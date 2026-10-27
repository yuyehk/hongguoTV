# GitHub Release Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, with one final read-only reviewer.

**Goal:** Build 0.11.1 in GitHub Actions and publish an original-certificate APK to Releases.
**Architecture:** Unsigned cloud build; protected local signing; verified payload branch publishes a draft then final Release.
**Tech Stack:** GitHub Actions, JDK17/Android SDK36/Gradle, Python stdlib, apksigner, gh.
**Spec:** docs/superpowers/specs/2026-10-27-github-release.md

## Global Constraints
- No application logic/version change; original cert 76e72e760843107a1640ad8544fe46b5772e28fa00b8708ba414d5173ea3c9fa.
- Signing keys stay local; no secrets or build caches committed.
- Exact source SHA/build success/hash/certificate must match before publishing; never overwrite a Release.
- Original checkout untouched; isolated clone branch codex/github-release.

## Review Focus
- Path/env injection in manifest; wrong certificate; forged/failed build; source mismatch; partial release.

### Task 1: Guard + workflow
- [ ] RED tests for manifest traversal/hash/cert rejection and unit XML failures.
- [ ] Implement tools/ci_release.py, tests/test_ci_release.py and unsigned build flag.
- [ ] Add .github/workflows/android-release.yml and build/release documentation.
- [ ] Run helper tests, YAML validation and fresh read-only review; inspect only scoped staged diff.

### Task 2: CI build and publish
- [ ] Commit/push via authorized GitHub tools, observe successful cloud build and test report.
- [ ] Download actual build output; sign locally, verify same cert, API34 upgrade/playback smoke.
- [ ] Upload signed payload and publish via Actions; validate Release assets and downloadable APK signature/hash.
- [ ] Save release receipt, stop owned emulator, report Release and Actions URLs.
