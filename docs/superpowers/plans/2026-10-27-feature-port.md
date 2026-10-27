# Feature port implementation plan

> Implement independent domains in parallel following dispatching-parallel-agents, then integrate and review the whole release. User explicitly opts out of questions; no confirmation gate. Do not commit or push.

**Goal:** Deliver an Android 14 focused APK with the useful public fork features while preserving playback/search fixes and user data.
**Architecture:** Existing Kotlin/native View + Media3 application. Separate core protocols, activity/UI integration and optional tools modules.
**Spec:** docs/superpowers/specs/2026-10-27-feature-port.md

## Global constraints

- applicationId com.hongguotv.nativeapp; signing remains existing local delivery key.
- Single active player/decoder. Keep prepared next-episode queue and sleep-stop precedence.
- Keep settings page and app updater removed. Functional controls belong in search/detail/playback/library/tools dialogs.
- No Android 6 acceptance gate; primary runtime acceptance API 34. Keep minSdk only if it causes no regression, otherwise raise deliberately.
- Public-source attribution/GPL notices retained; no install telemetry.
- No external account writes, physical-phone installation, commits or publication in this run.

## Review focus

- Old local records and install signature survive upgrade; absent new fields receive defaults.
- Search fallback pagination does not mix app/web cursor sizes or clear existing results on retry.
- Prepared/manual next, sleep timer, pause/background cancellation survive the feature transplant.
- Account switching invalidates stale requests and never merges local data into a different account.
- Preview/social/automatic resync/third-party sources cannot consume resources or contact optional services while disabled.

## Tasks

- [x] 1. Preserve baseline and import core protocols/models/tests from tsukiyo with repaired search fallback and stable device ID. Run core tests, add regressions for integration differences before fixes.
- [x] 2. Integrate native UI/account/discovery/search/social/decoder modules, preserving package/branding/data and our buffered playback. Port relevant upstream tests, run app unit tests and compile. Keep settings/updater absent.
- [x] 3. Build isolated FeatureTools and preferences: skip/group/backup/remote/statistics/diagnostics/theme/source support. Borrow jycao code where compatible, retain attribution. Supply bounded interfaces to the activity.
- [x] 4. Wire tools to navigation/playback/season/favorites; upgrade build config and resources. Add cross-module regressions and execute full suites/lint.
- [x] 5. Review full change independently, fix important defects, perform API 34 runtime/upgrade checks where feasible, sign release APK, package clean source/checksum/feature matrix and evidence.

## Integration interfaces

Tools own FeaturePreferences (SharedPreferences independent from Library) and FeatureTools(Activity, Library, ContentRepository, openSeries: (Series)->Unit). Activity exposes entry through show(), calls onPlaybackTick(seriesId, positionMs, durationMs): Long? for optional skip target, playbackStarted(seriesId), recordPlayback(deltaMs), and tools remains optional. Library/core changes are owned by their respective integrators; tools must not rewrite those shared files. Source player accepts a validated HTTP(S) URL via an explicit callback and uses the existing ExoPlayer.

## Progress

- Baseline, design and interfaces recorded before implementation. Current branch codex/autoplay-seamless is task-specific and contains prior authorized changes, so reuse it without resetting.
