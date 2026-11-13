# getgua client — aplikasi kendali HP native

Aplikasi Android (Kotlin, tanpa dependensi eksternal) yang menggantikan
`phone_server.py` sebagai "tangan dan mata" agen di HP Infinix Pak.
Agen mengendalikannya lewat HTTP (port **8766**) dengan header `X-Token`,
sama seperti phone_server — tapi dengan tiga perbaikan besar:

| Masalah phone_server | Perbaikan di getgua client |
|---|---|
| Termux kadang di-kill Android (terbukti 12 Nov 2026 saat layar mati) | Foreground service + notifikasi persisten + autostart sesudah reboot — jauh lebih tahan dibunuh. Tanpa idle timeout (phone_server berhenti sendiri sesudah 30 menit idle). |
| Screenshot PNG ~324 KB: lambat, sering terpotong di jaringan HP yang lambat | Screenshot **JPEG kualitas 70: ~95 KB (3,4x lebih kecil)**, masih jelas dibaca. Kualitas + lebar bisa diatur per permintaan. Opsi PNG tetap ada. |
| **Klik sering meleset** (koordinat UI dump tidak tepat, tap tidak bereaksi, layout bergeser) | Klik UTAMA via **AccessibilityService**: klik node langsung (`ACTION_CLICK`, tanpa koordinat — tidak bisa meleset), tunggu UI idle sebelum aksi, verifikasi sesudah tap + retry otomatis (coba lagi → long-press → fallback tap koordinat via root dengan tahan ~100ms). |

Tidak butuh Xposed atau module Magisk — cukup root biasa (`su`) +
AccessibilityService bawaan Android.

## Arsitektur

```
Agen (cloud) --HTTPS/Tailscale--> getgua client :8766 (foreground service)
                                       |
                    +------------------+------------------+
                    |                                     |
          AccessibilityService                  Root (su -c)
          - klik node langsung                  - screencap, input,
          - dispatchGesture                       uiautomator, dumpsys,
          - baca pohon UI                         pidof/kill, tesseract,
          - tunggu UI idle                        logcat, screenrecord
```

- **HTTP server hand-rolled** (`HttpServer.kt`, tanpa dependensi):
  `ServerSocket` di `0.0.0.0:8766`, thread pool 4, satu koneksi satu
  permintaan. JSON memakai `org.json` bawaan framework.
- **Token**: dibaca dari file config yang sama dengan phone_server
  (`/data/data/com.termux/files/home/phone_config.json`) lewat root,
  di-cache 60 detik. **Tidak ada token hardcode di aplikasi ini dan
  tidak ada secret di repo** (repo ini publik).
- **Log**: `getgua-client.log` di storage internal aplikasi (teks saja,
  tanpa token/screenshot), diputar otomatis di ~200 KB. Dibaca lewat
  endpoint `/trace`.

## Endpoint

Cerminan `phone_server.py` v3 (bentuk JSON dan kode `info` sama).
Semua permintaan wajib header `X-Token`. Aksi tunggal adalah POST
dengan body JSON. GET yang disebut di bawah juga GET seperti di
phone_server.

| Endpoint | Metode | Keterangan |
|---|---|---|
| `/ping` | GET | `{"ok":true,"version":"1.0.0"}` |
| `/health` | GET | Baterai, uptime, current_package, screen_on, + `accessibility_active`, `root_available`, `token_configured` |
| `/screenshot` | GET | JPEG default. Param: `quality` (default 70), `width` (perkecil lebar, opsional), `format=png` (bila perlu PNG) |
| `/screen` | GET | JSON: screenshot base64 **JPEG** (`format:"jpeg"`) + nodes + `ui_source` + `captcha_detected` + ukuran layar |
| `/ui` | GET | UI dump: nodes + `ui_source` (`accessibility` atau `uiautomator`) + `captcha_detected` |
| `/ocr` | GET | Teks hasil Tesseract (Termux). Bila Tesseract belum terinstall: `tesseract_not_installed` |
| `/recording` | GET | File MP4 rekaman terakhir |
| `/trace` | GET | 50 baris log terakhir |
| `/logcat` | GET | Logcat. Param `n` (default 200, maks 1000) |
| `/tap` | POST | `{"x":..,"y":..,"verify":true,"retries":2}` |
| `/tap_text` | POST | `{"text":"..","allow_risky":false,"verify":true}` |
| `/tap_resource` | POST | `{"resource":"..","verify":true}` |
| `/swipe` | POST | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| `/scroll` | POST | `{"direction":"down\|up\|left\|right"}` (pola Artemis, 800ms) |
| `/long_press` | POST | `{"x":..,"y":..,"duration":1000}` |
| `/double_tap` | POST | `{"x":..,"y":..}` |
| `/type` | POST | `{"text":"..","clear":false,"enter":false}` |
| `/key` | POST | `{"key":26}` (kode tombol Android) |
| `/launch` | POST | `{"package":"com..."}` |
| `/stop_app` | POST | `{"package":"com..."}` |
| `/wait` | POST | `{"seconds":1}` |
| `/record/start` | POST | `{"time_limit":30}` (maks 180 detik) |
| `/record/stop` | POST | Berhentikan rekaman, kembalikan ukuran file |
| `/rotate_token` | POST | `{"new_token":"..."}` (min 20 karakter) |

Respons tap membawa tiga field tambahan dibanding phone_server:

```json
{"ok": true, "info": "tap_text", "method": "a11y_click", "attempts": 1, "ui_changed": true}
```

- `method`: cara yang akhirnya berefek (`a11y_click`, `a11y_long_click`,
  `gesture`, `gesture_long`, `root_hold`, `root_tap`).
- `attempts`: jumlah percobaan yang dilakukan.
- `ui_changed`: apakah UI berubah sesudah tap. `false` berarti tap sudah
  dieksekusi tapi UI tidak berubah — agen bisa memutuskan sendiri
  (mis. tap memang tidak diharapkan mengubah UI). Set `verify:false`
  bila tap tidak diharapkan mengubah UI, supaya tidak ada tap ulang.

Perilaku keamanan (disalin dari phone_server):

- `tap_text` ke tombol berisiko (beli/bayar/transfer/connect wallet/
  approve/sign/…) **ditolak** kecuali body membawa `allow_risky:true`.
- `/stop_app` **menolak** mematikan `com.termux`, `com.tailscale.ipn`,
  dan `com.getgua.client` sendiri (`blocked_protected_package`).
- `allowed_packages` di `phone_config.json` (bila diisi) membatasi
  `/launch` dan `/stop_app`, seperti di phone_server.

### Contoh curl (dari VM, lewat proxy Tailscale)

```bash
TOKEN="..."   # token yang sama dengan phone_server; JANGAN ditulis di repo/chat
P="--proxy ${HTTPS_PROXY%:*}:3130"
curl $P -H "X-Token: $TOKEN" http://100.112.198.112:8766/ping
curl $P -H "X-Token: $TOKEN" "http://100.112.198.112:8766/screenshot?quality=70" -o shot.jpg
curl $P -H "X-Token: $TOKEN" http://100.112.198.112:8766/ui
curl $P -H "X-Token: $TOKEN" -X POST -H 'Content-Type: application/json' \
  -d '{"text":"Kirim"}' http://100.112.198.112:8766/tap_text
```

## Quirks XOS Infinix yang ditangani

- **`am force-stop` TIDAK PERNAH bekerja** di ROM ini (error Binder
  "Failed transaction"). `/stop_app`: coba force-stop → verifikasi via
  **root pidof** hingga 10 detik → SIGKILL semua PID → verifikasi lagi.
  Pidof tanpa root buta terhadap proses aplikasi lain (hidepid).
- **`settings put/get` gagal** dengan error Binder yang sama — aplikasi
  ini tidak mengandalkannya (mis. tidak ada pengaturan brightness).
- **Screenshot gagal di aplikasi FLAG_SECURE** (MetaMask, Bitget):
  dicoba `screencap` biasa lalu `su -c screencap`; keduanya
  kemungkinan tetap gagal karena SurfaceFlinger memblokir — untuk
  aplikasi itu pakai `/ui` (UI dump tetap bekerja). Lihat catatan
  "Belum teruji" di bawah.
- **Jangan pernah kill Termux/Tailscale** (lihat perilaku keamanan).

## Build

```bash
cd android-client
gradle :app:assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Butuh JDK 17 + Android SDK (API 34). Build resmi lewat GitHub Actions
(`.github/workflows/android-client.yml`, trigger hanya bila
`android-client/**` berubah): APK tersedia sebagai artifact
`getgua-client-apk`. Tanpa dependensi eksternal, jadi build cepat.

## Install (langkah manual Pak)

1. Unduh APK dari artifact Actions (atau dari rilis), install di HP
   Infinix (izinkan "Install unknown apps" bila ditanya).
2. **Buka aplikasi sekali** (wajib — supaya autostart sesudah reboot
   aktif dan service bisa jalan).
3. Tekan **Mulai service**. Saat prompt root muncul, pilih
   **Izinkan** (atau "Allow" / "Grant"). Tanpa root, screenshot,
   tap fallback, dan baca token tidak bekerja — status Root di
   aplikasi akan menunjukkannya.
4. Tekan **Buka pengaturan Aksesibilitas**, aktifkan **getgua client**.
   Tanpa ini aplikasi tetap jalan, tapi klik memakai jalur fallback
   (koordinat via root) seperti phone_server — keunggulan
   anti-melesetnya hilang.
5. Tekan **Bebaskan dari battery optimization**, pilih izinkan.
   Bila XOS menanyakan autostart, izinkan juga.
6. Periksa status di aplikasi: Service JALAN, Token terbaca, Root
   tersedia, Aksesibilitas AKTIF. Agen lalu bisa memanggil
   `http://100.112.198.112:8766/ping` untuk verifikasi.

Phone_server (port 8765) tetap jalan berdampingan selama masa tes —
tidak ada yang dimatikan.

## Mengalihkan ps.sh ke port 8766 (sesudah tes lulus)

`ps.sh` memakai port 8765. Sesudah semua endpoint di 8766 terverifikasi
di HP, ubah port di `ps.sh` menjadi 8766 dan perhatikan dua perbedaan:

- `/screenshot` kini JPEG: simpan dengan ekstensi `.jpg`.
- `/screen` mengembalikan base64 **JPEG**, bukan PNG.

phone_server tetap dipertahankan sebagai fallback; matikan hanya bila
Pak memerintahkan.

## Yang belum teruji (butuh HP)

- [ ] Semua endpoint di HP sungguhan (build ini baru terverifikasi
      compile + CI; belum pernah install di HP).
- [ ] Apakah `su -c screencap` menembus FLAG_SECURE di ROM ini
      (dugaan: tidak — SurfaceFlinger tetap memblokir).
- [ ] Apakah XOS mengizinkan foreground service + autostart bertahan
      (perlu diamati beberapa hari; XOS dikenal agresif membunuh
      aplikasi latar).
- [ ] Latensi nyata `su -c` per perintah dari UID aplikasi
      (phone_server memanggil `su` dari Termux; dari aplikasi bisa
      sedikit berbeda).
- [ ] Apakah `dispatchGesture` bekerja di semua aplikasi target
      (Telegraph, Chrome, Bitget DApp browser).
- [ ] Prompt root pertama: bentuknya di HP ini (Magisk? KernelSU?)
      dan apakah izinnya persisten.

## Catatan desain

- `phone_server.py` tidak diubah; repo ini berisi keduanya sampai
  migrasi selesai dan Pak memerintahkan mematikan phone_server.
- Endpoint `/macro/*` dan `/stop` dari phone_server sengaja TIDAK
  dibawa: macro tidak dipakai ps.sh, dan service tidak boleh bisa
  dimatikan dari jarak jauh tanpa sengaja (berhentikan dari UI
  aplikasi bila perlu).
- Tidak ada idle timeout: service ini dirancang selalu hidup.
- Tidak ada analytics, iklan, atau permission di luar yang tercantum
  di `AndroidManifest.xml`.
