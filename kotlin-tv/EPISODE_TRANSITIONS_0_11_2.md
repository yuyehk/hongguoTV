# 0.11.2 episode transition repair

Version: `0.11.2-android14-transitions`, versionCode `16`. Android 14 is the primary target. The original delivery signing certificate is retained.

## Changes

- Opening the episode picker cancels unfinished speculative network work but retains an already queued successor. Choosing that next episode uses the existing playlist instead of cold-loading it again.
- An unprepared switch pauses the current player and keeps its media, surface and last frame while resolving the new source. It no longer calls `stop()` and `clearMediaItems()` before the network request. Old stream ownership is released after replacement; cancelled claims are explicitly closed.
- Progress is saved for the previous episode before switching. Retained old media cannot be saved as the requested new episode while resolution is pending.
- User playback intent is distinct from the paused retained surface. Picker dismissal, quality/configuration changes, pause/resume and enabled incompatible-codec skipping honor that intent. Background, explicit pause, sleep stop, stale requests and failed speculative reads still cancel their work.
- Slow transitions still show a delayed buffering indicator and retain real failure/retry controls.

No second video decoder or periodic frame-copy overlay was added. Local records are preserved; the settings page and updater are not restored.

## Verification

Regression tests exercise a real API34 Activity and ExoPlayer with deterministic MP4 responses. A PixelCopy check reads the video Surface while the next address is delayed, confirming the retained image stays visible. A separate minified Release check uses the normal AUTO decoder policy and real public video.

The delivery directory contains `verification.json`, `SHA256SUMS`, a complete source archive, the scoped source patch and runtime evidence. The report contains the actual test counts, signing certificate and APK/source hashes.

The Xiaomi TV was not connected during these checks. Network delays and hardware-specific codec transitions require device acceptance; this change does not promise zero latency for an unprepared or unavailable stream.

## Build

Use JDK17 and Android SDK36. Run:

```
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Configure the existing delivery signing variables or local `keystore.properties`; no private signing material is included in the source archive. For unsigned CI builds, use the existing `HONGGUOTV_UNSIGNED_RELEASE=1` workflow.
