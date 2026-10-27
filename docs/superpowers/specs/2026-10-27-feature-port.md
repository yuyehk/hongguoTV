# TV feature port design

User authorizes autonomous decisions and porting useful features from the compared forks. Primary device is Xiaomi S85 Mini LED on Android 14; Android 6 is no longer a hard requirement. Keep Kotlin/native Views and the existing package/signing identity. Do not restore an application settings page or update/download/install module. Retain existing local records, persisted randomized device identity, repaired web search fallback, sleep timer and single-player automatic/manual next-episode buffering.

## Architecture and scope

Use the reviewed tsukiyo-zero snapshot a99cd603 as the source for public account, discovery, ranking, search keyboard/suggestions, comments/danmaku, related seasons and adaptive decoder modules. Port functions, preserve this application's identity. Add jycao2 20d3f641 features through a separate tools module: per-series intro/outro, favorites groups, backup/recovery, cross-season continuation, remote shortcuts, local statistics, diagnostics, optional appearance and user-configured sources. No telemetry/download statistics. Optional content preview and danmaku default off. Avoid a second decoder or unbounded prefetch. AV correction starts manual/optional to avoid interrupting playback.

Copying an entire competing application and losing our fixes is unacceptable. Baseline source and diff are saved privately under .tooling/feature-port/baseline before any integration. Existing feature branch is reused; no commit, push, public release or physical-device installation.

Public source availability bounds transplantation: private MPV/native implementations cannot be copied; provide available platform decoder selection and diagnostics, and document source gaps honestly. Android TV system channels are optional and must not replace app navigation. Account synchronization must label recent/partial data and unconfirmed writes; no automatic migration of local favorites into a cloud account. Login requires user action later, not during this run.

## Acceptance

Release APK upgrades com.hongguotv.nativeapp with existing signing key and a higher versionCode. Native feature entries are reachable with a D-pad, no updater/settings page, no credential leak in logs/backup/source archive. Test pure protocol/state logic, app UI with Robolectric/instrumentation where available, lint, release build and signature. Android 14 emulator runtime checks are distinct from Xiaomi physical-TV acceptance; unavailable hardware verification must be reported.

Account QR login, account permissions and actual phone/cloud round trips require user account action and remain pending until then. Third-party sources start empty. Server failures must retain previous successful lists and offer retry without infinite network loops.
