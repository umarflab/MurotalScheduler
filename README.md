# MurotalScheduler

Aplikasi Android offline untuk memutar murotal atau audio lain secara manual dan terjadwal.

## Fitur

- Impor beberapa berkas audio dari penyimpanan telepon.
- Putar satu track secara langsung.
- Buat dan putar playlist dari track yang telah diimpor.
- Jadwalkan waktu mulai dan waktu berhenti setiap hari.
- Atur volume media untuk setiap jadwal, terpisah dari playlist.
- Pilih hari aktif dan cara berhenti berdasarkan jam atau akhir audio.
- Gunakan mode Single, Sequential, atau Shuffle Cycle pada setiap jadwal.
- Terapkan fade-in 10 detik dan pulihkan volume media sebelumnya setelah jadwal selesai.
- Pulihkan jadwal setelah telepon dinyalakan ulang.
- Berjalan tanpa koneksi internet.

## Build

GitHub Actions membangun APK debug pada setiap push ke `main`. APK tersedia pada bagian **Actions > Build Android Debug APK > Artifacts**.

Build lokal memerlukan JDK 17, Android SDK 35, dan Gradle 8.7:

```bash
gradle assembleDebug
```

## Catatan izin

Pada Android 12 atau lebih baru, izinkan alarm presisi melalui pengaturan sistem agar jadwal dimulai tepat waktu. Pada Android 13 atau lebih baru, izinkan notifikasi agar status pemutaran terlihat.
