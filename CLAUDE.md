# Buy My Way — project guidance

Shared shopping-list app for Android (Kotlin, Jetpack Compose, Polish UI), the shop-side
companion of Eat My Way. Room is the source of truth on the device; Firebase Realtime
Database holds the shared lists and their photos, one node per item, so a change reaches
another open screen in under a second; FCM, sent by a Google Apps Script, wakes closed apps.
No Google Drive access (Phase 0 verdict, STATE.md decisions 19–21). No server of ours, no
premium, no ads, no analytics.

- Full specification and phase breakdown: [PLAN.md](PLAN.md)
- Progress, decisions, open questions: [STATE.md](STATE.md)
- Release notes: [CHANGELOG.md](CHANGELOG.md) (generated — never hand-edited)
- Local setup and cross-platform rules: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
- Release, Firebase, Apps Script, Play: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)

## Workflow rules

- **One phase per conversation**, started via `/phase N`. Never continue into the next phase
  in the same conversation.
- **STATE.md is the single source of truth** for progress and decisions. Update it before and
  after every phase.
- Any **deviation from PLAN.md must be recorded in STATE.md** before proceeding.
- **Conventional commits**; push after each phase.
- **A push is not finished until CI is green.** `ci.yml` runs on every push to `dev` (and on
  pull requests) — never on `main`. It is the only check that runs somewhere other than the
  machine the work was done on, and from Phase 3 it runs the instrumented suite on an emulator
  on every push — a green local run is evidence, not a substitute. After pushing, find the run
  by the pushed commit's SHA, wait for it (`gh run watch <id> --exit-status`) and report what
  it said. Never call work done while a run is pending or red.
- **A release has one instrumented gate, and it is on `dev`.** The `vX.Y.Z` tag starts
  `deploy.yml`, whose build job repeats lint and unit tests — not the emulator. So only a `dev`
  commit whose `ci.yml` run is green may be merged to `main` and tagged; `/release` checks this
  first. Afterwards the release job's `chore(release)` CHANGELOG commit is pushed back to
  `dev`, which starts one more CI run to wait for.
- **End-of-phase ritual:** update STATE.md → **re-read README.md against what the phase
  changed** → regenerate CHANGELOG.md (`npm run changelog`, once the script exists) → commit →
  push → plain-language summary → go/no-go statement for the next phase.
- **The README is part of the phase, not an afterthought.** Before closing a phase, check the
  status blockquote (which phases are done), any claim about what the app does or refuses to
  do, and the screenshots. Re-take screenshots whenever a screen in them changed.
- **Code and comments in English. All user-facing UI text in Polish.**
- **Minimal dependencies** — every package must justify itself with a STATE.md decision; this
  app holds a Firebase session that can read and write every list its user belongs to.
- **Battery is a requirement.** No foreground service, no persistent background connection, no
  polling. Background work goes through WorkManager with constraints or through FCM. A phase
  that adds background work has to say in STATE.md what wakes the device and how often.
- **Secrets never enter the repository.** The release keystore, its passwords and any Play
  service account live only in GitHub Secrets and `~/.gradle/gradle.properties`.
  `google-services.json`, OAuth client ids and the Apps Script URL are public by design.

## Repository conventions

- **Branches:** work on `dev`, merge to `main`, release by pushing a `vX.Y.Z` tag. A plain push
  to `main` does nothing — only the tag builds and publishes. Ship with the `/release` skill
  (Phase 10).
- **CHANGELOG.md is generated** by git-cliff from Conventional Commit messages. The commit
  message *is* the release note; edit messages, not the file.
- **Two machines (Windows + Linux)** share this checkout. LF line endings are enforced by
  `.gitattributes` (`gradlew.bat` is the CRLF exception); no absolute paths (`local.properties`
  is ignored), no platform-only scripts in the build path; each machine's debug signing SHA-1
  is registered in Firebase. Details in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
- **Gradle is the interface to the project**: `./gradlew lint testDebugUnitTest assembleDebug`
  is what CI runs; `connectedDebugAndroidTest` is the emulator suite; the Firebase rules tests
  live in `firebase/` and run with the emulator suite via npm. Prefer these over ad-hoc
  commands — they behave the same on both machines and in CI.
- **Public repository.** Never commit a credential, a real uid or list id, or a real person's
  email in a fixture.
- **Reference material, not a dependency:** `D:\Work\eat-my-way\android\listonic` (and the other
  folders beside it) are decompiled third-party apps kept for ideas about categories, voice
  input and live-change UX. Nothing is copied from them, and their premium, ads and leaflet
  features are explicitly out of scope.
