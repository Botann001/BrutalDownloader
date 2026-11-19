# BrutalDownloader ⚡

An all-in-one media downloader for Android built with Kotlin and designed with a bold **Neo-Brutalism UI**.

Download videos, reels, photos, and extract audio from **TikTok**, **Instagram**, and **YouTube** in a single app.

---

## 📸 Features

- **Brutalism UI Design:** High-contrast aesthetic with sharp neon orange `#FF5722`, cyber yellow `#FFE600`, and pitch black `#101010`. Monospace typography and crisp borders.
- **TikTok Downloader:**
  - Watermark-free downloads via TikWM API.
  - Supports full HD (1080p) and standard quality.
  - Audio extraction (MP3).
- **Instagram Downloader:**
  - High-speed progressive MP4 extraction for Reels, videos, and posts.
  - Muxed H.264 (AVC) + AAC stereo audio playable on any device.
  - Auto-scans to Android MediaStore for instant Gallery display.
- **YouTube Downloader:**
  - Extracts progressive MP4 video formats and standalone audio.
- **Resolution Selector:** Choose between `BEST / MAXIMUM (HD / 1080P)`, `HIGH (720P)`, `MEDIUM (480P)`, and `DATA SAVER (360P)`.
- **Clipboard Auto-Detection:** Automatically strips unnecessary text and detects the source platform.

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
