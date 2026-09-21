# STATE — Buy My Way

Single source of truth for progress and decisions. Update before and after every phase.
Any deviation from [PLAN.md](PLAN.md) must be recorded here before proceeding.

## Phase status

| Phase | Name                                   | Status  | Completed |
|-------|----------------------------------------|---------|-----------|
| 0     | Spike: Drive sharing & Google project  | in-progress |           |
| 1     | Scaffold & CI                          | pending |           |
| 2     | Local data layer & the merge           | pending |           |
| 3     | Lists & items on screen                | pending |           |
| 4     | Google sign-in & Drive persistence     | pending |           |
| 5     | Sharing & real-time                    | pending |           |
| 6     | Photos                                 | pending |           |
| 7     | Voice input                            | pending |           |
| 8     | Import from Eat My Way                 | pending |           |
| 9     | Background, notifications & battery    | pending |           |
| 10    | Release engineering & 1.0              | pending |           |
| 11    | Google Play closed testing             | pending |           |

Statuses: `pending` → `in-progress` → `done` (or `blocked` with a note).

Nothing of the app is built yet. The repository holds the plan, the workflow files and the
repository hygiene (2026-09-18).

**Phase 0 in progress (started 2026-09-21).** The spike tooling is written and builds:
`spike/` (a standalone throwaway Gradle build, see [spike/README.md](spike/README.md) for the
runbook) and `push/` (the Apps Script skeleton). Waiting on the owner for the Google console
setup, two accounts on two phones and the measurements; none of those can be done from the
development machine. The verdict may amend Phases 4 and 5 (decision 1, open question 1).

## Decisions

Newest last. Every deviation from PLAN.md lands here **before** it is acted on.

### 2026-09-18 — Planning

1. **Hybrid storage: Drive holds the data, Firebase Realtime Database carries the signal.**
   Chosen by the owner over „Firebase holds everything" (simplest, but the lists would live in
   a database of ours rather than on the user's Drive) and over „Drive alone" (polling gives
   3–10 s latency in the foreground and nothing at all in the background, which fails the one
   hard requirement). The refinement that makes the hybrid fast: the RTDB signal *is the
   operation* (item id, checked, actor, time), not a „file changed" ping, so the receiving
   screen updates from the op and never waits on a Drive round trip. Ops are pruned after 7
   days; `list.json` on Drive is the folded, durable copy. Consequence: two systems to keep
   consistent, which is why Phase 2 builds the merge as a pure, property-tested function and
   Phase 0 measures both halves before anything depends on them.
2. **Photos live on the list owner's Drive**, in the list's folder, so they inherit the folder's
   sharing. Chosen over thumbnails inlined in RTDB (simplest, but the data would leave Drive)
   and over Firebase Storage (new projects need the Blaze plan — a card on the account, no hard
   spending cap). Depends on the Phase 0 verdict on `drive.file` visibility across users.
3. **Distribution: GitHub Releases first, Google Play closed testing later.** A tag builds a
   signed APK into a GitHub Release (Phase 10) and the app checks that endpoint for updates;
   Phase 11 adds the Play closed track from the same workflow. Both installs must share one
   signing key so they can replace each other.
4. **Repository: `D:\Work\buy-my-way` (a sibling of `eat-my-way`, not inside its `android/`
   reference folder), public GitHub `zyndata/buy-my-way`, MIT.** `google-services.json` is
   committed as configuration; the release keystore, its passwords and any Play service account
   are GitHub Secrets only.
5. **Push in the background is sent by a Google Apps Script**, not by a Cloud Function (Blaze
   plan, card) and not by a relay on gorny.dev (a backend to keep alive). FCM cannot be sent
   phone-to-phone since the legacy server key was retired, so *something* must hold server
   rights; a ~40-line script that Google hosts under the owner's account, bound to the
   Firebase project so it uses its own OAuth token (no service-account key), is the cheapest
   thing that does. Latency 1–3 s, background only. The client contract (`POST {listId, kind}`
   with a Firebase ID token) is the same for a Cloud Function, which is the recorded fallback
   if the script proves flaky.
6. **A viewer cannot check items.** Three roles (owner, editor, viewer) with the viewer
   read-only keeps the RTDB rules to one question per write („is the actor an editor or the
   owner?"). If daily use wants a „can tick but not edit" role, it is a rules change, not a
   model change.
7. **Checked items go to a collapsed „Kupione" section, not away.** The requirement says
   „crossed out and after a moment removed"; removed from the shopping part of the list, kept
   at the bottom so a wrong tap is undone by one tap and „Wyczyść kupione" is the deliberate
   act. Own tick: strike immediately, slide after ~800 ms. Someone else's tick: strike with
   their initial, hold 1.5 s, then slide — that pause is the feedback the requirement asks for.
8. **Sign-in is optional.** A private list works signed out; the sign-in is asked for at the
   first act that needs another person or another device (share, import into a shared list,
   notifications), never at first launch. Same stance as Eat My Way.
9. **The nine Eat My Way departments are the built-in categories, with the same ids**, so an
   import maps one-to-one and a user of both apps meets the same words. Lists may add their
   own categories and reorder all of them; the order belongs to the list.
10. **Import reads Eat My Way's plain-text share; Eat My Way is not changed.** The format is
    stable (`formatShoppingList` in Eat My Way's `src/lib/shopping.ts`), it is what the share
    sheet already hands over, and it keeps the two projects independent. A structured export is
    an open question for Eat My Way, not a task here.
11. **Voice is the Android `SpeechRecognizer` plus a pure parser**, no cloud speech library and
    no Gemini in the MVP. Dictation always lands on a review sheet before anything is added.
12. **Drive REST through OkHttp, not `google-api-client`.** The Java client brings Guava and its
    own HTTP stack for a handful of endpoints; Eat My Way already talks to the same endpoints
    from a browser with `fetch`, and a ~300-line wrapper is easier to audit for where a token
    can go. Coil is the one image library and shares that OkHttp client.
13. **Temporary CI guard.** `ci.yml` skips its Gradle jobs while `gradlew` does not exist, so
    CI is green before Phase 1. Phase 1 removes the guard (task 5). Mirrors Eat My Way's
    decision 7.
14. **Cross-platform checkout (Windows + Linux)** is a hard constraint, as in Eat My Way: LF in
    the repository, `gradlew.bat` CRLF, no absolute paths (`local.properties` ignored), each
    machine's debug signing SHA-1 registered in Firebase rather than a shared debug keystore
    in a public repository.

### 2026-09-21 — Phase 0 (spike)

15. **The spike is a standalone Gradle build in `spike/`**, with its own settings and wrapper
    (Gradle 9.5.0, AGP 9.3.2, Kotlin 2.4.10 from the Compose compiler plugin), so the root stays
    free of `gradlew` and the CI guard (decision 13) holds until Phase 1. It uses the real
    `applicationId dev.gorny.buymyway` so it runs against the same Firebase Android app and
    OAuth clients the app will use. Its dependencies are the ones on PLAN.md's stack list, plus
    `kotlinx-coroutines-play-services` (`Task.await()`). That one is throwaway and **not**
    pre-approved for the app; Phase 4 decides it again if it wants it. The spike initialises
    Firebase from a git-ignored `spike/spike.properties` instead of the `google-services`
    plugin, so no Firebase file enters the repository before Phase 1.
16. **Latency is measured without comparing two phones' clocks.** RTDB: phone A writes a ping,
    phone B's listener writes a pong, and A times the round trip on its monotonic clock
    (one way ≈ RTT/2, sample 0 is a warm-up and is not counted). B also logs a server-offset
    estimate as a cross-check. Push: the phone asks the script to push to its own token and
    times the arrival against its own `sentAt`.
17. **The Apps Script skeleton verifies the Firebase ID token with Auth REST
    `accounts:lookup`** (public Web API key; rejects expired, forged and other-project tokens)
    and sends through FCM v1 with `ScriptApp.getOAuthToken()` and the `firebase.messaging`
    scope. The Phase 0 target token is a script property, never in the repository. Phase 9
    replaces it with the list's members.
18. **The spike's RTDB rules open `/spike` to signed-in users only** (`spike/database.rules.json`).
    Everything else stays locked, and the rules go back to fully locked when the spike ends.

## Open questions

1. **Does `drive.file` let user B open a file user A's copy of the app created and shared?**
   The hybrid and the photo decision both rest on it. Phase 0 answers it by experiment. If no:
   the fallback order is (a) the `drive` scope with the consent screen kept in Testing mode
   for the household, (b) list data in RTDB with Drive only for photos the owner uploads.
2. **Firebase project, OAuth clients, Realtime Database — not created yet.** Phase 0 task 1.
   The public ids go into `gradle.properties` and here; nothing else is written down.
3. **GitHub repository `zyndata/buy-my-way` exists but is private** (checked 2026-09-21),
   while decision 4 says public. The owner has to flip the visibility
   (`gh repo edit zyndata/buy-my-way --visibility public --accept-visibility-change-consequences`)
   and add the rulesets that Eat My Way has (protect `main` and `v*` tags, no bypass actors).
4. **App Link host.** The invite link is planned as `https://buymyway.gorny.dev/i/<token>`
   served by a static page on the existing VM (nginx + a one-line `assetlinks.json`). To be set
   up in Phase 5; until then the `buymyway://` scheme works on its own.
5. **Room tests: Robolectric or the emulator?** Phase 2 decides. The emulator job exists from
   Phase 3 anyway, so instrumented is the likely answer unless it makes the CI loop too slow.
6. **Privacy policy page** — required by Play (Phase 11), sensible before. One static page on
   the existing host, written from `SECURITY.md`.
7. **Does *Testing* mode expire the grants after 7 days?** Google documents that an External
   app in Testing status issues refresh tokens that expire after 7 days. If that reaches the
   Play-services `AuthorizationClient` grant, or the Apps Script's stored authorisation (its
   Cloud project is the same one), "Testing mode forever" (DEPLOYMENT.md) means a re-consent
   every week, or a push sender that stops. Spike step 3.10 checks it on day 8. If it
   bites: publish the consent screen to *In production*. `drive.file` and `drive.appdata` are listed
   as non-sensitive scopes (confirm in the console's scope list), so that should need no
   verification, only the brand info.
