# Remaining feature completion

User authorizes autonomous implementation of all remaining feasible features, Android 14 primary. Preserve current single-player/next-episode optimizations, native-library-v1, device identity, package and delivery signing key. Keep settings page and update module absent. No commit/push/publish or physical-phone/TV installation.

## Deliverables

Version 0.11.0-android14-complete / versionCode 14, signed Release APK, corresponding clean sources, checksums, feature-by-feature validation and explicit unavailable/protocol-limited boundaries. Preserve source baseline privately under .tooling/feature-complete/baseline before editing.

1. Social: keyword filtering, opacity 21-100%, per-series enabled state, existing font/density/speed. Disabled social must stay inactive.
2. Appearance: expanded palettes including readable light styles, phone/local-image background upload and reset. Bounded image parsing, app-private storage, no changes to local records. Functional appearance dialogs, no settings page.
3. Seasons: supplementary same-title season searches, dedupe and ordered list, bounded pagination/caching. Label search matches and avoid automatically playing an ambiguous or unrelated work. Existing confirmed-season continuation remains protected by sleep/foreground state.
4. User sources: measured source preference with manual override; paged aggregate results with dedupe and partial failures; phone QR import; portable source config/favorites/history; actual playlist next/previous/auto continuation and per-series intro/outro in SourcePlaybackActivity. Retain one player and background pause, foreground-only media session and sleep precedence. Restrict to publicly understood JSON/MacCMS interfaces and HTTP(S)/HLS/DASH media; unknown scripts/JAR/encrypted proprietary rules remain unsupported rather than guessed.
5. Playback recovery: explicit play-next action on unsupported episodes and optional bounded automatic skip (maximum five consecutively), only for identified codec incompatibility; network/auth/data failures do not silently skip. Reset budget on actual rendered playback.
6. Experience: current receive rate during buffering, exit confirmation with three recommendations, data-safe manual cache management.
7. Accounts: implement pagination only when supported by verified public request/response contracts. Detect and label recent/partial lists; no inventing offset values or declaring full synchronization from a successful fixture. Real QR/phone synchronization needs the user's later login and is not a development-time side effect.
8. Backup: backward-compatible envelope v2 may contain opt-in source/appearance extras, validate all partitions before applying. Preserve newer existing local progress, provide exported complete backup and external two-copy recovery. Account/device identities never exported. Imported source credentials remain private unless the user explicitly exports the complete package.

## Verification

Test protocol and policy additions first, meaningful UI/provider tests, full core/app tests and lint. API34 emulator exercises preserved queue/skip/sleep and source playlists/import/backup. Signed Release upgrade from 0.10.0 preserves fixture local records and device identity. Verify final Release real playback plus HLS/DASH after R8. Deliver and stop only this run's emulator.
