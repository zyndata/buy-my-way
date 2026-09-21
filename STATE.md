# STATE — Buy My Way

Single source of truth for progress and decisions. Update before and after every phase.
Any deviation from [PLAN.md](PLAN.md) must be recorded here before proceeding.

## Phase status

| Phase | Name                                   | Status  | Completed |
|-------|----------------------------------------|---------|-----------|
| 0     | Spike: Drive sharing & Google project  | done    | 2026-09-21 |
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
| 11    | Google Play closed testing             | dropped | 2026-09-21 |

Statuses: `pending` → `in-progress` → `done` (or `blocked` with a note, or `dropped` by a
decision).

Nothing of the app is built yet. The repository holds the plan, the workflow files, the
repository hygiene (2026-09-18) and the push sender's skeleton (`push/`, deployed).

**Phase 0 done (2026-09-21).** Under `drive.file`, a shared file is invisible to the other
member's copy of the app. So shared lists and photos move to Firebase Realtime Database and
the app requests no Drive scope (decisions 19–21). PLAN.md Phases 2, 4, 5 and 6 are
amended. Measured: an RTDB change reached the other phone in ~70–110 ms median one way
(decision 22); a push reached a killed app in 2.7 s, warm median 1.7 s (decision 24). The
Firebase project `buy-my-way-c3949` is on Spark with the rules locked again. The spike is
deleted, the Drive API is off, the spike's Drive folder is removed. Phase 1 is next.

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
      and 100 users is far above a household.
    - The privacy page is still shown: Google links it on the sign-in sheet and in Google
      Account → Connections. Nothing enforces its content any more, but it should tell the
      truth. So Eat My Way's `privacy.html` gets a Buy My Way section (open question 6),
      done in the Eat My Way repository.

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

   Still to do: register the **Linux machine's debug SHA-1** (Phase 1, the first build
   there). Nothing but public ids is written down.
3. **GitHub repository `zyndata/buy-my-way` exists but is private** (checked 2026-09-21),
   while decision 4 says public. The owner has to flip the visibility
   (`gh repo edit zyndata/buy-my-way --visibility public --accept-visibility-change-consequences`)
   and add the rulesets that Eat My Way has (protect `main` and `v*` tags, no bypass actors).
4. **App Link host.** The invite link is planned as `https://buymyway.gorny.dev/i/<token>`
   served by a static page on the existing VM (nginx + a one-line `assetlinks.json`). To be set
   up in Phase 5; until then the `buymyway://` scheme works on its own.
   The owner does not want a separate domain (2026-09-21), so Phase 5 should consider a path
   on `eatmyway.gorny.dev` (for example `/bmw/i/<token>`) instead of `buymyway.gorny.dev`.
5. **Room tests: Robolectric or the emulator?** Phase 2 decides. The emulator job exists from
   Phase 3 anyway, so instrumented is the likely answer unless it makes the CI loop too slow.
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
