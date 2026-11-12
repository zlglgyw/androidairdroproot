#!/usr/bin/env bash
# Setup phone_server v3 di Termux (Android root).
# Jalankan sekali setelah clone:
#   bash install.sh
# Script ini TIDAK menulis secret apa pun — token dan IP diisi manual
# di ~/phone_config.json setelah script selesai.
set -euo pipefail

echo "== phone_server v3 — setup Termux =="

# Paket dasar: python3 untuk server, git untuk update repo.
pkg install -y python git

# OCR (opsional): endpoint /ocr butuh Tesseract. Kalau tidak diinstall,
# /ocr menjawab error tesseract_not_installed; server tetap jalan normal.
# Untuk mengaktifkannya, jalankan:  pkg install -y tesseract
if command -v tesseract >/dev/null 2>&1; then
  echo "Tesseract sudah terpasang (OCR aktif)."
else
  echo "Tesseract belum terpasang (opsional, untuk /ocr)."
  echo "  Aktifkan dengan: pkg install -y tesseract"
fi

# File server: salin ke home. Clone membuat folder sendiri
# (~/androidairdroproot), sementara server dijalankan dari
# ~/phone_server.py — jadi salin ke home di sini.
REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$REPO_DIR" != "$HOME" ]; then
  if [ -f "$HOME/phone_server.py" ]; then
    cp "$HOME/phone_server.py" "$HOME/phone_server.py.bak"
    echo "phone_server.py lama dibackup ke phone_server.py.bak."
  fi
  cp "$REPO_DIR/phone_server.py" "$HOME/phone_server.py"
  cp "$REPO_DIR/run_server.sh" "$HOME/run_server.sh"
  echo "phone_server.py + run_server.sh disalin ke home."
fi

# Konfigurasi: salin contoh HANYA kalau belum ada — jangan pernah timpa
# konfigurasi yang sudah berisi token asli.
if [ -f "$HOME/phone_config.json" ]; then
  echo "phone_config.json sudah ada — tidak ditimpa."
else
  cp "$(dirname "$0")/phone_config.json.example" "$HOME/phone_config.json"
  echo "phone_config.json dibuat dari contoh."
fi

echo ""
echo "Langkah berikutnya:"
echo "  1. Edit ~/phone_config.json: isi token acak (min. 20 karakter)"
echo "     dan host dengan IP Tailscale HP ini."
echo "  2. Pastikan Tailscale tersambung di HP ini."
echo "  3. Jalankan server:  sh ~/run_server.sh"
echo "     (atau: python3 ~/phone_server.py untuk sekali jalan)"
echo "  4. Dari mesin agen, tes: curl dengan header X-Token ke /ping."
echo ""
echo "Selesai."
