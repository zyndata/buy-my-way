# STATE — Buy My Way

Single source of truth for progress and decisions. Update before and after every phase.
Any deviation from [PLAN.md](PLAN.md) must be recorded here before proceeding.

## Phase status

| Phase | Name                                   | Status  | Completed |
|-------|----------------------------------------|---------|-----------|
| 0     | Spike: Drive sharing & Google project  | done    | 2026-09-21 |
| 1     | Scaffold & CI                          | done    | 2026-09-21 |
| 2     | Local data layer & the merge           | done    | 2026-09-21 |
| 3     | Lists & items on screen                | done    | 2026-09-21 |
| 4     | Google sign-in & Drive persistence     | pending |           |
| 5     | Sharing & real-time                    | pending |           |
| 6     | Photos                                 | pending |           |
| 7     | Voice input                            | pending |           |
| 8     | Import from Eat My Way                 | pending |           |
| 9     | Background, notifications & battery    | pending |           |
| 10    | Release engineering & 1.0              | pending |           |
| 11    | Google Play closed testing             | dropped | 2026-09-21 |

Statuses: `pending` → `in-progress` → `done` (or `blocked` with a note, or `dropped` by a
decision).

The repository holds the plan, the workflow files, the repository hygiene (2026-09-18), the
push sender's skeleton (`push/`, deployed), from Phase 1 the Android app's scaffold, from
Phase 2 its local data layer and, from Phase 3, its screens for private lists.

**Phase 0 done (2026-09-21).** Under `drive.file`, a shared file is invisible to the other
member's copy of the app. So shared lists and photos move to Firebase Realtime Database and
the app requests no Drive scope (decisions 19–21). PLAN.md Phases 2, 4, 5 and 6 are
amended. Measured: an RTDB change reached the other phone in ~70–110 ms median one way
(decision 22); a push reached a killed app in 2.7 s, warm median 1.7 s (decision 24). The
Firebase project `buy-my-way-c3949` is on Spark with the rules locked again. The spike is
deleted, the Drive API is off, the spike's Drive folder is removed.

**Phase 1 done (2026-09-21).** A Gradle project (`app`, `dev.gorny.buymyway`) with pinned
versions (decision 26), five placeholder routes with Polish titles, dark theme and dynamic
colour, Firebase on the classpath and initialising from the committed `google-services.json`,
Lint and Kotlin warnings as errors, one unit test, and CI with the "no gradlew yet" guard
removed. Verified on Windows (clean build, API 35 emulator: every route shows its title, back
works, logcat `FirebaseApp initialization successful`) and in CI on Linux. **Not verified:** a
build on the Linux machine itself; CI's Ubuntu runner is the Linux evidence for now, and the
Linux machine's debug SHA-1 is still to be registered (open question 2). Phase 2 is next.

**Phase 2 done (2026-09-21).** The local data layer, with no screen and no network yet. The
domain types and ops (`core/model`) and a pure merge (`core/sync/Merge.kt`) where applying an
op and merging a remote node are the same join (decision 39). Room schema v1 (seven tables,
schema exported to `app/schemas`) and `ListRepository`, where every mutation is merged into
Room and queued in the outbox in one transaction, with flows per screen. The 90-day expiry
and the revive rule (decision 36), the default category order in DataStore, the 662-name
`products-pl.json` with the `Categorizer`, and the RTDB node codec. CI gained the
`instrumented` job (decision 38).
Verified: 38 JVM tests (merge laws over ≥ 1000 random orders per scenario, codec round
trips, the categoriser sample at 100 / 100, see decision 41 for why that overstates it) and
17 instrumented tests (every screen-facing DAO, the repository, the v1 migration walk) on the
Windows machine's API 35 emulator, since no phone was connected. A deliberately broken merge
made 12 tests fail, so the tests do catch a wrong merge. **Not verified:** the Linux machine
(CI's Ubuntu runner stands in again), and a physical phone for the Room tests. The merged
manifest still declares Firebase's network permissions (there since Phase 1). Phase 2 adds
none, and no code opens a connection or signs in. Phase 3 is next.

**Phase 3 done (2026-09-21).** The app can be used. Listy: create, rename, delete with
„Cofnij", drag to reorder (kept per device), „2 / 10" on each card. Lista: items under their
department in the list's walk order. A tap strikes an item through, and 800 ms later it
moves to the collapsed „Kupione". A tap there brings it back. Also „Wyczyść kupione",
„Zaznacz / Odznacz wszystko", drag within a department, and the category order editor, where
a list's own categories can be added, renamed and deleted. The add bar reads „2 kg
ziemniaki, mleko" as two items, offers names from the dictionary and from this device's
history, and shows the proposed department as a chip that can be changed. The edit sheet has
a disabled photo slot. Ustawienia holds the default category order, the theme and the version.
Haptics on tick. A list is swept once a day when it is opened (decision 42). No new
background work, so nothing new wakes the device. Decisions 48–52.
Verified: 54 JVM tests (16 new ones for the add-bar parser, quantities, ordering, the
lingering tick and dictionary suggestions). 21 instrumented tests, with 4 new Compose flows:
add → tick → „Kupione" → back; a category dragged by touch and one moved by the accessibility
action, both persisted; a list deleted and restored with „Cofnij", and one committed when the
snackbar closes; an item deleted from the edit sheet and restored. They passed on the API 35
emulator and on a **physical S10e (Android 12)**. By hand on the emulator: the app was killed
in the background, and the list, the tick and even the half-typed add-bar text came back
(process death). The screenshots in the README were taken on the S10e. **Not verified:**
TalkBack (dropped by the owner, decision 52), and the Linux machine (CI's Ubuntu runner stands
in again). CI time: recorded after the push. Phase 4 is next.

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

19. **Spike verdict: `drive.file` does NOT reach across users (2026-09-21).** Two real
    phones (S10e = A, S23 Ultra = B), two accounts, both on the test-user list, both
    granted exactly `drive.file` + `drive.appdata`, same OAuth project and same APK. A's copy
    created `Buy My Way/spike/list.json` and shared the folder with B as `writer`; the
    permission was confirmed to target B's signed-in account. B's copy then got:
    `files.list` in the folder → **empty**; `files.get` → **404 File not found**;
    `files.update` → **404**; `sharedWithMe` folders → **empty**. A still read its own file
    (version 3, unchanged). So a file one user's copy of the app creates is invisible to
    another user's copy even when shared with them: `drive.file` access is per user and per
    file, and sharing does not grant it. The hybrid's Drive half (decisions 1 and 2), the
    shared `list.json` and the photos on the owner's Drive, **does not work as planned**.
    Plan to be amended before Phase 1. Owner's choice pending (open question 1).
    The grants are taken from both phones' logs (`granted scopes: [… drive.file,
    drive.appdata …]`), not from the console. Afterwards the consent screen's *Data access*
    list turned out not to name the Drive scopes. In Testing mode that list only matters for
    verification, so it does not change the result.
    Working under `drive.file`: sign-in, the consent screen in Testing, per-user
    `appDataFolder` (create/list/read `prefs.json`), creating files in one's own Drive, and
    RTDB writes under the spike rules.

20. **Shared lists and photos live in Firebase Realtime Database (owner's choice,
    2026-09-21, after decision 19).** Chosen over the full `drive` scope (a restricted scope:
    the consent screen in Testing for good, access to the whole of every member's Drive) and
    over a further Google Picker spike (a web page to host, one more step per member per
    list, and it is not known whether a picked folder covers its files). This supersedes
    decisions 1 and 2:
    - **RTDB holds the list itself**: `meta`, `members`, `categories`, and one node per item
      that carries the item's current state. A change is written to that node, together with
      the fields the merge needs (`updatedAt`/`updatedBy`, `checkedAt`/`checkedBy`,
      `deletedAt`). The rules reject a write older than what is stored, so the server
      enforces last-writer-wins per field group, the same rules Phase 2's pure merge applies
      on the device. The separate op log, its 7-day pruning, the Drive snapshot and the
      `SnapshotWorker` are gone: the item node *is* the op, and a device that was offline
      for a month reads the items changed since it last looked (`orderByChild("updatedAt")`).
    - **Photos are stored in RTDB**, under their own node (`/photos/{listId}/{itemId}`), so
      a list read never downloads them. They are downscaled on the device to ≤ 800 px WebP,
      target ≤ 80 kB, capped by the rules. On Spark (1 GB stored, 10 GB/month downloaded)
      that is about ten thousand photos, and a cached photo is not downloaded twice.
      Firebase Storage stays out (Blaze plan, decision 2).
    - **Room is still the source of truth on the device**, and the app still works fully
      signed out and offline. Nothing about the hard requirement changes. RTDB was always
      the fast path, and now it is also the durable one.
    - Consequence to be honest about: the lists no longer live on the user's Drive. They sit
      in the owner's Firebase project, readable by its members through the rules and by the
      project owner in the Firebase console. README, SECURITY.md and CLAUDE.md say so from
      now on.
21. **Drive leaves the app entirely.** With the lists in RTDB, the only Drive use left was
    `prefs.json` in `appDataFolder` (it works, decision 19). A per-user node
    `/users/{uid}/prefs` does the same job without a Drive token. So the app requests **no
    Drive scope at all**: no `AuthorizationClient`, no Drive REST client, and the consent
    screen asks only for the basic sign-in scopes. That removes the most sensitive thing the
    app held, a token with write access to the user's Drive, and it makes open question 7
    moot for the app, because basic scopes can be published to *In production* without
    verification. A "save a copy to Drive" export can return later as a feature. It is under
    *Later* in PLAN.md, not planned. The Drive API stays enabled in the project for the
    spike only and is switched off when Phase 0 closes.

22. **RTDB change latency, phone to phone (2026-09-21): median ~70–110 ms one way, p95
    ≤ 160 ms, far under the 1 s requirement.** Measured as an echo (decision 16): A writes,
    B's listener answers, A times the round trip. 20 counted samples per run after one
    warm-up. Phone A (S10e) has no SIM, so it was on home Wi-Fi in both runs. The plan's
    "both on mobile data" was not possible and is not recorded.

    | Run | B's network | RTT median | RTT p95 | one-way median | one-way p95 | max RTT |
    |---|---|---|---|---|---|---|
    | 1 | mobile data | 135 ms | 310 ms | 67 ms | 155 ms | 802 ms |
    | 2 | Wi-Fi | 214 ms | 227 ms | 107 ms | 113 ms | 363 ms |

    The Wi-Fi run is bimodal (≈110 ms or ≈215 ms RTT). That is most likely the phones' Wi-Fi
    power saving; either way it is well inside budget. The warm-up of the first run (409 ms)
    is the connection cost a list pays once when it opens. B's server-offset cross-check
    agrees in scale (−13…+228 ms, the negatives being clock-offset error). The Drive
    `files.get` timing was not measured: Drive no longer carries data (decision 21).

23. **Removing the app in Google Account → Connections ends the Firebase session.** Found
    by accident (2026-09-21). Removing "Buy My Way" at myaccount.google.com/connections, to
    redo the Apps Script consent, also invalidated the Firebase session of the phone signed
    in with that account. Its next `getIdToken(true)` threw
    `FirebaseAuthInvalidUserException` ("the user's credential is no longer valid"). The
    Apps Script and the Android app share one OAuth project, so they show up as one
    connection. Phase 4 must treat that exception as "sign in again" (with a sentence),
    never as a crash or a silent sign-out, and never lose local data. Related: a partial
    consent (not every checkbox ticked on Google's granular consent screen) is remembered, so
    the script ran without `script.external_request` and then without `firebase.messaging`.
    DEPLOYMENT.md (Phase 9) must say "tick every box".

24. **Push through the Apps Script works end to end: ~1.7 s median, under 2.8 s to a killed
    app (2026-09-21).** Phone B (S23 Ultra, mobile data) asked the script to push to its own
    token, so one clock measured the whole path (decision 16): tap → HTTPS POST → the script
    verifies the Firebase ID token (`accounts:lookup`, 100–150 ms) → FCM v1 with the script's
    own OAuth token → data message received.

    | Case | n | end-to-end | script's own time |
    |---|---|---|---|
    | warm, app in foreground | 6 | median 1.67 s, min 1.27 s, max 1.99 s | 130–210 ms |
    | app process killed (`am kill`), push sent from phone A | 1 | 2.73 s (A's and B's clocks, NTP) | 608 ms |

    The killed app was started by the high-priority data message and posted its notification
    with no activity running. **Not measured:** a true cold start of the script after 30+
    minutes idle, and Doze. Both belong to Phase 9's acceptance ("notification within 5 s,
    p95"), which measures them on the finished sender. The script also rejects a request with
    no token or a forged one (`{"ok":false,"error":"unauthenticated"}`, checked with `curl`).
    Deployment lessons for DEPLOYMENT.md: paste the manifest *before* the first deployment;
    a deployment keeps the manifest of its version, so every manifest change needs
    **Manage deployments → edit → New version**; tick every box on the consent screen.

### 2026-09-21 — After Phase 0

25. **No Google Play. Phase 11 is dropped, and the consent screen goes *In production*
    (owner, 2026-09-21).** Decision 3 is superseded: distribution is GitHub Releases only,
    and the in-app update check reads `releases/latest`. What this changes:
    - Phase 11 and everything Play-specific are gone from PLAN.md: the service account, Play
      App Signing, the data-safety form, the installer check.
    - The OAuth consent screen is **published to *In production*** so that no grant expires
      after 7 days in Testing (open question 7). The app now asks only for basic sign-in
      scopes (decision 21), and the Apps Script's two scopes are authorised by the owner
      alone, so this needs no verification. At worst it shows the "unverified app" notice,
      and 100 users is far above a household. **Published 2026-09-21.** Afterwards the
      script still answered correctly (`GET` → `{"ok":true}`; a forged token →
      `unauthenticated`, which means `UrlFetchApp` is still authorised).
    - The privacy page is still shown: Google links it on the sign-in sheet and in Google
      Account → Connections. Nothing enforces its content any more, but it should tell the
      truth. So Eat My Way's `privacy.html` gets a Buy My Way section (open question 6),
      done in the Eat My Way repository.

### 2026-09-21 — Phase 1 (scaffold)

26. **Toolchain pinned at Phase 1 (latest stable on 2026-09-21), with two deviations from
    PLAN.md's ranges.** Gradle 9.7.1, **AGP 9.4.1** (the plan says 8.x; 9.x is the current
    stable line and the spike already built with 9.3.2), Kotlin 2.4.20 through **AGP 9's
    built-in Kotlin** (no `org.jetbrains.kotlin.android` plugin, only the Compose compiler
    plugin), **compileSdk / targetSdk 37** (the plan wrote 36 "at the time of writing"; 37
    is current), minSdk 26, bytecode target 17. Compose BoM 2026.09.00, Navigation Compose
    2.10.1, Activity Compose 1.13.0, Firebase BoM 34.19.0, JUnit 4.13.2. CI builds on JDK 21;
    locally Gradle runs on whatever JDK 21+ is on `JAVA_HOME` (the Windows machine has 24 and
    Studio's JBR 25). No Gradle toolchain resolution: auto-provisioning needs the foojay
    plugin, one more thing to trust for nothing the bytecode target doesn't already give.
27. **Two build pieces not named on the stack list.** `com.google.gms.google-services` (build
    plugin only, nothing in the APK beyond the generated resource values): it turns the
    committed `google-services.json` into the values `FirebaseApp` initialises from, which
    PLAN.md's "google-services.json committed" presupposes. `androidx.activity:activity-compose`:
    `setContent` and predictive back live there; it is the entry point of any Compose app.
    Navigation uses plain string routes (`list/{listId}`), exactly as PLAN.md names them, so
    the kotlinx.serialization plugin waits for Phase 2, which needs it anyway.
28. **`versionCode` comes from `git describe`, not from a timestamp.** A tag `vX.Y.Z` gives
    `X·1 000 000 + Y·10 000 + Z·100`; a commit *n* commits after that tag adds `min(n, 99)`.
    So a dev build installed over a release is newer, and the next release is newer again,
    and the same commit always builds the same number, which a timestamp would not (it also
    reconfigures the build on every run). With no tag yet: `versionName` `0.0.0-dev`,
    `versionCode` = `min(commit count, 99)`. `versionName` is `git describe --tags
    --match v* --dirty` without the `v`.
29. **Lint's "a newer version exists" checks are off** (`GradleDependency`,
    `NewerVersionAvailable`, `AndroidGradlePluginVersion`). With `warningsAsErrors`, they
    would turn CI red whenever Google publishes something, with no change on our side.
    Dependabot (monthly) owns version freshness. Every other check stays an error.
30. **Android backup and device transfer are off** (`allowBackup="false"`,
    `fullBackupContent="false"`, and `dataExtractionRules` that exclude every domain for
    Android 12+, where `allowBackup` alone no longer stops device-to-device transfer). A
    restored copy would carry a Firebase session and a Room database to another phone
    without the user signing in there. The data that matters is safe anyway: a shared list
    is in RTDB, and a private list will be exportable once Settings has „Kopia listy". Revisit if
    daily use misses restoring private lists on a new phone.
31. **Placeholder navigation is a button per route and a text „Wstecz" in the top bar**, not
    an icon: Material 3 no longer carries the icon set, and `material-icons` would be a
    dependency for one arrow. Phase 3 decides the icons with the real screens.
32. **An AGP bump waits for a stable Android Studio that can sync it.** Found after Phase 1:
    Studio 2026.1.3 refused AGP 9.4.1 ("Latest supported version is AGP 9.3.0"), because
    Studio checks major.minor. The owner updated Studio instead of holding AGP back, so AGP
    stays at 9.4.1 (decision 26). From now on, a Dependabot PR that raises AGP's minor
    version is merged only after the stable Studio has been updated on both machines.

### 2026-09-21 — Before Phase 2

33. **Room tests are instrumented, not Robolectric (owner, answers open question 5).**
    Locally, `connectedDebugAndroidTest` runs on a physical phone when one is connected
    over `adb`, and on an emulator otherwise. Gradle already picks whatever device `adb`
    sees, so this needs no extra code. CI always uses the emulator. Consequence for PLAN.md:
    Room's DAO tests arrive in Phase 2, so the `instrumented` CI job has to exist by then
    too (PLAN.md adds it in Phase 3). Otherwise CI would not run them, and CI is the only
    evidence that counts. Phase 2 records that move when it starts.
34. **The repository is public, as decision 4 says (owner, answers open question 3).** It
    was made public on 2026-09-21 after a scan of the whole history for credentials. The only
    key found was the Android API key in `google-services.json`, which is public by design.
    The owner's worry is that someone builds the app and uses up the free Firebase quota.
    A private repository would not prevent that: every APK on the public Releases page
    carries the same `google-services.json` values. What does limit it:
    - **Spark has no billing.** The worst case is a quota exhausted for the month (1 GB
      stored, 10 GB downloaded), never an invoice.
    - **Every write needs a Firebase sign-in, and the only provider is Google.** A rebuild
      signed with a different key fails Google sign-in, because the Android OAuth client is
      bound to the package and to our SHA-1s.
    - **The RTDB rules (Phase 4)** limit each write to the lists the signed-in user belongs
      to, and cap sizes (photos ≤ 80 kB).
    - **Still to do:** restrict the Android API key to the package and SHA-1s (open
      question 2). App Check is not available: its Android provider, Play Integrity, assumes
      Play distribution, which decision 25 dropped. A household allow-list in the rules is
      open question 9.
35. **The invite link lives on `eatmyway.gorny.dev` (owner, answers open question 4).** The
    link is `https://eatmyway.gorny.dev/bmw/i/<token>`. The existing nginx serves a static
    page there (it says where to get the app) and `/.well-known/assetlinks.json`, which
    names `dev.gorny.buymyway` and the release SHA-256, so that Android opens the app
    directly. `buymyway://` stays as the fallback. Set up in Phase 5.
36. **Bought items expire after 90 days, and re-adding revives them (owner, 2026-09-21).**
    The goal is that RTDB cannot grow without bound. Photos are what fill it: ≤ 80 kB each,
    so ~12,000 fill Spark's 1 GB, while all text together is a few MB.
    - **Expiry:** an item whose `checkedAt` is more than 90 days old gets `deletedAt` (an
      ordinary delete, so the merge needs nothing new). The existing 30-day tombstone rule
      then removes the node and its photo, about 4 months after the purchase in total. The
      same rule applies to private lists in Room.
    - **Revive:** adding an item whose normalised name matches an item in „Kupione" unchecks
      that item instead of creating a new node. Photo, category and quantity are kept.
    - **Autocomplete history** is a local Room table of names only, so it survives expiry.
    - **Who cleans:** any editor's device, when a list is opened, at most once a day. No
      background work, so nothing new wakes the device. The writes are idempotent, so two
      devices cleaning at once agree. An Apps Script janitor on a weekly trigger would also
      clean lists nobody opens any more. It is not planned, because it would give the script
      admin rights over the whole database, and today it can only send pushes.
    - **Other leftovers:** expired invites are removed by the owner's device; the push sender
      deletes a token when FCM answers `UNREGISTERED`; deleting a list removes its items,
      photos and invites at once.
    - Where it lands: the rule and revive in Phase 2 (Room, pure and tested), the add-bar
      behaviour in Phase 3, the RTDB side in Phases 5–6, token cleanup in Phase 9.

### 2026-09-21 — Phase 2 (local data layer & the merge)

37. **Phase 2 libraries, all on PLAN.md's stack list, pinned at their latest stable.** Room
    2.8.5 (`room-runtime`, `room-ktx`, `room-compiler` through KSP, and `room-testing` for
    the migration test), plus Room's own Gradle plugin `androidx.room`, which exports the
    schema and hands it to the instrumented tests; KSP 2.3.12; kotlinx.serialization 1.11.0
    (the compiler plugin at the Kotlin version, and `-json`); DataStore preferences 1.2.1;
    `kotlinx-coroutines-test` 1.11.0; AndroidX Test (runner 1.7.0, `ext:junit` 1.3.0).
    `kotlinx-coroutines-android` 1.11.0 is declared explicitly. Room and Firebase bring 1.9.0
    on their own, and the 1.11 test library then fails on the device with a
    `NoSuchMethodError`. The app uses coroutines directly anyway, so it names the version.
38. **The `instrumented` CI job arrives in Phase 2, not Phase 3** (the consequence decision 33
    announced). The Room tests are instrumented, and CI is the only evidence that counts, so
    `ci.yml` gets `connectedDebugAndroidTest` on an emulator now. It uses
    `reactivecircus/android-emulator-runner` (the usual way to run an emulator on a GitHub
    runner with KVM), API 35 `google_apis` x86_64, the same API level as the Windows AVD, and
    a cached AVD snapshot. Phase 3 inherits the job and adds its UI tests to it.
39. **The merge is a join of item states, and four details of PLAN.md's model are settled
    here.** An op becomes the partial node it would write, and `apply(op, state)` is
    `mergeRemote(state, thatNode)`. So applying ops and merging remote nodes are one
    function, and it is commutative, associative and idempotent by construction (a
    semilattice join). Content is last-writer-wins by `updatedAt` and the checked state by
    `checkedAt`, each on its own. Equal timestamps are broken by actor and then by content,
    so the order is total and every device picks the same winner.
    - **A tombstone is final.** PLAN.md says a delete is never undone by an *older* put; here
      a newer one does not undo it either. Re-adding a name creates a new item, or revives the
      checked one (decision 36). That keeps the Phase 5 rule simple: nothing but the cleanup
      writes to a deleted node.
    - **„Wyczyść kupione" is a list-level mark, `clearedAt` in the list meta** (the later mark
      wins). An item checked before it counts as deleted. Rewriting each item at the moment of
      clearing would not converge: a device that learns of an older check after the clear
      would keep the item. With a mark in the meta it converges, and an item someone unchecks
      after the clear comes back, which is what that person meant. The RTDB `meta` node gains
      `clearedAt`.
    - **Deleting a category leaves a tombstone that remembers `moveItemsTo`.** An item whose
      category is gone is shown under that target (followed to a live category), or under
      „Inne". Items are not rewritten, for the same convergence reason.
    - **One op more than PLAN.md lists: `list.delete`.** Phase 3's „Usuń" needs it, and the
      outbox format is fixed now. The meta keeps `deletedAt`. `Category` gains
      `updatedAt`/`updatedBy`/`deletedAt`/`moveItemsTo`, the meta gains `updatedBy`, because
      the merge needs them. A node whose check or delete is known but whose content is not yet
      (possible only with out-of-order delivery) is kept with `updatedAt = 0` and never shown.
40. **`products-pl.json` is written by hand, with Eat My Way's departments as the reference.**
    About 600 everyday shop names, grouped by category id. Where Eat My Way files something
    unexpectedly (oil, honey and peanut butter under „Przyprawy i dodatki", coconut milk and
    nuts under „Sypkie"), the dictionary does the same, so a typed item and an imported one land
    in the same place (decision 9). Two exceptions look like slips in Eat My Way and are not
    copied: ketchup and potato starch (both „Warzywa" there) are filed under „Przyprawy" and
    „Sypkie". Nothing is copied from the decompiled apps. The
    `Categorizer` folds Polish letters (ą→a, so typing without diacritics works), drops numbers
    and units, and matches words by common prefix with a short inflection allowance
    („ziemniaków" ↔ „ziemniaki"). The longest matching entry wins („mleko kokosowe" beats
    „mleko"). A tie between two departments gives „Inne": a miss is allowed, a wrong
    department is not. Per-user corrections are passed in and win over the dictionary.
41. **The Categorizer's 100-item sample is not a literal week's export, because none exists in
    this repository or beside it.** It is built from the closest real thing: the 65 distinct
    ingredients across the owner's 27 real recipes (read from an Eat My Way backup outside the
    repository; only the ingredient names are copied, the backup holds personal data and stays
    out), under the departments Eat My Way itself gives them, plus 35 names typed the way a
    person types into a shopping list (inflected, without diacritics, with a count). It lives
    in `app/src/test/resources/categorizer-sample.tsv`. A real export can replace it later
    without code changes. **Result: 100 / 100, no miss.** That overstates it: the dictionary
    was written after seeing the 65 recipe names, so they were bound to be covered. The 35
    typed names are the fairer part of the test. Daily use will show the real miss rate, and a
    miss costs one tap: it lands in „Inne", never in a wrong department.
42. **Two small additions to schema v1, so Phase 3 does not start with a migration.** A
    `name_history` table (decision 36's autocomplete history: names only, with last use and a
    count), and `sweptAt` in `list_sync`, so the 90-day expiry and the 30-day tombstone purge
    run at most once a day per list. Nothing runs in the background: the sweep is called when
    a list is opened (Phase 3), so nothing new wakes the device.

### 2026-09-21 — Before Phase 3

43. **„Cofnij" after a delete holds the delete back (owner, 2026-09-21).** A tombstone stays
    final (decision 39). Deleting a list or an item hides it on screen at once, and the
    `list.delete` / `item.delete` op is committed only when the undo snackbar closes without
    „Cofnij". If the app dies while the snackbar is open, the delete is lost and the thing is
    still there: the safe side. Chosen over an „undelete" op, which the merge and the Phase 5
    rules would both have to allow.
44. **The order of lists on the home screen is personal and stored per device (owner,
    2026-09-21).** Two members may sort a shared list differently, so the order is not part
    of the list. Phase 3 keeps it in DataStore (list ids in order; lists not named in it come
    after, oldest first). Phase 4 moves it to `/users/{uid}/prefs` with the other per-user
    settings.
45. **Drag-to-reorder is written in-house, no library (owner, 2026-09-21).** It covers the
    lists, the items within a category and the category order editor. It is about 150 lines on
    `detectDragGesturesAfterLongPress` and `LazyListState`, chosen over
    `sh.calvin.reorderable` because of the minimal-dependencies rule.
46. **Icons are vector drawables copied from Material Symbols (Apache-2.0), no icon
    library (owner, 2026-09-21).** It is only the few the screens need (add, mic, share, drag
    handle, delete, more, back…), in `res/drawable`, with the licence noted in the README.
    `material-icons-extended` is large and would be mostly unused. This settles what decision
    31 put off.
47. **TalkBack and the README screenshots are done by the owner on a real phone (owner,
    2026-09-21).** Phase 3 asks for the phone to be connected over `adb` when it reaches those
    steps: the TalkBack check (recorded here as the owner reports it) and the screenshots
    (taken with `adb exec-out screencap`). The Compose UI tests still run on the emulator in
    CI. Also for Phase 3: PLAN.md's „emulator API 34" is already API 35 (decision 38), so
    Phase 3 only adds its tests to the existing job. The add bar's quantity/unit parser is
    written as the pure parser Phase 7's dictation will reuse.

### 2026-09-21 — Phase 3 (lists & items on screen)

48. **Phase 3 libraries, all on PLAN.md's stack list.** `lifecycle-viewmodel-compose` and
    `lifecycle-runtime-compose` 2.11.0 ("Lifecycle + ViewModel"; Navigation already brings
    2.11.0, so they are named, not added), and Compose's `ui-test-junit4` (androidTest) with
    `ui-test-manifest` (debug only) from the Compose BoM ("Compose UI test"). `buildConfig` is
    switched on so that Ustawienia can show `BuildConfig.VERSION_NAME`. No other dependency.
    ViewModels are built with the `viewModel { … }` initializer from the `AppContainer`, still
    no DI framework.
49. **Drag starts on a handle, not after a long press (refines decision 45).** On a row, a long
    press opens the edit sheet, and on a list card it opens the menu (PLAN.md *Screens*). So
    the drag needs its own place, the ⋮⋮ handle at the end of the row, and it starts at
    once on it with `detectVerticalDragGestures`. The rest is as decided: in-house, on
    `LazyListState`. Every draggable row also has TalkBack actions „Przesuń wyżej" / „Przesuń
    niżej", so reordering does not need a drag at all. A row is opened for editing by a long
    press only. PLAN.md's "long-press **or swipe**" is kept to the first. A swipe on a list
    row is too easily mistaken for a scroll in a shop.
50. **What Phase 3 leaves to later phases, and one small addition.** The card menu has
    „Zmień nazwę" and „Usuń". „Udostępnij" and „Uprawnienia" arrive with Phase 5, and the mic
    button with Phase 7, so no control is shown that does nothing. The category order editor
    (a screen, `list/{listId}/categories`) can also add, rename and delete a list's *own*
    categories. The repository has had those ops since Phase 2, decision 9 promises them, and
    without a screen they would be unreachable. The nine departments can only be reordered.
51. **„Strike through, then slide" is a view over Room, not a delayed write.** A tap commits
    `item.check` at once, so the tick survives the app dying in the next 800 ms. The screen
    keeps the item in its category, struck through, for 800 ms (`ListViews.detail`'s
    `lingering`), then lets it move to „Kupione". Undo from „Kupione" is an ordinary uncheck.
    The deletes held back by „Cofnij" (decision 43) are committed when the snackbar closes
    without „Cofnij": by timeout, by the next delete replacing it, or by leaving the screen.
    Only the app dying loses one, which is the safe side, as decided.
52. **No manual TalkBack check (owner, 2026-09-21).** Phase 3's acceptance criterion
    "TalkBack reads the list sensibly (manual check, recorded)" is dropped. The owner does
    not use TalkBack and does not want to verify it. What task 7 built stays, because it costs
    nothing and Lint requires the descriptions: content descriptions, the „kupione / do
    kupienia" state on every row, headings, and the move actions of decision 49. Nothing
    checks by hand that they read well, and no later phase will. Asked at the same time: a
    mic that types the spoken name into the add field, as in Listonic. The owner chose to keep
    Phase 7 as planned (dictation, the parser, the review sheet), so Phase 3 has no mic.

## Open questions

1. ~~Where do shared lists live, now that `drive.file` cannot cross users?~~ Answered by
   decisions 19–21: in RTDB, and Drive leaves the app.
2. **Firebase project created 2026-09-21.** Public ids so far:
   - project id `buy-my-way-c3949` (the plain `buy-my-way` was taken), project number
     `270774397521`
   - Web client id (`serverClientId`)
     `270774397521-4l890vstjdbmsvvhkjjho056io7ue221.apps.googleusercontent.com`
   - Android app id `1:270774397521:android:7f3a8715b5ec1f9a386670` (`dev.gorny.buymyway`)
   - Android OAuth client (Windows debug SHA-1 `5A:DB:9A:F8:…:52:8D`)
     `270774397521-0msiurf1sb48iqp7nb3l2jp6mh48oiuo.apps.googleusercontent.com`
   - RTDB `https://buy-my-way-c3949-default-rtdb.europe-west1.firebasedatabase.app`
   - OAuth consent screen: External, Testing, Eat My Way branding (open question 6), scopes
     `drive.file` and `drive.appdata`.

   - Google sign-in on, verified on two phones; the Drive API was enabled for the spike and
     is off again; RTDB rules locked (`.read`/`.write` false) until Phase 4 writes the real
     ones; plan **Spark** (checked 2026-09-21).
   - Apps Script push sender: project "Buy My Way push", Cloud project `270774397521`,
     web-app URL
     `https://script.google.com/macros/s/AKfycbxALVMNZHX5kQxw1OZyeYZ8IdELlmAFR1RXHE_1DXklCIhKFxxBNHcK_Zf7fy2MLjyCyA/exec`.

   - `app/google-services.json` committed in Phase 1 (2026-09-21); it holds the Windows
     debug SHA-1's Android client, the Web client and the Android API key.

   Still to do: register the **Linux machine's debug SHA-1** at the first build there (not
   done in Phase 1: the phase ran on Windows only), then commit the refreshed
   `google-services.json`. Also unchecked: whether the Android API key is restricted in
   Google Cloud to the package and SHA-1s as PLAN.md's *Security* section asks. Nothing but
   public ids is written down.
3. ~~Should `zyndata/buy-my-way` be made public?~~ Yes, done 2026-09-21 (decision 34).
   Still open: the rulesets that Eat My Way has (protect `main` and `v*` tags, no bypass
   actors).
4. ~~App Link host?~~ `eatmyway.gorny.dev/bmw/i/<token>` (decision 35).
5. ~~Room tests: Robolectric or the emulator?~~ Instrumented: a phone if one is connected,
   an emulator otherwise and in CI (decision 33).
6. **Privacy policy page.** Buy My Way is part of the Eat My Way brand, so its consent
   screen uses Eat My Way's support group, home page `https://eatmyway.gorny.dev` and privacy
   link `https://eatmyway.gorny.dev/privacy.html` (`gorny.dev` authorised). No separate
   domain. Play is out (decision 25), so nothing enforces the page, but Google shows it at
   sign-in. **To do in the Eat My Way repository:** a Buy My Way section on that page (what
   goes to Firebase, who can read it, push, voice, deletion). Requested 2026-09-21.
7. **Does *Testing* mode expire the grants after 7 days?** Answered by avoiding it: the
   consent screen goes *In production* (decision 25). Once it is published, the Apps Script
   check stays useful after any console change:
   `curl -sL -d '{"idToken":"x"}' <script url>` must answer
   `{"ok":false,"error":"unauthenticated"}`, not an authorisation error page.
8. **"Usuń moje dane" in the app?** The privacy page can only offer deletion by email until
   the app has it. A Settings action that deletes the user's lists where they are the owner,
   leaves the others, and removes `/users/{uid}`, `/emailIndex`, `/fcmTokens`,
   `/userLists` and their photos is small once Phase 5 exists. It is proposed for Phase 5
   or 9, and the owner decides when that phase starts.
9. **Limit sign-in to the household?** The RTDB rules (Phase 4) could accept writes only
   from uids listed under an `/allowed` node that only the owner can edit in the console.
   That would stop a stranger's Google account from using the quota even through our own
   APK, but every new user would need a manual step. Phase 4 decides.
