# Episode transition black-gap repair

> Execute inline with systematic-debugging, test-driven-development and executing-plans.

**Goal:** Preserve prepared media through the episode picker, and keep the last displayed frame while an unprepared episode resolves.

**Evidence:** `showEpisodePanel` pauses before showing its dialog; the playWhenReady listener cancels the whole queued successor on every pause. The fallback `playEpisode` calls stop/clearMediaItems before asynchronous resolution, unnecessarily removing the current media for the entire network wait.

**Constraints:** Android 14 primary; one player/decoder; preserve local records, timer stops, cancellation on background, and explicit playback failures. No settings/updater restoration. Use existing isolated CI clone without changing staged historical CI files. Do not publish or commit unrelated changes.

- [ ] Establish baseline playback-buffer test and API34 runtime.
- [ ] Add runtime regressions for picker retaining a completed successor and unresolved switch retaining old media/frame, paused audio, correct progress and latest selection.
- [ ] Observe both regressions fail before changing production code.
- [ ] Preserve completed queue only for a picker pause, while still cancelling in-flight speculative work; defer current-source disposal until successful replacement.
- [ ] Run unit/lint and all playback instrumentation, including sleep/background/failure/rapid-next cases; review lifecycle and resource ownership.
- [ ] Build versionCode 16 / 0.11.2 with original signature and verify upgrade/public-video smoke; deliver APK with limitations and checksums.

**Review focus:** old source must not be recorded as the new episode during a pending switch; pending read/resolve cancellation must not close the retained source; real user pause still cancels speculation; queued expiry/network loss and sleep stop still override preloading; picker callback cannot resume a replaced episode; latest rapid selection wins.

Ruling: retain the existing SurfaceView and player rather than adding a second decoder or unconditional frame-copy overlay. Removing unnecessary teardown addresses a proven path; device-specific codec blackouts remain a real-TV acceptance condition.

Baseline: playback buffer unit test passed. Original two runtime regressions failed for the intended reasons (current media cleared; completed successor discarded), then passed after the fix including Surface PixelCopy visibility. The first complete playback run passed 16/16 tests with original records retained.

Final review: one Important codec-skip finding confirmed; a paused retained surface was being treated as user pause. Added native regressions before the final fix pass: pending picker, configuration change and incompatible-skip all failed for the expected reason; pending pause/resume already passed.

Final: Ruling: quality/configuration intent reads have the same user-visible unintended pause as the confirmed picker finding, so treat them as Important and fix them through the shared playbackIntent helper. The configuration and picker RED tests cover the shared pending-state intent; existing ready/paused playback tests cover regular intent.
