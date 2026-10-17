# AutoTube

[![Build](https://github.com/zntrimi/autotube/actions/workflows/build.yml/badge.svg)](https://github.com/zntrimi/autotube/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/zntrimi/autotube?include_prereleases)](https://github.com/zntrimi/autotube/releases)

**Play YouTube videos on your Android Auto head unit.** Queue videos on your phone; the car just plays them.

[日本語の README はこちら](README.ja.md)

> [!WARNING]
> **Video keeps playing while the car is moving.** AutoTube presents itself to Android Auto as a navigation app, so Android Auto's driving restrictions do not apply. Watching video while driving is dangerous and illegal in many places. **The driver must not watch while driving.** You use this app entirely at your own risk.
>
> AutoTube is an **unofficial** app and is not affiliated with Google or YouTube. It does not use the official YouTube API; it extracts streams with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor). This is a gray area under YouTube's Terms of Service, and YouTube changes can break it at any time.

## Screenshots

**Car (Android Auto)** — full screen, and the split dashboard (media card on the left, video on the right)

<p>
  <img src="docs/screenshots/car-fullscreen.png" width="49%" alt="Car screen, full screen">
  <img src="docs/screenshots/car-split.png" width="49%" alt="Car screen, split dashboard">
</p>

**Phone** — queue (while playing in the car), in-app search, settings

<p>
  <img src="docs/screenshots/phone-queue.png" width="32%" alt="Queue">
  <img src="docs/screenshots/phone-search.png" width="32%" alt="Search">
  <img src="docs/screenshots/phone-settings.png" width="32%" alt="Settings">
</p>

<sub>Videos shown are Blender Studio open movies (Spring, Coffee Run, Sprite Fright, CHARGE — © Blender Foundation, <a href="https://creativecommons.org/licenses/by/4.0/">CC BY</a>). Car screenshots from the Android Auto Desktop Head Unit; phone screenshots from a Galaxy Z Fold and the Android emulator.</sub>

## Features

- **Search YouTube inside the app** (with suggestions) and add to the queue. You can also add from the YouTube app's *Share* menu, by copying a URL, or by pasting a playlist URL (up to 200 videos).
- **Resumes where you left off** when you connect to the car (video and position). Steering-wheel play / next / previous keys and the phone's notification / lock screen controls work too.
- **No ads** — the video file is played directly. Sponsor segments and self-promotion inside videos are skipped with [SponsorBlock](https://sponsor.ajay.app) (can be turned off).
- **Built not to stall**: avoids YouTube's per-request throttling, buffers up to 5 minutes ahead, recovers automatically from tunnels and dead zones, swaps expired stream URLs in the background when your network changes (home Wi-Fi → 5G), and retries on its own when something fails.
- Material 3 phone UI: swipe to remove / mark watched (with undo), drag to reorder.

> [!NOTE]
> The app's UI is currently **Japanese only**. Translations are very welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Installation

### Why it has to look like it came from the Play Store

In a real car, Android Auto only lists Car App Library apps (navigation, parking, charging, …) whose **installer is the Google Play Store** (`com.android.vending`).

- Installing the APK directly, or from F-Droid, Galaxy Store or any other store → **the app does not show up in the car** (it does show up in the Desktop Head Unit).
- Android Auto's developer setting *Unknown sources* only applies to media and messaging apps, not to this kind of app.
- Publishing on Google Play is not realistic either: an app that plays YouTube without ads, inside a navigation surface, would not pass review.

### Option 1: install with adb, setting the installer to Play (recommended)

If you install over adb and declare the Play Store as the installer, Android Auto treats the app as if it came from Play:

```bash
adb install -r -i com.android.vending AutoTube.apk
```

1. Enable *Developer options* → *USB debugging* (or *Wireless debugging*) on the phone.
2. Download `AutoTube-<version>.apk` from [Releases](https://github.com/zntrimi/autotube/releases) (or [build it yourself](#building)).
3. Run the command above, then **reconnect to the car** — Android Auto only rebuilds its app list when a connection starts.

You need to install **every update the same way**. Opening an APK on the phone to update it changes the installer, and the app disappears from the car.

### Option 2: your own Play Console internal testing track

With a Google Play developer account (one-time US$25 fee) you can upload your own build to the *internal testing* track and install it from Play. Internal testing has no review, and updates then only need the phone.

1. Change `applicationId` in `app/build.gradle.kts` to your own (`com.autotube.zentaro` belongs to the maintainer's Play account).
2. Create the app in Play Console, add yourself as an internal tester and open the opt-in link once.
3. Build an AAB with `scripts/release.sh` and upload it (see [Releasing](#releasing)).

## Usage

### Adding videos

- Tap 🔍 (top right), search, and tap **+**. Use **⋮** for *Play now* / *Play next*.
- In the YouTube app: *Share* → AutoTube.
- Copy a YouTube URL, then open AutoTube — an *Add* card appears above the queue. You can also paste a URL into the search box.
- Paste a **playlist URL** (`youtube.com/playlist?list=…`) to add the whole list (up to 200, duplicates skipped).

### In the car

- Open AutoTube and it **continues from where you left off** (can be turned off in settings).
- Top right: previous / play-pause / next. Right side: −10 s / +30 s / stop.
- Watched videos are skipped by auto-advance. Playback stops when there is nothing left.
- The card in the top-left shows the title for 5 seconds after a video changes, and stays while paused, loading, on error, or when the queue is empty.
- Android Auto hides navigation-screen buttons after a few seconds; the first tap only brings them back.

### On the phone

- **Queue**: the now-playing panel at the top (shows "playing in the car" while connected). Tap to play, swipe right to mark watched, swipe left to remove (both can be undone), drag the handle to reorder, **⋮** for *Play next* / *Move to top* / *Open in YouTube*.
- **Search**: suggestions while typing, infinite scroll. Live streams are hidden because they can't be played.
- **Settings**: maximum quality (360p / 720p / 1080p), auto-play on connect, SponsorBlock, battery optimization, and troubleshooting.
- Playback in the car continues when you close the phone app.

### When playback stops

Normally nothing is needed — the app recovers by itself:

| Situation | What the app does |
|---|---|
| No signal / tunnel | Shows "waiting for network" and continues when it's back |
| Stream URL expired / network changed | Fetches a new URL in the background and keeps playing |
| Video deleted, private, etc. | Skips to the next video and marks it "unavailable" in the queue |
| Anything else | Shows "couldn't play" and retries automatically after 10 → 20 → 40 → 60 s |

To fix it by hand, **press ▶** (in the car or on the phone) — it reloads and resumes at the same position. If that doesn't help, go to *Settings (設定) → Troubleshooting (困ったとき)* and try, in order: *Rebuild playback (再生を立て直す)* → *Reset playback (再生をすべてリセット)* → *Restart app (アプリを再起動)*. Use *View / share logs (ログを見る・共有する)* to attach logs to a bug report.

## How it works

AutoTube **declares itself as a navigation app to get the `NavigationTemplate` surface**, and renders video onto it with ExoPlayer. Canvas drawing and MediaCodec can't share a surface, so the whole car surface is handed to the player; the title and status are shown in the NavigationInfo card instead.

| Part | Implementation |
|---|---|
| Stream URLs | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (`youtube/YouTubeExtractor.kt`). Video-only MP4 (up to the configured height, 720p by default; no AV1; prefers 30 fps at the same height) + M4A audio, merged with `MergingMediaSource`. Falls back to any-format merge, then 360p progressive. Results are cached for 45 minutes and the next video is prefetched. |
| Playback | media3 ExoPlayer (`player/PlayerEngine.kt`). The car surface wins; otherwise the phone's PlayerView. |
| MediaSession | `player/PlaybackService.kt` (media3 `MediaSessionService`). Next / previous are routed to AutoTube's own queue (`QueuePlayer`). Runs as a foreground service while playing. |
| Throttling | YouTube sends only the first 8–10 MB of a request for more than ~10 MB at full speed, then throttles to about 3.3 Mbps. ExoPlayer normally reads with an open-ended range, so `player/ChunkedDataSource.kt` splits reads into 4 MB ranges, each served at full speed. Buffers up to 5 minutes (96 MB). |
| Network loss | Network errors (disconnects, timeouts, 5xx) are retried inside the loader indefinitely and never surface to the player (which would discard the buffer). Stream URLs are bound to the client IP, so on 403 `ChunkedDataSource` fetches a fresh URL for the same itag in the background. Quality is not lowered while offline. |
| Stalls / failures | If the video effect pipeline fails, re-prepare; after two failures, fall back to center-crop. If the buffer doesn't grow for 20 s, step down one candidate (up to 3 times). When everything fails, retry with increasing intervals. |
| Audio focus | Requested when playback starts, but **losing it does not pause** (so sharing the next video from the YouTube app doesn't stop the car). |
| Layout | Largest size that fits the surface (`Presentation` effect). In the split dashboard, fits into the host's visible area (`CarLayoutTransformation`). |
| Queue | `queue/QueueStore.kt`, saved to `filesDir/queue.json` with position, watched and unavailable flags. |
| Phone UI | Jetpack Compose + Material 3 (`phone/ui/`). Reordering with [Reorderable](https://github.com/Calvin-LL/Reorderable). |
| SponsorBlock | `youtube/SponsorBlock.kt`. When enabled, the ID of the video being played is sent to the SponsorBlock server. |

## Building

Requires JDK 21 and the Android SDK.

```bash
export JAVA_HOME=/path/to/jdk-21
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew :app:assembleDebug
adb install -r -i com.android.vending app/build/outputs/apk/debug/app-debug.apk
```

- The debug build is `com.autotube.zentaro.debug`, labelled "AutoTube 開発版" (dev build), and installs side by side with the release build (separate queue).
- **Real cars ignore debuggable apps**, so use the release build in a car (`./gradlew :app:assembleRelease`). The Desktop Head Unit shows debug builds too.
- Signing: if `~/.android/personal-release.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`) exists, both debug and release are signed with that key; otherwise with the Android debug key. Keys never go into the repository.
- NewPipeExtractor: an unreleased fix that keeps up with a recent YouTube change is vendored in `localMaven/` (sources included; see [`localMaven/README.md`](localMaven/README.md)). Switch back to an upstream release in `gradle/libs.versions.toml` once one is available.

### Testing without a car (Desktop Head Unit)

Use the *Android Auto Desktop Head Unit Emulator* from the Android SDK (`extras;google;auto`).

1. In Android Auto settings on the phone, tap *Version* 10 times to enable developer settings, then **⋮ → Start head unit server** (press it again every time the DHU disconnects).
2. Connect the phone with adb (USB or wireless debugging) and run:

```bash
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

You can drive the DHU through its stdin: `screenshot out.png`, `tap 400 240`, `keycode media_next`, … (`help` lists everything). Reinstalling the app with `adb install` while connected drops the connection; start the head unit server again afterwards.

### Releasing

- **GitHub release (APK):** `scripts/github-release.sh <version>` builds a release APK and publishes it as `AutoTube-<version>.apk` with `gh release create`.
- **Play internal testing (maintainer):** `scripts/release.sh` builds an AAB. With `--upload`, it uploads to the internal testing track and updates the connected phone via the Play Store (`scripts/play-track.sh`). Set the service account invited in Play Console and a gcloud account that can impersonate it:

```bash
export PLAY_SERVICE_ACCOUNT=play-uploader@<project>.iam.gserviceaccount.com
export PLAY_GCLOUD_ACCOUNT=<gcloud account with Service Account Token Creator>
scripts/release.sh --upload
```

## Contributing

Bug reports, translations and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## License

[GPL-3.0](LICENSE). AutoTube bundles [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), which is GPL-3.0, so the app is distributed under the same license.

YouTube is a trademark of Google LLC. This project is not affiliated with, endorsed by, or sponsored by Google or YouTube.
