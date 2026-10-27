# Multi-account switch and header avatar

User requests one-click switching among saved accounts and the signed-in user's avatar at the upper right. Earlier instructions authorize autonomous decisions and recompiling after tests. Primary target Android14; preserve single-player queue/prefetch, all local records, package/delivery signature and device identity. No settings/updater restoration, no real account login or cloud mutation during development, no commit/push.

Use the existing AES-GCM Android Keystore alias and private cloud_account_secure partition for a bounded vault of ten profiles and credentials. Verified login updates an existing user by biz_user_id or adds one; no silent eviction. Legacy session remains until successful identity verification, then migrates atomically into the vault. Credentials/profile URLs stay outside every portable backup.

The fixed upper-right avatar opens a D-pad-friendly switcher. One click selects a saved account; plus/add uses the existing QR or SMS flow without losing others. Missing/bad avatars use a visible generic placeholder; image requests use the existing credential-free artwork loader and stale-view guards. Catalog navigation scrolls separately, so the avatar stays right-aligned. Browsing/detail headers show it; video remains unobstructed.

Switching cancels old HTTP work, increments account scope, clears cloud history/favorites/reservations/unconfirmed mutations and pending progress, and durably selects the chosen account. Cached profile identifies the selection immediately, but uploads/recommendations/account mutations wait for fresh identity verification. Refreshed identity must match the selected saved user. Delayed old-account results cannot repopulate the new account. Temporary local-only mode retains saved logins; logout/removal explicitly forgets only the named login and leaves native-library-v1 untouched.

Avatar fields verified from the pinned public HGTV AccountParser: reading/user/info/v1 data.avatar_url with expand_avatar_url fallback. Restrict URLs to bounded HTTP(S) without credentials/control chars; no extra profile endpoint, authenticated image headers or new dependency.

Validation: RED/GREEN profile parser, encrypted vault/migration/dedupe/capacity/corruption/isolation, controller switch+stale response+upload cancellation, avatar fallback/layout. Full core/app tests+lint, API34 account switching with synthetic identities and actual Keystore, preserved player integration, signed Release upgrade and playback. Deliver 0.11.1-android14-accounts / code15, APK+source+checksums+report. Real phone authorization/avatar service requires later user login.

User refinement: remove the standalone Account navigation action; the fixed top-right control shows a Login button for guests and the current avatar when signed in. Clicking it opens all account actions (history, refresh, logout, switch/add/manage). Keep switching reachable while a refresh is in flight.
