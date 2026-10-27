# Multi-account and Avatar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Securely save/switch accounts with a persistent right-header avatar.
**Architecture:** Existing Keystore encrypted vault + generation-scoped AccountController + small AccountAvatarView. Keep local Library and player independent.
**Tech Stack:** Kotlin, Android SDK36/API34, JDK17, existing OkHttp/Media3/Robolectric.
**Spec:** docs/superpowers/specs/2026-10-27-multi-account-design.md

## Global Constraints
- Android14 primary; preserve native-library-v1/device/package/signature, no settings/updater.
- No real credentials/account writes, no commit/push. Existing dirty checkout reused with private file baseline; linked worktree would omit authorized uncommitted feature work.
- Account credentials and metadata never enter library backup/source artifacts.
- Use only main Gradle owner. No simultaneous Robolectric standalone runner (locks R.jar/resource APK on Windows).

## Review Focus
- Old in-flight response or pending upload must not cross account scope.
- Legacy encrypted session migrates only after validation; corruption/failed save cannot silently overwrite logins.
- Verification failure/mismatched ID cannot enable cloud writes under cached name.
- Header avatar stays reachable/right aligned on narrow TV widths; stale image cannot show another user's face.
- Deleting/logging out one saved account must preserve others and every local record.

### Task1: Profile + encrypted account vault
Files: core CloudAccount.kt; new app AccountStore.kt; new core CloudProfileAvatarTest.kt and app AccountStoreTest.kt.
- [x] Write tests for avatar fields/unsafe URL, legacy migration, two accounts, same-ID refresh, ten-account cap, authenticated corruption, logout isolation.
- [x] Run targeted Gradle to observe RED on absent API, implement minimally, rerun GREEN.
Interfaces: CloudProfile(id,nickname,avatar=""); AccountStore(context,cipher defaultKeystore).load/save/select/remove/deactivate; immutable StoredAccount/AccountVault.

### Task2: Account controller switching
Files: AccountController.kt; new AccountSwitchTest.kt; existing verified-login fixtures adjusted.
- [x] RED switch A→B scopes/cache clearing, stale history/favorites/upload cannot enter B, profile mismatch blocks writes, persistence error and guest/removal.
- [x] Implement vault restore/login-upsert, switcher, add/manage/guest and generation-gated worker persistence, verified-session gate; GREEN and existing account regressions.

### Task3: Avatar + fixed header
Files: new AccountAvatarView.kt; MainActivity.kt; AccountAvatarViewTest.kt + Main header integration.
- [x] RED circular placeholder, bad/missing URL, account name/accessibility, header not horizontally scrolled, guest/multi-state update.
- [x] Reuse artwork binding; add fixed avatar to catalog/detail header and onHistoryChanged; GREEN, no video/player rebuilding.

### Task4: Review, regression and release
- [x] Fresh read-only whole-change reviewer, resolve substantive findings with regression.
- [x] Full tests/lint then API34 synthetic account/Keystore + existing player cases; preserve old records through same-signature upgrade.
- [x] Build code15 signed R8 Release only after tests; real playback smoke. APK/source/checksums/report, no private files; stop only owned emulator.

Ruling: User already opted out of questions, so the documented defaults proceed without another design-approval question. No commits as not requested.

User refinement: remove the standalone Account navigation action; the fixed top-right control shows a Login button for guests and the current avatar when signed in. Clicking it opens all account actions (history, refresh, logout, switch/add/manage). Keep switching reachable while a refresh is in flight.
