# Feature port progress and decisions

Plan: `docs/superpowers/plans/2026-10-27-feature-port.md`

- Baseline source plus uncommitted diff preserved under ignored `.tooling/feature-port` before edits. Existing task branch reused, no reset/commit/push.
- Core: 258 tests green; preserved signed preload, exact episode matching, persisted device identity and repaired website search fallback.
- App: initial complete API34 unit run 118 tests green, followed by two additional authorized-DocumentsProvider round-trip/recovery tests green. Final aggregate runs after runtime fixes.
- Tools: skip-once, bounded sources, cancellation, preference merge and backup recovery verified. SAF mock-provider tests exercise write/readback, two-copy recovery and revoked authorization; actual Xiaomi folder picker remains device acceptance.
- Independent review found source EOF saves and missing audio-focus/noisy handling. EOF regression observed red then green; media attributes/focus/noisy now match official playback. Real source instrumentation tests added.
- Runtime old-release→same-delivery-signed-debug upgrade preserves fixture favorite, second-episode 45-second checkpoint and exact device identity. New home displays retained fixture record and tools. Final release receipt generated separately.
- Anonymous live checks pass: search 10 items, suggestions 8, comic ranking 20, discovery 24, detail 1000 episodes, related works 16 and signed playback with 5 renditions plus MP4 track parsing. These are one bounded live snapshot, not permanent platform guarantees.

## Decisions made under user authorization

- Android 14 is primary; keep existing minSdk23 declaration where it costs no new implementation. No API23 runtime gate.
- Preserve original package/signing/branding and repaired playback/search. Import public capabilities rather than replace the application identity.
- Keep settings page/updater/install telemetry absent. Use function-specific menus/dialogs. Optional preview/danmaku/auto-resync default off; third-party sources start empty.
- Cloud lists stay separate and recent/partial; real login/account synchronization requires user action after delivery. No account writes performed in development.
- Source mode pauses on background return, consistent with official playback. Guard source media controls by foreground state and release the preceding decoder before source launch.
- Tool backup merge preserves existing global options; per-series skip/groups supplement missing records. External folder access is granted explicitly through the OS picker and stored only for the selected directory.
- Private MPV binaries and separate desktop/webOS clients cannot be transplanted from unavailable source. Use inspectable platform/Media3 decoder preference and preserve the documented boundary.
- No physical-TV installation, public release, commits or messages to other devices in this feature-port run. The final APK/source/checksum are delivered locally.

- Runtime exposed HTTP source rejection by legacy vendor-only network policy; allow user HTTP/LAN media while retaining explicit no-cleartext rules for official account domains. Replay from source EOF now seeks to zero before play; both changes have actual instrumentation regressions.

## Final validation

- Core 258/258 and app 120/120 tests pass, zero failures/errors/skips. Lint Debug and Release vital checks pass; zero NewApi issues. R8 release build succeeds.
- API34 instrumentation 18/18 passes including real combined search/result playback and controlled single-player transition/skip/sleep/source cases.
- Release 0.9.3 → release 0.10.0 upgrade retains fixture favorite, episode 2 at 45 seconds and exact device identity; certificate unchanged. Final release also renders first frames for real online episodes 1 and 2, no AndroidRuntime fatal.
- Controlled prepared manual next first frame 285 ms; public-network unprepared release fallback first frame 1287 ms. Preparation/network/codec conditions determine whether a switch has a visible wait; no absolute gapless guarantee.
- No commits, push, publication, physical-phone commands or actual TV installation.

- Final minified Release also plays synthetic HLS and DASH through a bounded loopback fixture, with video screenshots and active media-session proof. Fixture servers/ADB reverse mappings are stopped after checks.
