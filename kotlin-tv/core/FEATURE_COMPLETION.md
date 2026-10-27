# Seasons and account coverage completion

## Seasons

`related(id, force=false)` retains the cheap official-cell contract used by favorite update
checks. It never performs title search or detail recursion. Explicit detail UI calls
`relatedComplete(current, page=1, force=false)` and receives cumulative `RelatedWorksPage`
items, `nextPage`, `searchComplete` and a readable status. Each invocation fetches at most
one website search page. A first-page official failure is isolated and remains visible in
the message even when website search succeeds.

Search supplementation uses normalized exact title families after removing explicit
Chinese/Arabic season markers. Related IDs are deduplicated with official rows taking
precedence and sorted by parsed season. Search-only rows have `confirmed=false`; they can
be opened manually but `SeasonContinuation.next` never selects them. A same title is not
proof of a season identity, and even an exhausted title search does not prove every season
has been found. No generic detail lookup or per-result metadata fan-out is introduced.

The explicit list retains up to eight series for ten minutes and one hundred sequential
search pages per series. Empty intermediate pages retain a server `hasMore=true`; reaching
the page bound reports incomplete coverage. A failed page preserves previous rows and
offers the same page for retry. Cancelled calls propagate and do not become completed empty
lists. The search transport has its own repository state, so detail opening does not reset
catalog App search cursors or fallback state.

## Account protocol audit (2026-10-27)

Primary public sources inspected read-only using the GitHub connector:

- [HGTV Account.kt, pinned commit](https://github.com/charlie901208/HGTV/blob/7989bd15a4a8069a85a0c2619649fb94f09e05dd/android/core/src/main/kotlin/com/hgtv/star/core/Account.kt)
- [HGTV ContentRepository.kt, pinned commit](https://github.com/charlie901208/HGTV/blob/7989bd15a4a8069a85a0c2619649fb94f09e05dd/android/core/src/main/kotlin/com/hgtv/star/core/ContentRepository.kt)
- Cached tsukiyo public sources under `.tooling/feature-port/tsukiyo`.

Public `feat/phone-login` branch resolved to the above SHA. Its history request uses
`book_types=2&limit=50`; video shelf uses `bookshelf/video/info/v1` without paging;
reservations request `offset=0&subscribe_offset=0&limit=20` separately for online/offline.
These implementations do not establish a server response cursor to subsequent request
mapping. The reservations source's claim that two batches are the whole list is therefore
not adopted. No new offset arithmetic, guessed cursor field, or fake load-more action is
implemented. The existing source/protocol attribution and GPL notices remain intact.

Compatible list methods remain available. New `historyPage`, `favoritesPage` and
`reservationsPage` return `CloudListPage<T>` plus `CloudCoverage`: request limit, diagnostic
server `has_more`/`hasMore` (when a real boolean or 0/1), `complete=false`,
`paginationSupported=false`, and a readable recent/partial warning. Unknown cursor fields
never trigger requests. A successful or empty fixture never proves whole-account coverage.
History and reservation batches are capped to the verified limits before metadata calls.

The account UI preserves generation guards, cancellation on logout, deduplication and
separate local history. It displays coverage after successful reads and explains limits in
sync status. A favorite addition is confirmed only when observed in a fresh shelf read;
absence from a partial shelf cannot confirm a removal. Such an accepted deletion remains
unconfirmed and blocks automatic repeat submissions until the account can be checked.
No credentials, QR confirmation, SMS, account requests or cloud mutations were executed
during this implementation. Actual account authorization and synchronization still require
the user's later login.

## Playback compatibility classification

`IncompatibleStreamException` is an `IOException` for an identified all-ByteVC2/BVC2
rendition set. It does not classify authentication rejection, network errors, missing
requested episode IDs, empty data, malformed supported siblings or unknown codecs as
skip-worthy incompatibility. Signed preload endpoint/payload, exact episode key selection,
stable device identity and normal website fallback remain intact. Missing fallback data is
now an ordinary readable I/O failure instead of a null-pointer crash.

## Verification

New tests were introduced first; initial compilation failed on absent season, coverage and
typed-compatibility APIs. The first integrated run executed 267 tests and exposed the
missing-fallback null-pointer case in a newly added test. Subsequent results and app UI
verification are recorded by the integrator once the coordinated build completes.

The final coordinated core run passed **270 tests in 48 suites**, zero failures, errors or
skips: `./gradlew.bat --no-daemon :core:test` from `kotlin-tv`, JDK17 with
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=Z:/nonexistent-codex-java-sockets`.
Evidence: `.tooling/feature-complete/core-completion-final.log` and core XML reports.
The preceding retry failed at JAR creation due to another standalone compiler holding the
live core JAR; compilation tasks were coordinated and the final run completed normally.
The all-codec classifier was additionally checked against malformed non-object siblings;
the new assertions failed before tightening the classifier and the full 270-test suite
passed after the change. Evidence: `core-codec-sibling-red.log` and the final full-run log.

Scoped API34 Robolectric verification compiled the current `AccountController`,
`AccountCoverageUiTest` and `AccountFavoriteActionsTest` against a copied core JAR plus
the prior compiled app classes: **6 tests passed**. Evidence:
`.tooling/feature-complete/account-targeted-green-2.log`. This checks the account controller
and visible dialogs; full current-app compilation/runtime remains the integrator's check.

New fixtures cover title-family rejection, duplicate/remake ambiguity, manual-only search
matches, cumulative paging/caching/failure/retry/expiry, independent real App search cursors,
empty intermediate pages, the hundred-page incomplete limit, cancellation, account request
contracts/coverage, and typed codec failure versus malformed/auth/data failures. App tests
cover visible recent/partial messages and unconfirmed removals without repeated writes.
