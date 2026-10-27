# Core feature port ledger

Source: tsukiyo-zero public snapshot `a99cd603`, preserved under `.tooling/feature-port/tsukiyo`.
Imported GPL notices and protocol attribution remain in source. The updater implementation and
its tests remain deleted. No account requests or writes were executed during this port.

## Imported surfaces

- Account credentials, QR and optional phone login protocols, profile, recent history,
  favorites, reservation and explicit progress/favorite actions.
- Discovery filters/categories, recommendations and startup snapshots, drama feeds,
  ranking browser state, related works and episode summaries.
- Search suggestions and explicit all-category website search.
- Public comments, timed comments/danmaku and bounded comment transport.
- Rendition ladders, coded track dimensions, bounded streaming reads and codec metadata.
- Conservative, pure `SeasonContinuation.next(current, related)` selection for released higher
  seasons with explicit Chinese/Arabic numbering and matching normalized series titles.

## Deliberate adaptations

- Playback retains the repaired signed `multi_video_model/preload/v1` endpoint and required
  `video_platform=1024` / `need_all_video_definition` payload. It does not adopt the source
  fork's unsigned player endpoint or per-repository replacement device IDs.
- Both legacy and inline rendition parsing require the requested episode key. An unrelated
  first response entry is never accepted as a substitute.
- `DeviceIdentity`, `VendorConstants.device` and persisted randomized anonymous identity
  remain intact. Account-specific signer maps are isolated from that global identity.
  The legacy lambda-based signer constructor remains supported for pinned oracle tests.
- `search()` retains genre-filtered `/api/search/result/` fallback, backend stickiness,
  app-search rejection backoff, app cursor expiry and bounded gateway retry. New `searchAll()`
  explicitly returns all categories through the source fork's website API.
- Existing 45-second prepared-slot `nearEnd()` and sleep timer lookahead remain intact.
  The source fork's optional `canStart()` byte warmup has its own 30-second/stable-buffer gate.
- Bouncy Castle Base64 stays compatible with older supported Android releases; missing
  padding is normalized for QR/protocol responses and impossible input lengths are rejected.
- Existing Probe commands remain intact; public feature availability does not imply a live
  account, production CDN, physical-TV or decoder acceptance result.

## Tests and validation

Imported upstream tests first (except updater tests). Where names overlap, `Upstream*Test`
keeps baseline tests present. The first run failed compilation on absent feature APIs. The
first integrated run executed 277 tests and exposed Base64 padding and warmup gate issues,
plus three intentional fork-contract mismatches. Those tests were adapted to the retained
signed player/genre-filtering contract, rather than restoring the conflicting behavior.
The next complete run passed 277 tests with zero failures.

`FeaturePortIntegrationTest` adds requested-episode isolation for inline models, stable signed
identity across repository instances, account signer isolation and sleep-stop/warmup checks.
Final aggregate counts are recorded by the release integrator after all modules are merged.

After removing semantically duplicate upstream test copies, the complete suite passed 247
tests. `SeasonContinuationTest` was then added first and failed on the absent helper; after
implementation the complete suite passed 258 tests in 45 suites, zero failures/errors/skips.
This helper deliberately leaves unnumbered current seasons, conflicting season metadata and
multiple distinct IDs for the next season to manual selection. It does not perform networking
or change account/local library state. Its source can also identify newer seasons for an
explicit favorite-update check.

Command: `./gradlew.bat --no-daemon :core:test` from `kotlin-tv`, using JDK 17. On this host,
command-local `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=Z:/nonexistent-codex-java-sockets`
avoids a broken JDK Unix-domain loopback path and lets Java use TCP for Gradle pipes.
Logs: `.tooling/feature-port/core-red.log`, `core-green-1.log`, `core-green-2.log`.
Final logs: `core-final.log`, `core-season-red.log`, `core-season-green.log` in the same folder.

Live limitations: login/QR confirmation, user account permissions, recent/partial cloud data
round trips and write confirmation need later user action; adaptive decode still needs actual
Xiaomi S85 Android 14 acceptance. No credentials are included in tests or logs.
