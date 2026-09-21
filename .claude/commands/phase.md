---
description: Implement one phase of PLAN.md (usage: /phase N)
---

Implement **Phase $ARGUMENTS** of this project. Follow this procedure exactly:

## 1. Read context

- Read CLAUDE.md (workflow rules), STATE.md (current progress and decisions), and the
  "Phase $ARGUMENTS" section of PLAN.md (tasks + acceptance criteria).
- `git fetch origin && git status -sb` — this project is developed from two machines, so make
  sure the branch is not behind before writing anything. Work on `dev`.

## 2. Verify preconditions

- Confirm every predecessor phase is marked `done` in STATE.md. If any is not, STOP and tell
  the user which phase must be completed first. Do not proceed.
- Confirm Phase $ARGUMENTS itself is `pending`. If `done`, ask the user what they want.
- Set Phase $ARGUMENTS to `in-progress` in STATE.md.

## 3. Implement

- Do the tasks listed for this phase in PLAN.md — this phase only, nothing from later phases.
- Any deviation from PLAN.md (different library, changed approach, skipped/added task) must be
  recorded in the STATE.md "Decisions" section **before** proceeding with it.
- Code and comments in English; all user-facing UI text in Polish.
- Minimal dependencies: adding any package not named in PLAN.md requires a STATE.md decision
  entry justifying it.
- Cross-platform: no absolute paths or drive letters, LF line endings (`gradlew.bat` excepted),
  no PowerShell- or bash-only scripts in the build path — the same checkout is used on Windows
  and Linux.
- Battery: anything that runs in the background goes through WorkManager with constraints or
  is triggered by FCM, and STATE.md says what wakes the device and how often.
- Never commit a credential. This is a public repository.

## 4. Verify

- Go through the phase's acceptance criteria one by one and verify each. Report the result of
  every criterion honestly — if one fails or cannot be verified in this environment (no second
  phone, no second Google account, no emulator), say so explicitly and record it in STATE.md.
- Run what CI runs before pushing: `./gradlew lint testDebugUnitTest assembleDebug`, and from
  Phase 2 `./gradlew connectedDebugAndroidTest` on an emulator, and from Phase 5 the rules
  tests in `firebase/`.

## 5. End-of-phase ritual (in this order)

1. Update STATE.md: phase status → `done` (with date), plus any decisions/open questions.
2. Re-read README.md against what the phase changed: the status blockquote, the claims, the
   screenshots.
3. Commit with a **Conventional Commit** message (e.g. `feat: phase $ARGUMENTS — <short name>`).
   The message is the changelog entry — CHANGELOG.md is generated from it, never hand-edited.
4. Regenerate the changelog (`npm run changelog`, once that script exists) and add a
   `chore: update CHANGELOG` commit. Skip if git-cliff is not available yet — the release
   workflow regenerates it anyway.
5. Push to `dev`, then wait for that commit's `ci.yml` run (find it by SHA,
   `gh run watch <id> --exit-status`) and report its result. A red run is fixed and pushed again
   before the summary — a phase is not done while its run is pending or red.
6. Give the user a plain-language summary of what was built and how it was verified.
7. End with an explicit **go / no-go** statement for the next phase.

Releasing is a separate act: `/release` merges to `main` and tags. A phase does not release.

## Hard rule

**Never start the next phase in this conversation** — even if everything went smoothly and the
user seems ready. The next phase starts in a fresh conversation via `/phase N+1`.
