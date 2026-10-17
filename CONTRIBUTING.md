# Contributing to AutoTube

Thanks for your interest! Bug reports, translations, documentation fixes and pull requests are all welcome.

日本語での Issue・PR も歓迎します。

## Reporting bugs

Please [open an issue](https://github.com/zntrimi/autotube/issues/new/choose) using the bug report template and include:

- **Logs.** In the app: *Settings (設定) → View / share logs (ログを見る・共有する) → 共有*. The log's first line also contains the app version and device. Please check the log for anything you'd rather not share before posting (it contains video IDs and titles).
- Where it happened: a real car (which model / head unit), the Desktop Head Unit, or the phone only.
- Phone model, Android version and Android Auto version.
- The video URL, if the problem only happens with a specific video.

**If extraction is broken for every video** ("couldn't play" everywhere), YouTube has most likely changed something on their side. Check [NewPipeExtractor's issues](https://github.com/TeamNewPipe/NewPipeExtractor/issues) first — the fix usually lands there, and AutoTube then only needs a dependency update.

## Development setup

- JDK 21, Android SDK (compileSdk 37). Android Studio works, or just the command line:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug
adb install -r -i com.android.vending app/build/outputs/apk/debug/app-debug.apk
```

- The debug build (`com.autotube.zentaro.debug`) installs next to the release build, so you can keep using the app while developing.
- See [Testing without a car](README.md#testing-without-a-car-desktop-head-unit) for the Desktop Head Unit.

### Project layout

```
app/src/main/java/dev/imaizentarou/autotube/
├── car/        Android Auto screen (NavigationTemplate, buttons, status card)
├── phone/      Phone app: MainActivity and Compose screens in phone/ui/
├── player/     ExoPlayer engine, MediaSession service, ChunkedDataSource, car layout
├── queue/      Queue model and persistence (queue.json)
└── youtube/    NewPipeExtractor wrapper, search, SponsorBlock
```

`player/PlayerEngine.kt` is the heart of the app: it owns the single ExoPlayer, the output surface, and all the recovery logic. The README's [How it works](README.md#how-it-works) section explains why things are done the way they are.

## Testing your change

Please describe in the pull request how you tested it. In rough order of value:

1. **A real car** — the only way to be sure about Android Auto behavior.
2. **The Desktop Head Unit** with a real phone — covers almost all car-side UI and playback.
3. **The phone app on a real device.**
4. **The Android emulator** — fine for phone UI and network-loss tests (`adb shell svc wifi disable; adb shell svc data disable`), but video frames usually don't render there, so don't rely on it for playback.

Things that are easy to break and worth checking when you touch `player/`:

- Seeking (±10 s / +30 s, repeatedly) resumes within a second or two.
- Turning the network off for a minute and back on doesn't end in an error.
- Switching between full screen and the split dashboard in the car keeps the video inside the visible area.

## Code style

- Kotlin official code style (`kotlin.code.style=official`).
- Match the surrounding code: naming, structure, comment density.
- Comments explain **why**, not what. Many existing comments are in Japanese; new comments in English are welcome, and translating existing ones is a fine contribution too.
- Keep dependencies small. Every new library needs a reason.

## Translations

The UI is currently Japanese only, and strings are written directly in the Compose code. The first step toward translations is moving them into `res/values/strings.xml` (English as the default) with `res/values-ja/strings.xml` for Japanese. Pull requests that move a screen at a time are welcome — please open an issue first so we don't duplicate work.

## Pull requests

1. Fork and create a branch from `main`.
2. Keep each pull request focused on one change.
3. Make sure `./gradlew :app:assembleDebug` passes (CI runs it on every pull request).
4. Fill in the pull request template, including how you tested.

Never commit signing keys, `local.properties`, or anything from `scripts/release.local.env`.

## License

By contributing, you agree that your contributions are licensed under the [GPL-3.0](LICENSE), the same license as the project.
