# GitHub Release design

User authorizes running repository packaging and publishing Releases for yuyehk/hongguoTV. Preserve the 0.11.1 Android 14 app and existing signing certificate.

Build job runs on main pushes/manual dispatch: JDK17/SDK36, Python release-guard tests, core/app unit tests, Android lint, unsigned minified Release APK, verified build report and artifact. Add HONGGUOTV_UNSIGNED_RELEASE=1 as an explicit CI-only signing opt-out; normal local signing is unchanged.

Existing private signing material stays on the local machine. Download the actual CI-built APK, align/sign locally with the delivery key, verify certificate/package/version and perform an API34 upgrade/playback smoke. Upload only the signed APK, manifest and build report to a codex/release-* payload branch. No credentials enter Git or Actions.

Publish job is push-only on that payload branch, contents:write only there. Validate hashes, certificate, source commit and successful build run. Archive source from the exact build commit. Create a draft Release, upload APK/source/checksums/report, verify assets, then publish v0.11.1. Fail on an existing Release or conflicting tag instead of overwriting.

Ruling: prior user opts out of questions; proceed with these defaults. Use a clean clone on codex/github-release because the original checkout contains historical work. No secret upload, settings/updater restoration, production app code changes, force pushes or device actions outside the owned API34 emulator.
