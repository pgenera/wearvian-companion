# AGENTS.md — wearvian companion (phone app)

Operational guide for AI coding agents. The watch app (`../watch`, GitHub `pgenera/wearvian`) is the
primary project and its `AGENTS.md` holds the full shared toolchain/signing/release/Play knowledge —
read it too. This file covers companion specifics.

## What this is

The **phone companion** for the wearvian Wear OS Rivian key. It handles enrollment/pairing hand-off
to the watch over the Wear Data Layer, plus a reverse-engineered Rivian **cloud/GraphQL** client
(the watch does BLE; the phone does cloud). Includes a hidden "bring an existing Rivian key onto the
watch" import flow (QR-based).

- **applicationId:** `org.fivesevenfive.wearvian` — **MUST match the watch** (same id AND same signing
  key), or the Wear Data Layer won't deliver messages between them. The *namespace* is
  `org.fivesevenfive.wearvian.companion`.

## Environment, toolchain, versioning, Play, git

Identical to the watch — see `../watch/AGENTS.md`. Key differences for this module:

- **Gradle root is this directory** (`./gradlew`, module `:app`); no `-p` needed.
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/pgenera/android-sdk
  nice -n 19 ./gradlew :app:bundleRelease
  ```
- `minSdk` = **26** (phones), `compileSdk`/`targetSdk` = **36**.
- **versionCode lane:** `2xxx`. Current: 2024 / versionName 0.8.0.
- **Play track:** upload to **`internal`** (watch uses `wear:internal`): `python3
  ../tools/play_upload.py <aab> internal <notes.txt>`.
- Only `debug` (`- debug` versionName suffix, Internal App Sharing) and `release` (R8, upload-signed)
  build types here — no `debugRelease`.

## Companion-specific conventions

- **Don't build or version-bump the companion unless it has real changes.** Watch and companion
  versions are NOT lockstep — a watch release does not imply a companion release.
- The Data Layer message contract (enrollment message paths/payloads between watch and phone) is the
  integration surface; keep both sides in sync when changing it.

## Where the old knowledge went

The watch repo's `docs/agent-memory/` holds a verbatim archive of the Claude Code memory store that
previously carried this project's accumulated knowledge. Treat dated entries as historical.
