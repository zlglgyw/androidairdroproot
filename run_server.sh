#!/bin/sh
# Pembungkus server v3: hidup ulang otomatis bila crash.
# Jalankan dari Termux biasa (bukan shell root):
#   sh ~/run_server.sh
# Hentikan dengan CTRL+C dua kali, atau lewat endpoint /stop.
while true; do
  python3 ~/phone_server.py
  echo "Server berhenti, restart dalam 5 detik (CTRL+C untuk berhenti)..."
  sleep 5
done
