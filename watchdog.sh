#!/data/data/com.termux/files/usr/bin/bash
# watchdog.sh — Jaga phone_server tetap bisa dijangkau
# - Pastikan layar tetap NYALA (hidupkan lagi kalau mati)
# - Pastikan wake lock Termux aktif
# - Log ke ~/watchdog.log
# Dijalankan di Termux HP, background, dengan root (su).
# Dibuat 13 Nov 2026 atas permintaan Pak.

LOG="$HOME/watchdog.log"
INTERVAL=30  # cek tiap 30 detik

log() {
  echo "$(date +%Y-%m-%d\ %H:%M:%S) $1" >> "$LOG"
}

# Pastikan wake lock aktif
termux-wake-lock 2>/dev/null
log "watchdog mulai (PID $$), interval ${INTERVAL}s"

# Exempt Termux + Tailscale dari Doze (butuh root, sekali saja)
su -c "dumpsys deviceidle whitelist +com.termux" 2>/dev/null
su -c "dumpsys deviceidle whitelist +com.tailscale.ipn" 2>/dev/null
log "Doze whitelist: com.termux + com.tailscale.ipn"

while true; do
  # Cek status layar via dumpsys power (butuh root untuk hasil akurat)
  SCREEN_ON=$(su -c "dumpsys power" 2>/dev/null | grep -m1 "mScreenOn" | grep -c "true")

  if [ "$SCREEN_ON" != "1" ]; then
    log "LAYAR MATI terdeteksi — menghidupkan..."
    su -c "input keyevent 26" 2>/dev/null
    sleep 2
    # Verifikasi
    SCREEN_ON2=$(su -c "dumpsys power" 2>/dev/null | grep -m1 "mScreenOn" | grep -c "true")
    if [ "$SCREEN_ON2" = "1" ]; then
      log "Layar berhasil dihidupkan"
    else
      log "GAGAL hidupkan layar (coba lagi ${INTERVAL}s lagi)"
    fi
  fi

  # Pastikan wake lock masih aktif (termux-wake-lock idempotent)
  termux-wake-lock 2>/dev/null

  sleep "$INTERVAL"
done
