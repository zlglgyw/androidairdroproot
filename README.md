# phone_server v3 — Server Otomasi HP Android (Termux)

Server HTTP kecil yang berjalan di Termux pada HP Android yang sudah di-root.
Server ini memungkinkan sebuah agen (manusia atau AI) mengendalikan layar HP
dari jarak jauh lewat jaringan Tailscale: melihat layar, mengetuk, mengetik,
menggeser, membuka aplikasi, membaca teks layar (OCR), dan merekam layar.

Repo ini adalah sisi HP dari sistem dua sisi. Sisi agen (skrip pemanggil,
monitor channel, antrean tugas, kunci layar) ada di repo
`androidairdroproot-agent`.

## Syarat

- HP Android yang sudah di-root (perintah perangkat dijalankan lewat `su`).
- [Termux](https://termux.dev/) terpasang.
- [Tailscale](https://tailscale.com/) terpasang dan tersambung (server hanya
  bisa dihubungi lewat IP Tailscale HP — tidak pernah lewat internet publik).
- Python 3 di Termux (`pkg install python`).
- Opsional untuk OCR: Tesseract (`pkg install tesseract`).

## Instalasi

```sh
# Di Termux:
git clone https://github.com/zlglgyw/androidairdroproot.git
cd androidairdroproot
bash install.sh
```

`install.sh` memasang Python dan Git, lalu menyalin
`phone_config.json.example` ke `~/phone_config.json` (hanya kalau belum ada —
konfigurasi yang sudah berisi token tidak pernah ditimpa).

Lalu edit `~/phone_config.json` dan isi nilai aslinya:

| Field | Isi |
| --- | --- |
| `token` | Token acak min. 20 karakter. Wajib ada di header `X-Token` setiap permintaan. |
| `host` | IP Tailscale HP ini. Server HANYA bind ke alamat ini. |
| `port` | Port HTTP server (default `8765`). |
| `allowed_packages` | Paket aplikasi yang boleh di-launch/dihentikan. Kosong = semua paket boleh. |
| `idle_timeout_minutes` | Server berhenti sendiri kalau tidak ada permintaan selama ini (default `30`). |

Jalankan server:

```sh
sh ~/run_server.sh
```

`run_server.sh` menjalankan `phone_server.py` dan menghidupkannya lagi
otomatis kalau berhenti (crash). Hentikan dengan `CTRL+C` dua kali, atau
lewat endpoint `/stop`.

## Endpoint

Semua permintaan wajib membawa header `X-Token` dengan nilai token dari
konfigurasi. Tanpa token yang benar, server menjawab `403`.

Aksi tunggal (`POST`, body JSON):

| Endpoint | Body | Keterangan |
| --- | --- | --- |
| `/tap` | `{"x": int, "y": int}` | Ketuk di koordinat. |
| `/tap_text` | `{"text": "..."}` | Ketuk elemen yang teks/deskripsinya mengandung teks. Ditolak kalau teks mengandung kata berisiko (lihat Keamanan). |
| `/tap_resource` | `{"resource": "..."}` | Ketuk elemen berdasarkan resource-id. |
| `/swipe` | `{"x1","y1","x2","y2"}` | Geser dari satu titik ke titik lain. |
| `/scroll` | `{"direction": "up"\|"down"\|"left"\|"right"}` | **Baru di v3.** Scroll arah dengan jarak terukur (titik tengah 60% lebar layar, 70% ke 30% dalam ~800ms). |
| `/long_press` | `{"x": int, "y": int}` | Ketuk dan tahan (~1 detik). |
| `/double_tap` | `{"x": int, "y": int}` | Ketuk dua kali. |
| `/type` | `{"text": "...", "clear": bool, "enter": bool}` | Ketik teks. **Baru di v3:** `clear=true` menghapus isi kolom dulu (Move End + 50x Delete), `enter=true` menekan Enter sesudah mengetik. |
| `/key` | `{"key": int}` | Tekan tombol (keyevent Android, mis. 3 Home, 4 Back, 66 Enter). |
| `/launch` | `{"package": "..."}` | Buka aplikasi (dibatasi `allowed_packages` kalau diisi). |
| `/stop_app` | `{"package": "..."}` | **Baru di v3.** Tutup paksa aplikasi (`am force-stop`). Aturan `allowed_packages` yang sama dengan `/launch`. |
| `/wait` | `{"seconds": float}` | Tunggu sekian detik. |

Pengamatan (`GET`):

| Endpoint | Keterangan |
| --- | --- |
| `/ping` | Cek hidup. Menjawab `{"ok": true, "version": 3}`. |
| `/health` | Baterai, uptime, idle timeout, **baru di v3:** `current_package` (aplikasi yang sedang di depan) dan `screen_on` (apakah layar menyala). Keduanya best-effort, `null` kalau tidak ketemu. |
| `/screenshot` | Gambar PNG layar saat ini. |
| `/ui` | Daftar node UI (teks, deskripsi, resource-id, koordinat tengah) + flag `captcha_detected`. |
| `/screen` | **Baru di v3.** Screenshot (base64 PNG) + node UI + flag captcha + ukuran layar, dalam SATU panggilan — menghemat round-trip di jaringan lambat. |
| `/ocr` | **Baru di v3.** Baca teks di layar dengan Tesseract (`--psm 6`). Menjawab `{"ok": true, "text": "..."}`. Kalau Tesseract belum terpasang: `{"ok": false, "error": "tesseract_not_installed", "hint": "pkg install tesseract"}`. Berguna untuk WebView/gambar yang tidak punya node UI. |
| `/trace` | **Baru di v3.** 50 baris terakhir log server. |
| `/logcat?n=200` | **Baru di v3.** Log sistem Android (`logcat -d -t n`, default 200, maks 1000). |
| `/recording` | **Baru di v3.** Unduh file video rekaman terakhir (MP4). `404` kalau belum ada rekaman. Catatan: mengunduh video lewat jaringan lambat bisa lama — rekam seperlunya saja. |
| `/macro/list` | Daftar macro tersimpan. |

Rekam layar (`POST`):

| Endpoint | Body | Keterangan |
| --- | --- | --- |
| `/record/start` | `{"time_limit": detik}` | **Baru di v3.** Mulai rekam layar (`screenrecord`). Default 30 detik, maks 180. Menjawab `{"ok": false, "error": "already_recording"}` kalau sudah ada rekaman yang jalan. |
| `/record/stop` | — | **Baru di v3.** Hentikan rekaman, menjawab `{"ok": true, "file": "/sdcard/phone_rec.mp4", "size_bytes": ...}`. |

Macro (`POST`):

| Endpoint | Body | Keterangan |
| --- | --- | --- |
| `/macro/save` | `{"name": "...", "steps": [...]}` | Simpan rangkaian langkah (aksi yang sama seperti di atas). |
| `/macro/play` | `{"name": "..."}` | Jalankan macro. Berhenti di langkah pertama yang gagal. |

Lainnya (`POST`):

| Endpoint | Keterangan |
| --- | --- |
| `/stop` | Matikan server. |
| `/rotate_token` | Ganti token (`{"new_token": "..."}`, min. 20 karakter). Token lama langsung tidak berlaku. Token baru harus diteruskan ke agen lewat jalur yang disepakati — bukan lewat chat biasa. |

## Keamanan

- **Token wajib.** Semua endpoint menolak permintaan tanpa header `X-Token`
  yang benar (`403`).
- **Bind Tailscale-only.** Server hanya mendengarkan di IP Tailscale HP,
  tidak pernah di `0.0.0.0`. Tanpa akses ke jaringan Tailscale pemilik,
  server tidak bisa dijangkau.
- **Tanpa shell bebas.** Hanya perintah allowlist yang diizinkan
  (ketuk, geser, ketik, tombol, launch, dan sebagainya). Tidak ada endpoint
  yang menjalankan perintah shell sembarangan.
- **Blok kata berisiko.** `/tap_text` menolak mengetuk tombol yang teksnya
  mengandung kata berisiko (beli, bayar, transfer, withdraw, connect wallet,
  approve, sign, dan sebagainya), kecuali permintaan membawa
  `"allow_risky": true` secara eksplisit — dan agen tidak boleh mengeset itu
  tanpa persetujuan pemilik setelah melihat preview.
- **Deteksi captcha.** `/ui` dan `/screen` membawa flag `captcha_detected`
  kalau teks layar mengandung kata-kata captcha/verifikasi manusia.
- **Idle timeout.** Server berhenti sendiri setelah tidak ada permintaan
  selama `idle_timeout_minutes` (default 30 menit).
- **Log hanya teks.** Log server (`~/phone_server.log`) hanya mencatat waktu,
  aksi, dan hasil — tanpa screenshot dan tanpa token.
- **Konfigurasi terpisah.** Token dan IP tinggal di `~/phone_config.json`,
  bukan di kode. Update server tidak menghapus konfigurasi. File ini
  masuk `.gitignore` — jangan pernah meng-commit-nya.

## Troubleshooting

- **Server tidak merespons:** cek apakah Termux masih jalan (Android bisa
  mematikan Termux) dan Tailscale tersambung. Jalankan lagi
  `sh ~/run_server.sh` di Termux.
- **Semua permintaan `403`:** token di header tidak cocok dengan
  `~/phone_config.json`.
- **`/ocr` menjawab `tesseract_not_installed`:** jalankan
  `pkg install tesseract` di Termux.
- **Screenshot gagal (`screencap failed`):** pastikan HP sudah di-root dan
  perintah `su` berfungsi.
- **Server mati sendiri:** itu idle timeout (default 30 menit) — jalankan
  lagi, atau kirim `/ping` berkala dari sisi agen.

## Riwayat versi

- **v3** — `/screen`, `/scroll`, `/type` clear/enter, `/stop_app`,
  `current_package` + `screen_on` di `/health`, `/ocr`, rekam layar
  (`/record/start`, `/record/stop`, `/recording`), `/trace`, `/logcat`.
  Terinspirasi proyek Artemis ([google/artemis](https://github.com/google/artemis)).
- **v2** — Server dasar: aksi tap/type/key/launch, screenshot, UI dump,
  macro, rotasi token, idle watchdog.
