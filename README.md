# BrutalDownloader ⚡

An all-in-one media downloader for Android built with Kotlin and designed with a bold **Neo-Brutalism UI**.

Download public videos and reels from **TikTok**, **Instagram**, and **YouTube**, plus standalone original audio where the source provides it. Instagram photos and photo/video carousels are supported, along with direct photo/audio/video URLs. MP3 conversion is not implemented.

---

## 📸 Features

- **Brutalism UI Design:** High-contrast aesthetic with sharp neon orange `#FF5722`, cyber yellow `#FFE600`, and pitch black `#101010`. Monospace typography and crisp borders.
- **TikTok Downloader:**
  - Watermark-free downloads via TikWM API.
  - Uses the source HD or standard variant; actual dimensions are checked after download.
  - Downloads original audio when available, with an extension matching its container.
- **Instagram Downloader:**
  - Resolves public photos, Reels, videos, and mixed photo/video carousels.
  - For carousels, choose one numbered item or download all in source order. Per-item failures are reported without discarding successful downloads.
  - Detects JPEG, PNG, WebP, and GIF by their signatures, validates image decoding, and keeps the original format and dimensions. Resolution preferences do not resize photos.
  - Downloads the original source stream without transcoding or guaranteeing a specific codec.
  - The current resolver does not provide standalone audio or resolution metadata.
- **YouTube Downloader:**
  - Selects progressive MP4 video formats and standalone MP4/WebM audio when available; never falls back to video for an audio request.
- **Resolution Selector:** For YouTube, BEST chooses the largest reported height; other preferences choose the highest available height at or below the target, or the lowest available height if none fit. Fallbacks are reported. TikTok/Instagram do not guarantee exact resolutions. Completed videos show their actual dimensions, not a guessed quality label.
- **Original Audio:** Preserves the source container (such as M4A, MP3, or WebM); no MP3 conversion or audio extraction from a video container is performed. Unsupported/mislabeled responses are rejected before publication.
- **Safe Downloads:** Saves to `Downloads/BrutalDownloader`. Android 10+ uses pending MediaStore entries; Android 7–9 waits for storage permission and stages files before publication. Unique names prevent overwrites; handled transfer/validation failures remove pending output. Abrupt process termination can still leave staging files/pending entries. Legacy publication copies the validated staging file, so a final file may briefly be visible during that copy.
- **Clipboard Detection:** The paste button strips surrounding text and selects the source platform.

Downloads are tied to the current Activity and canceled when it is destroyed; background continuation and resume are not implemented. Media validation checks signatures and known response lengths. Photos are checked using Android image decoding with memory-bounded sampling; audio/video use Android-recognized tracks and initial sample availability. Validation does not fully decode every video/audio sample or guarantee compatibility on every device. Resolver availability depends on third-party services.

---

## 🛠️ Tech Stack & Requirements

- **Language:** Kotlin
- **Min SDK:** 24 (Android 7.0+)
- **Target SDK:** 34
- **Compile SDK:** 37
- **Build System:** Gradle 9.6 + Android Gradle Plugin 9.5
- **Networking:** OkHttp 4 + Coroutines

---

## 🚀 Building from Source

```bash
git clone https://github.com/Botann001/BrutalDownloader.git
cd BrutalDownloader
./gradlew assembleDebug
```

The debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Validation

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Unit tests cover image/audio/video format detection, carousel parsing, preservation of signed media URLs, quality fallback, and incomplete downloads. Instrumentation tests cover photo decoding and MIME checks with cache-only fixtures, MediaStore publication/rollback and duplicate-name safety on Android 10+, plus transaction failure handling. The connected tests require an emulator or device. Also manually check Android 7–9 permission grant/denial, low storage, network interruption, screen rotation, and actual public URLs from each resolver.
