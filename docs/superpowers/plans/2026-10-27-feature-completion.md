# Remaining features implementation plan

User opts out of questions. Implement independent domains in parallel using dispatching-parallel-agents, root integrates and verifies. No commits or publication.

**Goal:** Complete the feasible remaining public TV features and deliver version 0.11.0.
**Spec:** docs/superpowers/specs/2026-10-27-feature-completion.md
**Architecture:** Existing native Kotlin/Media3 app. Preserve main playback queue and native library; isolate optional source, social and appearance state.

## Global constraints

- Primary runtime API34; same com.hongguotv.nativeapp and delivery certificate.
- Single decoder, current+next main queue, sleep-stop precedence, no hidden background playback.
- No settings page, updater, telemetry, account writes or actual TV installation during development.
- Preserve all prior uncommitted code and verified 0.10.0 baseline.

## Tasks

- [ ] 1. Snapshot baseline; define interfaces and ownership.
- [ ] 2. Social/appearance completion, tests, isolated image import/export.
- [ ] 3. Source discovery/playlists/speed/import/state export, tests.
- [ ] 4. Season supplementation/account pagination audit and implementation, tests.
- [ ] 5. Root integration: incompatible-episode policy, complete backup transport, rate display, exit recommendations, safe cache manager, version bump.
- [ ] 6. Full review and tests, API34 runtime/upgrade, Release/R8 smoke, clean source/checksum/report delivery.

## Interfaces and ownership

- Social/appearance worker owns TvSocial.kt, TvStyle.kt, new AppearanceController/AppearanceTransfer classes and their tests. FeatureTheme expansion is coordinated with root; no MainActivity/FeatureTools edits. AppearanceController(activity,onChanged,chooseImage) offers show(), onImagePicked(uri), apply(rootView), loadAsync(), close(), destroy(), exportState():String, restoreState(raw):Unit.
- Source worker owns ThirdCms/ThirdStore/ThirdSourceScreen/SourcePlaybackActivity/SourceMediaPolicy and their tests; new SourceImportServer, SourceLibraryBackup. Existing onPlaySource(url,title) callback stays; source selection stores a bounded playlist context for SourcePlaybackActivity to claim. SourceLibraryBackup(context).exportState():String / validate(raw) / restoreState(raw) integrates portable extras; no Library/FeatureTools edits.
- Core worker owns core season/repository/account files and AccountController.kt only. Keep public existing interfaces compatible. Mark supplementary search matches unconfirmed to protect automatic continuation. Communicate changes before root wiring.
- Root owns MainActivity, FeaturePreferences, FeatureTools, backup classes, build/manifest/docs, new generic rate/cache/recovery helpers and integration/runtime tests.

## Review focus

- Backups validate all partitions and cannot lose newer local records; old v1 files remain readable.
- Source playlist transitions consume sleep once, do not replay stale queues, and keep a single player.
- Filters/background decoding are bounded and never send/execute comments/configuration scripts.
- Season search does not turn remakes or uncertain names into automatic continuation.
- Optional network/import services close on cancel/background; no credential/device state in default backups.
