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
- Edit jadwal yang telah disimpan tanpa membuat alarm ganda.
- Atur equalizer lima pita dan preset Voice, Room, Concert, Ballroom, Hall, serta Plate secara terpisah pada setiap jadwal.
- Preset ruang memakai jalur auxiliary reverb dengan intensitas berbeda untuk Room, Ballroom, Concert, Hall, dan Plate.
- Tampilkan panel pemutar dengan posisi, durasi, seek bar, jeda/lanjut, dan berhenti.
- Tampilkan equalizer sebagai lima slider vertikal.
- Simpan kemajuan playlist per jadwal: mode setelah-audio-selesai memutar satu track per kejadian, lalu melanjutkan track berikutnya tanpa pengulangan pada siklus yang sama.
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
