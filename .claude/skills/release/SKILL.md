---
name: release
description: Ship Buy My Way — asks whether to do a LOCAL install to a connected phone or emulator, or a REMOTE release (tag a SemVer version on main to trigger the signed APK, the git-cliff CHANGELOG and the GitHub Release). Use when the user wants to release, ship, publish, or install the app on a device.
---

# Release Buy My Way

Drive a release of what is currently on the branch. There are two mutually exclusive targets —
**always ask the user which one first**, then execute only that path.

This project is developed from two machines (Windows and Linux). Prefer `./gradlew`, `git` and
`gh` — they behave identically on both. A release is not a phase: `/phase N` implements one
phase and ends; this skill ships whatever is on the branch now. **Do not start phase work from
inside this skill.**

## Step 1 — Ask the target

Use the **AskUserQuestion** tool with one single-select question, header `"Cel wydania"`, and
exactly these two options:

- **„Lokalnie: zainstaluj na telefonie"** — Build and install on the connected device or
  emulator. Nothing is pushed, nothing is tagged. This is how a change is tried on a real phone.
- **„Wydanie: tag SemVer + GitHub Release"** — Merge `dev` into `main`, tag `vX.Y.Z` and push
  the tag. The tag (not a plain `main` push) triggers `.github/workflows/deploy.yml`: lint and
  unit tests → signed `assembleRelease` → git-cliff CHANGELOG commit-back → a GitHub Release
  carrying `buy-my-way-vX.Y.Z.apk` and its `.sha256`.

Do not proceed until the user picks one (or gives custom direction).

## Step 2a — Local install

1. **Check a device is there and awake.** `adb devices` must list one, and it must not be
   behind a lock screen — Phase 9 lost a whole suite to that: an activity cannot come to the
   front on a locked phone, and every Compose test failed with „No compose hierarchies found".
2. **Run what CI runs**, because a local install has no gate at all:
   ```
   ./gradlew lint testDebugUnitTest assembleDebug
   ```
   Red means stop and fix. Do not install a build whose tests fail.
3. **Install:** `./gradlew installDebug`. The debug build is signed with the debug key, so it
   replaces a debug build and **cannot** replace a release one — Android refuses a signature
   change. To go from a release APK back to debug, uninstall first (`adb uninstall
   dev.gorny.buymyway`), which takes the app's data with it.
4. **Say what was installed:** the `versionName` (`git describe --tags --match "v[0-9]*"`) and
   what the user should look at to see the change.

To try the **release** build locally instead — the only way to see R8 at work — the four
signing values must be in `~/.gradle/gradle.properties` (`buymyway.keystore`,
`buymyway.keystorePassword`, `buymyway.keyAlias`, `buymyway.keyPassword`); then
`./gradlew installRelease`. Without them `assembleRelease` still builds, but the APK is
unsigned and will not install (STATE.md decision 111).

## Step 2b — Release: tag a SemVer version

1. **Run the checks locally first** — `./gradlew lint testDebugUnitTest assembleDebug` — to fail
   fast. `deploy.yml` repeats them, so a red tag would stop there anyway.
2. **Review & commit** on `dev`, with a **Conventional Commit** message. The message *is* the
   release note: `CHANGELOG.md` is generated from it by git-cliff and is never hand-edited.
3. **Sync the other machine's work, then confirm the CI gate.** This is the step that matters
   most in this project, because `ci.yml` never runs on `main` and `deploy.yml` does **not**
   run the emulator suite — the instrumented tests and the Firebase rules tests exist only as
   a `dev` run:
   ```
   git fetch origin
   git status -sb                 # dev must not be behind origin/dev
   git rev-parse origin/dev
   gh run list --workflow ci.yml --branch dev --limit 5 \
     --json databaseId,headSha,status,conclusion
   ```
   The run whose `headSha` is the commit being released must be `completed` / `success`, with
   **all three jobs** green (lint+unit+build, the rules emulator, the instrumented emulator).
   Still running → `gh run watch <id> --exit-status`. Red, or no run for that commit → **STOP**.
4. **Merge into `main`:**
   ```
   git checkout main
   git pull --ff-only origin main
   git merge --no-ff dev
   git push origin main
   ```
   This push alone publishes nothing.
5. **Pick the version.** `git describe --tags --abbrev=0`, then
   `git log <last-tag>..HEAD --oneline`, and bump per [SemVer](https://semver.org): breaking →
   major, any `feat:` → minor, only `fix:`/`docs:`/chores → patch. The first release of the
   finished app is **v1.0.0**. If unsure, confirm with the user.
6. **Tag and push the tag** (annotated) — this is what triggers the release:
   ```
   git tag -a v1.0.0 -m "v1.0.0"
   git push origin v1.0.0
   git checkout dev
   ```
7. **Watch the run and report it:**
   ```
   gh run list --workflow=deploy.yml --limit 3
   gh run watch <run-id> --exit-status
   gh release view v1.0.0
   ```
   Report the Release URL, and **the APK's SHA-256 as the workflow printed it** — that is what
   the acceptance criterion asks somebody to be able to compare.
8. **Bring the CHANGELOG commit back to `dev` and push it**, or the other machine never sees it:
   ```
   git checkout dev
   git fetch origin
   git merge --ff-only origin/main   # a plain merge if dev has moved since
   git push origin dev
   ```
   That push starts a `ci.yml` run on `dev`. Wait for it and report it like any other push.

## After a release — what to check on a phone

The two things only a device can answer (PLAN.md Phase 10 acceptance criteria):

- **The signature holds.** Installing the release APK **over the debug build is refused**
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`: different signature — uninstall first), and over the
  **previous release it succeeds**, keeping the app's data.
- **The update banner.** An older release on the phone shows „Dostępna wersja X — Pobierz" on
  Listy within a day of the new Release existing; „Sprawdź aktualizacje" in Ustawienia →
  „O aplikacji" forces the check without waiting. „Pobierz" downloads and opens the installer —
  the first time, Android asks the user to allow this app to install unknown apps.

## When a release goes wrong

Published tags are protected by a repository ruleset (no deletion, no force-update, **no bypass
actors** — being the repository owner does not exempt you). A failed release is fixed
**forward**, with a new patch version, never by re-pointing the tag that broke:

```
git tag -f v1.0.0 && git push -f origin v1.0.0   # rejected — do not work around it
```

This is deliberate. `CHANGELOG.md`, the GitHub Release and the version inside every installed
APK are all generated *from* the tag (`git describe`), and a moved tag leaves them quietly
disagreeing about what a version contains. So:

1. Commit the fix on `dev`, wait for its `ci.yml` run to be green, merge to `main`.
2. Tag the **next patch** version (`v1.0.1`, never a reused `v1.0.0`) and push that.
3. A rollback for a user is sideloading the previous Release's APK. Same signature, lower
   `versionCode`, so it needs `adb install -r -d` — or, on a phone, uninstalling first. The
   lists are unaffected (they are in RTDB and in Room), **unless** the rollback crosses a Room
   schema version: the on-device migrations only go forward, so that case must be written in
   the release notes as „wymaga ponownej instalacji".

## Notes

- **A release is outward-facing and hard to reverse.** Only run Step 2b after the user has
  explicitly chosen it in Step 1.
- Never push with `--no-verify`, never force-push `main`, never move a published tag.
- **Never commit a credential.** The keystore and its three passwords live only in GitHub
  Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and in
  `~/.gradle/gradle.properties`. `google-services.json`, the OAuth client ids and the Apps
  Script URL are public by design (CLAUDE.md).
- **Losing the keystore ends the update path**: every installed copy would have to be
  uninstalled before the next one could install. It belongs in the owner's password manager,
  not only in GitHub Secrets.
- The database rules are **not** deployed by this workflow. If the release changes
  `firebase/database.rules.json`, publish the rules in the Firebase console **before** anyone
  installs the new build — Phases 6 and 8b both lost an afternoon to that, and
  [docs/DEPLOYMENT.md](../../../docs/DEPLOYMENT.md) says how.
- The workflow, the changelog configuration and the Firebase steps live in
  [.github/workflows/deploy.yml](../../../.github/workflows/deploy.yml),
  [cliff.toml](../../../cliff.toml) and
  [docs/DEPLOYMENT.md](../../../docs/DEPLOYMENT.md).
