# Brutal Downloader — Windows Desktop

Versi desktop terpisah dari aplikasi Android, dengan antarmuka PySide6 berbahasa Indonesia. Target paket portable: **Windows 10/11 x64**; build dan validasi lokal dilakukan di Windows 11 x64. Tidak perlu Python saat menjalankan paket `.exe`.

## Jalankan versi portable

1. Ekstrak `BrutalDownloader-Windows-x64-portable.zip` ke folder pilihan Anda.
2. Jalankan `Brutal Downloader.exe`.
3. Tempel URL, pilih platform/kualitas dan folder tujuan.
4. Klik **1. Inspeksi**, pilih media, lalu **2. Unduh**.
5. Gunakan **Buka tujuan** untuk melihat file. Folder default: `Downloads/BrutalDownloader` milik pengguna Windows.

Paket belum ditandatangani dengan sertifikat code-signing. Windows SmartScreen/antivirus mungkin menampilkan peringatan reputasi; jangan nonaktifkan perlindungan sistem. Verifikasi asal paket dan checksum SHA-256 sebelum menjalankan. Startup pertama dapat memerlukan waktu karena paket one-file mengekstrak dependensi ke folder sementara. Jangan menjalankan sebagai Administrator.

## Fitur dan batasan

- TikTok dan YouTube menggunakan **yt-dlp**; Instagram memakai **SnapSave**, lalu **curl-x**, kemudian yt-dlp jika resolver sebelumnya gagal.
- Foto dan carousel Instagram: pilih semua atau item tertentu, dengan urutan sumber dipertahankan.
- Tautan HTTP/HTTPS langsung ke gambar, audio, atau video yang didukung; bukan halaman web biasa.
- Pilihan BEST / 720 / 480 / 360 adalah preferensi, bukan jaminan. Video yt-dlp hanya menggunakan MP4 progresif berisi video **dan** audio. Jika target tidak tersedia, pilihan kualitas aktual dilaporkan di konsol. Instagram/tautan langsung menyimpan stream yang disediakan; tidak di-resize dan tidak dijamin sebagai kualitas asli/maksimum.
- **Audio saja** mempertahankan stream audio terpisah jika sumber menyediakannya. Tidak mengekstrak audio dari video dan tidak mengonversi menjadi MP3.
- Tidak membundel FFmpeg atau JavaScript runtime. Sumber yang hanya menyediakan HLS, DASH terpisah, DRM, atau membutuhkan login/challenge tidak dijamin bekerja. YouTube dapat meminta runtime JavaScript/challenge atau mengubah ketersediaan stream; versi awal ini dapat menolak video tersebut daripada membuat unduhan bisu/rusak. Tidak ada janji unduhan semua video.
- Progres dan konsol aktivitas, pembatalan kooperatif, folder tujuan pilihan pengguna, nama file aman Windows, dan penamaan unik agar tidak menimpa file lama.
- File ditulis di staging `.part` pada folder tujuan dan baru dipublikasikan setelah validasi signature/ukuran respons. Validasi tidak mendekode semua frame video/audio atau seluruh piksel gambar. Format palsu berheader valid masih bisa lolos. Publikasi menyalin ke nama unik; file akhir dapat terlihat sesaat selama salinan berlangsung.
- Bila suatu item gagal, batch berhenti. File yang sudah berhasil tetap tersimpan dan tercatat di konsol; staging item yang gagal dibersihkan. Proses mati mendadak atau listrik padam dapat meninggalkan staging/file parsial.
- Pembatalan/penutupan menunggu operasi jaringan aktif berhenti atau timeout; bukan penghentian paksa thread. Tidak ada antrean latar belakang, resume, pembaruan otomatis, atau penyimpanan pengaturan antar-sesi.

## Privasi dan layanan pihak ketiga

Inspeksi dan pengunduhan memerlukan internet. URL postingan Instagram publik dikirim ke SnapSave dan, jika perlu, curl-x. Kedua layanan mempunyai kebijakan privasi, batas permintaan, dan ketersediaan tersendiri. Respons kosong, rate limit, atau perubahan format bisa menyebabkan kegagalan. Tidak diperlukan API key; aplikasi tidak membaca cookie browser atau kata sandi akun. Jangan masukkan URL privat atau token sensitif. Konsol dapat berisi detail sumber unduhan; jangan membagikannya tanpa memeriksa isinya.

Unduh hanya konten yang Anda miliki atau diizinkan untuk disimpan, dan patuhi ketentuan platform serta hukum yang berlaku.

## Jalankan dari source (PowerShell)

Dari folder `desktop`, gunakan Python **3.12 x64**:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe main.py
```

Tidak perlu mengubah execution policy atau mengaktifkan virtual environment.

## Pengujian

```powershell
.\.venv\Scripts\python.exe -m unittest -v test_engine test_ui
.\.venv\Scripts\python.exe main.py --smoke-test
```

Tes otomatis memakai fixture/mock, bukan layanan eksternal. Pengujian mencakup parser carousel, fallback, URL bertanda tangan, seleksi kualitas, signature, staging/cleanup, pembatalan, dan alur GUI offscreen. Keberhasilan tes tidak menjamin layanan eksternal selalu tersedia. Uji URL publik nyata secara terpisah.

## Build portable (Windows x64)

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
.\.venv\Scripts\python.exe build_portable.py
```

Hasil:

- `dist/Brutal Downloader.exe` — aplikasi portable one-file.
- `dist/BrutalDownloader-Windows-x64-portable.zip` — aplikasi, petunjuk, dan pemberitahuan lisensi untuk dibagikan.
- `dist/BrutalDownloader-Windows-x64-portable.zip.sha256` — checksum paket.

Build script menjalankan smoke test GUI dan pemeriksaan import backend/ekstraktor terhadap executable hasil build (tanpa jaringan). ZIP juga menyertakan source desktop agar aplikasi dapat dibangun ulang dengan pustaka yang kompatibel. Ikon dibuat sendiri saat build. Sebelum distribusi publik, tinjau lisensi dependensi (`THIRD_PARTY_NOTICES.md` dan folder `licenses/` dalam ZIP), lakukan pemeriksaan keamanan, serta uji di Windows bersih tanpa Python/developer tools. Build di komputer pengembang bukan bukti kompatibilitas semua mesin Windows. Versi resolver bisa memerlukan pembaruan dan rebuild saat platform berubah.
