# Compatible playback resources implementation plan

> Execute with superpowers:executing-plans; preserve earlier local changes outside this isolated clone.

**Goal:** Play Qi Professor episode 1 using verified compatible upstream resources and publish 0.11.3.

**Architecture:** Keep Media3, single-player prebuffering, local records, signing and device identity. On an exclusively ByteVC2 legacy response, request one alternate from the same supplied HTTPS fallback URL with `force_fids` removed and `codec_type=1`. Parse its own media addresses and keys normally, continue filtering ByteVC2. No speculative decoder/library addition is needed if the media and playback checks pass.

**Tech stack:** Kotlin/OkHttp/Media3, JUnit, Android API34 emulator, existing CI build and verified original-key release publisher.

**Constraints:** No settings/updater restoration; no second player; no change to switching state machine; same package and signing certificate. VersionCode17, versionName0.11.3-android14-transitions. API minimum23 unchanged. User explicitly authorized implementation, testing, APK build and GitHub sync; prior autonomy preference applies.

## Evidence and approach
- Original episode7701564461647154200 supplies five ByteVC2 tiers, including fallback.
- Changing endpoint, platform, use_os_player and device_level did not help (six bounded probes).
- Removing fallback force_fids and requesting codec_type1 returned five H.265 tiers. Removing force_fids with codec0 returned H.265 high tiers but ByteVC2 low tiers. Use codec1 as bounded recovery, subject to MP4/header/decode verification.
- Adding ByteVC2 decoding is a more invasive alternative; unnecessary if compatible resources pass runtime checks.

## Task1: bounded resource recovery
- [x] Write failing regression tests for all-ByteVC2 pinned recovery, compatible/mixed unchanged, unsupported alternate stops, absent pin no duplicate retry, malformed response and cancellation.
- [x] Run RED, implement helper parsing and one alternate request, run GREEN/full core tests (279 passed).
- [x] Run live probe for episodes1/2/85, verify decrypted MP4 codec and decode actual first-episode frames.

## Task2: integration and release
- [x] Bump version and document behavior, keeping player files byte-identical to main.
- [x] Run core/app unit tests (478 passed), lint (no errors), and release guard tests (10 passed). On API34, verified real first/next episode frames and retained records. The full autoplay run passed 19 of 20, with the timing-sensitive automatic-boundary test also reproducing on 0.11.2. That case passed in isolation with the default decoder on 0.11.3, covering all 20 cases across runs.
- [x] Fresh whole-change review, resolve important findings with regression tests. No important findings. Clarified the name of the empty-list test.
- [ ] Commit/push reviewed source, wait for CI success, sign actual CI artifact using original local key, verify package/version/signature/content binding.
- [ ] Smoke-test actual signed release APK; publish verified tag/APK/source/checksums via existing workflow and redownload public assets.
- [ ] Report physical Xiaomi TV untested if not connected.
