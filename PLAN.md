# Buy My Way — Plan & Specification

## Product

**Buy My Way** — a shared shopping-list app for Android, the shop-side companion of
[Eat My Way](https://github.com/zyndata/eat-my-way). A small circle of people (a household, each
with their own Google account) keeps any number of shopping lists; a list is private or shared
with named people, and a shared list is **live**: when one person ticks an item off at the
shelf, everyone else who has the list open sees it crossed out within a second, and everyone
who has the app closed gets a notification. Items are grouped by the department of the shop
they are bought in, can carry a note and a photo, can be dictated, and a whole list can be
brought in from Eat My Way's shopping-list export with one share.

UI language: **Polish**. Code, comments and documentation: English.

### Non-goals — what this app will never contain

Taken deliberately from what the reference app (Listonic) does that this one must not:

- no premium tier, no subscriptions, no in-app purchases;
- no advertising, no ad SDKs, no consent dialogs for them;
- no leaflets, promotions, price tracking or shop integrations;
- no analytics, no crash reporting that leaves the device, no tracking of any kind;
- no location permission, no contacts permission;
- no AI chat, no recipes — that is Eat My Way's job;
- no server of ours. The push sender (below) is a script Google hosts, not a service we run.

### The one hard requirement

**A change one person makes reaches the other person's open screen in under a second, and
their closed app within a few seconds — without draining anyone's battery.** Every
architectural choice below serves that sentence:

- **Firebase Realtime Database holds the shared lists** — one node per item carrying its
  current state, written the moment someone changes it, which is what makes the other screen
  move instantly; photos sit beside them, downscaled;
- **Room on the phone is the source of truth for what the screen shows**, so the app works
  offline and signed out, and a list that is never shared never leaves the device;
- **Firebase Cloud Messaging wakes a closed app**, sent by a ~40-line Google Apps Script
  that runs under the owner's Google account for free.

The first plan kept the list documents on the owner's Google Drive with RTDB as the fast
signal. Phase 0 showed that under the `drive.file` scope another member's copy of the app
cannot see a shared file at all (STATE.md decisions 19–21), so the lists moved into RTDB and
Drive left the app.

## Architecture

```
  phone A (Compose UI)                                  phone B
  ┌────────────────────┐                                ┌────────────────────┐
  │ Room (source of    │      Firebase RTDB             │ Room               │
  │ truth on device)   │   /lists/{id}/items/{itemId}   │                    │
  │  ├ lists, items    │──write──▶ item node ──child──▶ listener ──▶ apply  │
  │  └ outbox          │   (rules: newer write wins)    │   (strike-through, │
  └────────────────────┘   /photos/{id}/{itemId}        │    then „Kupione") │
                                                        └───────▲────────────┘
                                                                │ on open / on push
                                                  Apps Script (push sender)
   phone A ──POST {listId} + Firebase ID token──▶  verifies token, reads members
                                                   & FCM tokens from RTDB, sends
                                                   FCM v1 data message ──▶ phone B
                                                   (closed) → CatchUpWorker →
                                                   notification „Ania dodała 3 …"
```

- **Room is the source of truth on the device.** Every screen reads Room; nothing renders from
  a network response. The app is fully usable offline; a private list never needs sign-in.
- **Every change is an operation** (`Op`): `item.put`, `item.check`, `item.delete`,
  `list.put`, `category.put`, `category.delete`, `items.clearChecked`. An op is applied to
  Room immediately (optimistic), kept in an outbox, and written to RTDB as the new state of the
  node it touches (an item, the list meta, a category), in one multi-path update. The Firebase
  SDK queues the write while offline.
- **Merging is deterministic.** Item content fields are last-writer-wins by `updatedAt`; the
  checked state is last-writer-wins by `checkedAt` *separately*, so a tick racing a note edit
  keeps both; a tombstone (`deletedAt`) beats any older write; applying the same state twice is
  harmless. The same pure Kotlin function merges a remote node into Room, and it is
  unit-tested to convergence (Phase 2). **The RTDB rules enforce the same order on the
  server**: a write whose `updatedAt` (or `checkedAt`) is older than the stored one is
  rejected, so a phone coming back from a week offline cannot overwrite a newer change.
- **The RTDB connection exists only while a list is on screen** (plus a 30 s grace period),
  never in the background. Background freshness comes from FCM and from a coarse WorkManager
  catch-up. See *Battery policy*.

## Stack

Versions are „latest stable at the time of Phase 1" and are pinned there in
`gradle/libs.versions.toml`; the plan names ranges, STATE.md records the numbers.

- **Kotlin 2.x**, JDK 21, Android Gradle Plugin 8.x, Gradle wrapper (LF `gradlew`, CRLF
  `gradlew.bat`), version catalog, KSP.
- **minSdk 26** (Android 8.0: notification channels, `SpeechRecognizer` offline preference,
  WebP encoding), **targetSdk / compileSdk = current** (36 at the time of writing).
- **Jetpack Compose** with **Material 3**, Navigation Compose, Lifecycle + ViewModel,
  `kotlinx.coroutines` / `Flow`.
- **Room** (KSP) for local data, **WorkManager** for the outbox flush, catch-up and
  periodic sync, **DataStore** (preferences) for small settings.
- **kotlinx.serialization** for every JSON document and payload.
- **Firebase** (BoM): `firebase-auth`, `firebase-database`, `firebase-messaging`. Nothing else
  from Firebase — no Analytics, no Crashlytics, no Storage.
- **Google identity**: `androidx.credentials` + `googleid` (Sign in with Google → Firebase
  Auth). No Drive scope and no `AuthorizationClient` (STATE.md decision 21).
- **OkHttp** for the two plain HTTPS calls the app makes outside Firebase: the push endpoint
  (Phase 9) and the GitHub update check (Phase 10).
- **Photos** come out of RTDB as bytes; whether Coil earns its place for the memory/disk cache
  and lifecycle-aware decoding, or a small in-house cache does, is decided in Phase 6 and
  recorded in STATE.md.
- **Testing**: JUnit 4 + `kotlinx-coroutines-test` for JVM unit tests (all pure logic lives in
  plain Kotlin so it is testable without Android); AndroidX Test + Compose UI test on an
  emulator for Room and the screen flows; **Firebase Emulator Suite** for the RTDB rules
  (`firebase/` directory, Node-based rules tests).
- **Android Lint** with warnings as errors on our own code; Kotlin `allWarningsAsErrors`.
  No ktlint/detekt — `.editorconfig` carries the formatting rules and Android Studio applies
  them on both machines.

Every dependency outside this list needs a STATE.md decision entry. This app holds a Firebase
session that can read and write every list its user belongs to; the fewer libraries, the fewer
places that session can go.

## Security

- `google-services.json` **is committed** — it is configuration, not a credential (Firebase's
  own guidance). The Android API key it holds is restricted in Google Cloud to this package
  name and the registered signing SHA-1s. What actually protects the data is the RTDB rules.
- Never in the repo: the release keystore and its passwords (GitHub Secrets; locally
  `~/.gradle/gradle.properties`), OAuth client secrets (none are needed — Android clients are
  public), Google tokens, real uids, list ids or emails in test fixtures.
- **No Drive scope.** Sign in with Google asks only for the basic profile scopes; the app holds
  no token that can touch the user's Drive (STATE.md decision 21). The consent screen can
  therefore leave *Testing* without verification.
- **The RTDB rules are the only authorization layer** for lists (Phase 5): only members read a
  list's nodes and photos, only editors and the owner write items and categories, a write
  older than the stored `updatedAt`/`checkedAt` is rejected, only the owner writes members,
  meta and deletes the list. Item and photo payloads are size-limited and shape-validated in
  the rules.
- **Where the data is**: in the owner's Firebase project. Its members reach it through the
  rules; the project owner can also read it in the Firebase console. For a household on the
  owner's own project that is the owner; SECURITY.md and the README say so plainly.
- The Firebase session lives in the Firebase SDK's own storage; the app never writes an ID
  token to Room, DataStore, logs or an Intent.
- The push endpoint (Apps Script) accepts a request only with a valid Firebase ID token whose
  user is a member of the list named, rate-limits per list, and sends nothing but a list id and
  a change kind in the FCM payload — item names never travel through FCM.
- A photo is downscaled and stripped of EXIF on the device before upload, and readable only by
  the list's members through the rules.

## Data model

Domain types are plain Kotlin (`core/model`), serialized with kotlinx.serialization; Room
entities mirror them in `data/local`.

```kotlin
data class ShoppingList(
  val id: String,              // UUID v4, generated on the device that creates the list
  val name: String,
  val ownerUid: String?,       // null for a private list of a signed-out user
  val shared: Boolean,         // true once it has a members node
  val synced: Boolean,         // true once it exists in RTDB (signed in)
  val categoryOrder: List<String>,   // category ids, the walk order of this list
  val createdAt: Long, val updatedAt: Long,
  val seenUpTo: Long           // server time of the newest remote change applied to Room
)

data class Item(
  val id: String, val listId: String,
  val name: String,
  val quantity: Double?, val unit: String?,   // „2", „kg"; both optional
  val categoryId: String,                     // one of the list's categories, „inne" by default
  val note: String?,
  val photoAt: Long?,                         // set when /photos/{listId}/{id} exists; null = none
  val checked: Boolean, val checkedAt: Long?, val checkedBy: String?,
  val createdAt: Long, val createdBy: String?,
  val updatedAt: Long, val updatedBy: String?,
  val deletedAt: Long?,                        // tombstone, kept 30 days
  val sortKey: Double,                         // manual order within a category
  val manualKey: Double                        // order in the „Ręcznie" view, across categories (Phase 5, STATE.md decision 62)
)

data class Category(val id: String, val listId: String, val name: String, val builtin: Boolean)

enum class Role { OWNER, EDITOR, VIEWER }
data class Member(val uid: String, val role: Role, val since: Long,
                  val name: String?, val email: String?, val photoUrl: String?)

@Serializable sealed class Op {           // every mutation, ever
  abstract val id: String; abstract val listId: String
  abstract val actor: String?; abstract val at: Long
  data class ItemPut(...: Item)           // upsert, content fields
  data class ItemCheck(itemId, checked: Boolean)
  data class ItemDelete(itemId)
  data class ListPut(name, categoryOrder)
  data class CategoryPut(...: Category)
  data class CategoryDelete(categoryId, moveItemsTo: String)
  data class ClearChecked()               // a list-level mark, `clearedAt` in meta (STATE.md decision 39)
  data class ListDelete()                 // added in Phase 2 (decision 39)
}
```

- **Built-in categories** are the nine departments of Eat My Way, with the same ids so an
  import maps one-to-one: `warzywa` „Warzywa i owoce", `nabial` „Nabiał i jaja", `mieso`
  „Mięso, ryby i wędliny", `pieczywo` „Pieczywo", `sypkie` „Sypkie i makarony", `przyprawy`
  „Przyprawy i dodatki", `mrozonki` „Mrożonki", `napoje` „Napoje", `inne` „Inne". A list can
  add its own categories and reorder all of them; the order is the list's, shared with it.
- **Auto-categorisation**: a bundled Polish product dictionary (`assets/products-pl.json`,
  ~600 common names → category id, built and maintained in-repo) proposes a category when an
  item is typed or dictated; the user's corrections are remembered per user in `/users/{uid}/prefs`
  and win over the dictionary. Never a network call to categorise.
- **Merge rules** (`core/sync/Merge.kt`, pure): `ItemPut` wins if `at > item.updatedAt`;
  `ItemCheck` wins if `at > item.checkedAt`; `ItemDelete` wins over anything older than it and
  is never undone by an older `ItemPut`; `ClearChecked` is an `ItemDelete` for every item
  checked before `at` (kept as `clearedAt` in the meta, so a late tick converges too; STATE.md
  decision 39). Ops apply in any order and any number of times to the same result.

## Storage layout

### Firebase Realtime Database

```
/users/{uid}                 { name, email, photoUrl, updatedAt }        write: self; name/email/photoUrl read: signed in (STATE.md decision 64)
/users/{uid}/prefs           { categoryMemory, defaultOrder, listOrder, listSort/{listId} } read/write: self
/users/{uid}/invites/{token} { listId, expiresAt }                         read/write: self (the owner's index of their invites)
/emailIndex/{email, . as ,}  uid                                          write: self, key = auth.token.email (decision 63)
/fcmTokens/{uid}/{token}     { at }                                       write: self; read: nobody (the script reads as owner)
/userLists/{uid}/{listId}    role                                         write: the list's owner
/lists/{listId}/meta         { name, ownerUid, categoryOrder, createdAt, updatedAt, updatedBy, clearedAt, deletedAt }
                             write: owner (name, order, clearedAt: editors too)
/lists/{listId}/members/{uid} { role, since }                             write: owner
/lists/{listId}/categories/{catId} { name, builtin, updatedAt, updatedBy, deletedAt, moveItemsTo } write: editor/owner
/lists/{listId}/items/{itemId}  { …Item fields… }                         write: editor/owner, only if not older
/lists/{listId}/presence/{uid} timestamp, removed by onDisconnect
/photos/{listId}/{itemId}    { webp: base64, w, h, by, at }               read: members; write: editor/owner; ≤ 110 kB
/invites/{token}             { listId, role, by, expiresAt, listName, byName } token: 128-bit random; read: signed in
```

- A member finds their lists through `/userLists/{uid}`, then reads `meta`, `categories` and
  `items` of each.
- **An item node is the whole truth about that item.** A write carries the full content fields
  with `updatedAt`/`updatedBy`, or the checked fields with `checkedAt`/`checkedBy`, or
  `deletedAt`; the rules accept it only if its timestamp is not older than the stored one.
  A device catching up reads `items` ordered by `updatedAt` from its `seenUpTo` (indexed in
  the rules), so after a month offline it downloads only what changed.
- **Tombstones** (`deletedAt`) are kept 30 days so a device that was offline learns about the
  deletion, then the owner's device removes the node and its photo.
- **Photos** live under a separate root so reading a list never downloads one; a row fetches its
  photo only when it is shown, and the device keeps a disk cache.
- **Presence** exists so the push sender can skip people who are looking at the list right
  now (they got the change through the listener already) — fewer pushes, fewer wake-ups.
- Firebase plan: **Spark** (free). The free limits (1 GB stored, 10 GB/month downloaded, 100
  simultaneous connections) hold a household's lists with room to spare; photos are what
  count, at ≤ 80 kB each about ten thousand of them.
- A **private list of a signed-out user** exists only in Room. Signing in uploads it
  (members = the owner alone), which is also what gives the user a second device and a
  backup.

### Push sender (Google Apps Script, `push/`)

A web app deployed from `push/Code.gs` + `push/appsscript.json`, *Execute as: me*, *Access:
anyone*, bound to the Firebase project's Cloud project so `ScriptApp.getOAuthToken()` carries
the `firebase.database` and `firebase.messaging` scopes — **no service-account key anywhere**.

1. Client: after a change is written, unless every other member is present, `POST <script url>`
   with `{ listId, kind }` and the user's Firebase ID token — debounced 5 s per list, so a
   burst of ten items is one push saying „10 zmian".
2. Script: validates the ID token with Firebase Auth REST (`accounts:lookup`, public Web API
   key), checks the caller is in `/lists/{listId}/members`, rate-limits per list with
   `CacheService`, reads the other members' `/fcmTokens`, sends one FCM v1 **data** message per
   token (`android.priority: high`, payload `{ listId, kind, count, actor }`), and deletes
   tokens FCM reports as unregistered.
3. Receiver: `FirebaseMessagingService` enqueues an expedited `CatchUpWorker(listId)`, which
   reads the items changed since the device's `seenUpTo`, applies them, and posts a
   notification when the list is not on screen.

Latency 1–3 s (Apps Script cold start); free on a consumer Google account (20 000 URL fetches
a day; a push is one fetch per recipient). If it ever proves unreliable, the same client
contract can be served by a Cloud Function on Blaze — STATE.md decision 5 records the choice
and the fallback.

## Battery policy

This is a requirement, not a preference. Concretely:

- **No foreground service, ever.** No persistent connection in the background.
- The Firebase database connection is opened when a list screen is resumed and closed
  (`goOffline()`) 30 s after the app leaves the foreground. Listeners are attached only to
  the list on screen and to `/userLists/{uid}` on the home screen.
- Background freshness: FCM (data message, high priority only because a notification follows)
  → expedited one-shot `CatchUpWorker`. Plus one **periodic** `CatchUpWorker` every 3 hours
  with `NetworkType.CONNECTED` and `requiresBatteryNotLow`, as insurance when a push was
  dropped. Nothing polls.
- Writes leave through the Firebase SDK while the connection is open. An outbox entry is
  cleared only when its write is acknowledged; entries still pending when the connection
  closes are flushed by one `OutboxWorker` (unique work, `NetworkType.CONNECTED`), which goes
  online, re-sends them (state writes are idempotent) and goes offline again.
- Photos are downscaled before upload and cached on the device; a list screen never downloads
  a photo it does not show.
- On app open: one read of `/userLists`, one `meta` read per list, the items changed since
  `seenUpTo` — a few kilobytes.
- Verification (Phase 9): a day of ordinary use leaves the app absent from Android's battery
  usage screen; `dumpsys batterystats` shows no wakelocks held by the app outside worker
  runs; the Energy Profiler shows a flat line while a list is open but idle.

## Google identity

- **Sign-in is optional** and is asked for when the user first shares a list, imports from Eat
  My Way into a shared list, or turns on notifications — never at first launch. Private lists
  work signed-out and are adopted (uploaded) when the user signs in.
- Sign in with Google via Credential Manager gives an ID token → `FirebaseAuth
  .signInWithCredential`. That is the only Google permission the app asks for: basic profile,
  no Drive (STATE.md decision 21).
- One Google Cloud project = the Firebase project; one Android OAuth client per signing
  SHA-1 (each developer machine's debug key and the release key), one Web client id used as `serverClientId`. All ids are public; they live in
  `gradle.properties`.
- Sign-out clears Room, DataStore and the Firebase session. The lists stay in RTDB for the
  other members and for the user's next sign-in.
- A second account on the same device is not supported: signing in as someone else is a
  sign-out first, with the sentence that says so.

## Sharing & permissions

- Roles: **owner** (everything, including permissions and deletion), **editor** (add, edit,
  check, delete items, add categories), **viewer** (sees and cannot touch — not even check;
  STATE.md decision 6 keeps the rules to one question per write).
- Invite by **link** (an App Link into the app on the Eat My Way host, e.g.
  `https://eatmyway.gorny.dev/bmw/i/<token>` — STATE.md open question 4 —, served as a static
  redirect page; the token is one-use-per-person,
  7-day expiry) or by **email** (the owner types an address; if that person has signed in
  before, `/emailIndex` resolves them and they see the list on next open; otherwise the app
  offers the link to send).
- Accepting an invite writes the member into `/lists/{listId}/members` under a rule that
  checks the invite. The rules then let them read the whole list and its photos at once;
  there is nothing for the owner's device to mirror.
- Changing a role or removing a member is the owner's action and takes effect in the rules
  immediately. A removed member's device gets a permission-denied on its next read, drops the
  list locally and says so.
- „Uczyń prywatną" removes all members; „Udostępnij" turns a private list into a shared one by
  creating the members node with the owner in it.

## Voice input

- Android `SpeechRecognizer`, `pl-PL`, `EXTRA_PREFER_OFFLINE` when the device has the language
  pack; on-device where available, Google's recognizer otherwise. No third-party speech
  library, no Gemini in the MVP.
- One utterance can name many items: „dwa kilo ziemniaków, mleko, masło i chleb". A pure
  parser (`core/voice/Dictation.kt`) splits on „,", „ i ", „oraz", newlines; reads a leading
  number (digits or Polish number words up to twenty) and a unit („kg", „kilo", „g", „litr",
  „sztuki", „opakowanie"…) into `quantity`/`unit`; the rest is the name.
- Result goes to a **review sheet** before anything is added: each parsed item as a chip with
  its proposed category; tap to fix, „Dodaj wszystkie". Dictation never writes directly.
- The mic button stays in the add bar; a second tap while listening stops.

## Import from Eat My Way

Eat My Way shares a plain-text list (`src/lib/shopping.ts` there, `formatShoppingList`). The
exact shape as of Eat My Way v1.16:

```
Lista zakupów — tydzień 15.09 – 21.09

Warzywa i owoce
• Cebula — 2 szt. (160 g)
• Czosnek — 2 ząbki (10 g)

Nabiał i jaja
• Mleko — 500 ml (500 g)
```

- Title line begins with „Lista zakupów"; blank line; groups headed by one of the nine
  department labels; lines `• <name> — <amount>` optionally followed by ` (<n> g)`. Empty list:
  „Brak składników do kupienia."
- Buy My Way registers as a **share target** for `text/plain` and also offers „Wklej ze
  schowka". The parser (`core/import/EatMyWayImport.kt`, pure) recognises the format by the
  heading set and the „• … — …" line shape; an unknown heading becomes a custom category with
  that name; text that is not in this format is imported as one item per non-empty line.
- The preview screen shows the parsed items grouped, lets the user pick a target list (or
  create one named after the title), and merges: same name and unit → quantities are summed;
  otherwise appended. A parsed name is auto-categorised only if the export gave no heading.
- No change to Eat My Way is required. A machine-readable export from Eat My Way (JSON or an
  App Link) is an open question there, not here.

## Screens & navigation

All copy in Polish. Navigation Compose, single activity, predictive back.

- **Listy** (home): cards — name, „3 / 12", avatars of members, lock icon for private, „Ania
  ogląda" when someone is present. FAB „Nowa lista". Long-press: Zmień nazwę, Udostępnij,
  Uprawnienia, Usuń. Signed-out banner only once sharing is attempted.
- **Lista**: items grouped under category headings in the list's walk order; within a
  category by `sortKey`; the **„Kupione" section** collapsed at the bottom with „Wyczyść
  kupione". Tap a row = check. The row strikes through immediately, then after ~800 ms slides
  into „Kupione" (undo by tapping it there). A change made by *someone else* strikes through
  with the actor's initial, holds 1.5 s, then slides — the feedback the requirement asks for.
  Bottom add bar: text field with autocomplete from the dictionary and this user's history,
  mic button, „+". Long-press or swipe on a row opens the **edit sheet**: name, quantity +
  unit, category, note, photo (camera / gallery / remove), and under them, read-only, when it
  was last edited („Edytowano 22.09.2026, 08:56", from `updatedAt`) and, for a bought item,
  when it was ticked („Kupiono …", from `checkedAt`). Toolbar: Udostępnij, Kolejność
  kategorii, Sortowanie, Zaznacz wszystko, Odznacz wszystko.
  **Sortowanie** (STATE.md decision 62) has three views of the items still to buy:
  „Według działów" (as above, the default), „Alfabetycznie" (A–Z by the Polish collation, one
  flat list) and „Ręcznie" (one flat list in the order the user dragged it, by `manualKey`).
  The two flat views show no category headings. „Kupione" stays at the bottom in every view.
  An item added in „Ręcznie" goes to the end.
- **Udostępnianie**: members with roles, „Zaproś linkiem", „Zaproś e-mailem", role menu,
  remove; „Uczyń prywatną".
- **Import**: preview from the share sheet or the clipboard.
- **Ustawienia**: account (Zaloguj / Wyloguj, email shown), notifications (per kind), default
  category order for new lists, „Kopia listy" (share as text / JSON), „Sprawdź aktualizację",
  version and licence.
- Dark theme follows the system; dynamic colour on Android 12+.

## Repository & workflow

Mirrors Eat My Way exactly where it can, because the same person maintains both from the same
two machines:

- Public repository `zyndata/buy-my-way`, MIT, branches `dev` → `main`, release by `vX.Y.Z`
  tag. `CLAUDE.md` (rules), `PLAN.md` (this file), `STATE.md` (progress and decisions),
  `CHANGELOG.md` (git-cliff, generated), `README.md`, `SECURITY.md`, `docs/DEVELOPMENT.md`,
  `docs/DEPLOYMENT.md`, `.claude/commands/phase.md` (`/phase N`), `.claude/skills/release`
  (`/release`, Phase 10), `.github/workflows/ci.yml` + `deploy.yml`, dependabot for Gradle,
  npm (rules tests) and Actions, issue templates.
- **CI (`ci.yml`)** on every push to `dev` and on PRs: `check` (JDK 21, Gradle cache, `lint`,
  `testDebugUnitTest`, `assembleDebug`), `rules` (Firebase emulator, rules tests), and from
  Phase 2 `instrumented` (Android emulator, `connectedDebugAndroidTest`). Green CI is the
  only evidence that counts, exactly as in Eat My Way; the instrumented job is this project's
  e2e gate.
- **Release (`deploy.yml`)** on a `v*` tag: `assembleRelease` signed from secrets → git-cliff
  CHANGELOG commit-back → GitHub Release with the APK attached. There is no Google Play
  (STATE.md decision 25).
- **One phase per conversation** via `/phase N`; STATE.md updated before and after; deviations
  recorded before they are acted on; Conventional Commits; a push is done only when its CI run
  is green.
- **Two machines** (Windows + Linux): LF in the repo (`.gitattributes`), `gradlew.bat` CRLF, no
  absolute paths (`local.properties` is ignored), each machine's debug SHA-1 registered in
  Firebase (docs/DEVELOPMENT.md).

---

# Phases

Each phase is one conversation, started with `/phase N`. Phase 0 is a spike whose outcome can
change the plan; its findings are recorded in STATE.md before Phase 1 begins. It did change it:
Phases 2, 4, 5 and 6 below were amended on 2026-09-21 (STATE.md decisions 19–21).

## Phase 0 — Spike: Drive sharing under `drive.file`, and the Google project

The hybrid depends on one thing the documentation is vague about: **can user B's copy of the
app read and write a file that user A's copy created and shared with B, when both hold only
`drive.file`?** If not, the fallback is the `drive` scope (works, but sensitive → verification
or Testing mode forever) or Firebase-held data. Find out before writing the sync layer.

### Tasks

1. Create the Google Cloud / Firebase project (`buy-my-way`), Realtime Database (europe-west1,
   locked rules), OAuth consent screen in *Testing* with the household as test users, one Web
   client id, Android client ids for both machines' debug SHA-1s. Enable the Drive API. Record
   every id that is public in STATE.md; nothing else.
2. A throwaway Kotlin spike (in `spike/`, deleted at the end of the phase, or kept out of the
   build) that: signs in with Google, requests `drive.file`, creates `Buy My Way/spike/` with a
   file, shares the folder with the second test account as `writer`; on the second account:
   `files.list` with `q='<folderId>' in parents`, `files.get?alt=media`, `files.update` on the
   file — and back on the first account, reads the change. Repeat with `drive.appdata` for
   `prefs.json`.
3. Measure the round trip that matters: op written on phone A → child event on phone B over
   mobile data, 20 samples; and Drive `files.get` of a 20 kB file, 10 samples.
4. Write the Apps Script skeleton (`push/Code.gs`) that validates a Firebase ID token and sends
   one FCM message to a hard-coded token; deploy it; measure cold and warm latency.
5. Record the findings in STATE.md (a decision each): the scope verdict, the latencies, any
   surprise in the Google console setup. Adjust PLAN.md Phase 4/5 if the verdict is negative.

**Verdict (2026-09-21): it cannot.** The second account got an empty list and 404s; the plan
below is already amended (STATE.md decisions 19–21): lists and photos in RTDB, no Drive scope.
The Drive `files.get` timing of task 3 no longer describes a path the app uses and is not
measured.

### Acceptance criteria

- [x] The second account, holding only `drive.file`, lists, reads and updates the file the
      first account created and shared — or STATE.md records that it cannot, and the plan is
      amended before Phase 1. *(It cannot; amended.)*
- [x] RTDB op round trip median and p95 recorded; Apps Script push latency recorded.
      *(Warm and to a killed app; the script's cold start is left to Phase 9.)*
- [x] Firebase project exists on the Spark plan; RTDB rules deny everything to unauthenticated
      users; the consent screen lists the test users.
- [x] No spike code is left in the build path.

## Phase 1 — Scaffold & CI

### Tasks

1. Gradle project: `app` module, `applicationId dev.gorny.buymyway`, version catalog, Kotlin
   2.x, Compose + Material 3, Navigation Compose, minSdk 26, target/compile current. Product
   name „Buy My Way". `versionName` derived from `git describe --tags` at build time (falls
   back to `0.0.0-dev`), `versionCode` from the tag or a timestamp for local builds.
2. Navigation shell with placeholder screens rendering their Polish titles: `lists`,
   `list/{listId}`, `list/{listId}/share`, `import`, `settings`. Dark theme, dynamic colour.
3. `google-services.json` committed; Firebase BoM with auth/database/messaging on the
   classpath but not yet called; `FirebaseApp` initialises without error.
4. Repository hygiene already present from scaffolding — verify, do not recreate:
   `.gitattributes`, `.editorconfig`, `.gitignore` (Android + `local.properties` + keystores),
   `cliff.toml`, dependabot, issue templates, `LICENSE`.
5. `ci.yml`: remove the „no gradlew yet" guard; jobs `check` (`./gradlew lint
   testDebugUnitTest assembleDebug`) with Gradle caching. A unit test that exists and passes
   (the merge stub's identity test is enough).
6. `docs/DEVELOPMENT.md`: JDK, Android Studio, `local.properties`, debug SHA-1 per machine and
   where to register it, the emulator, the Gradle tasks that are the interface to the project.
7. Lint: Android Lint with `warningsAsErrors = true`, `abortOnError = true`; Kotlin
   `allWarningsAsErrors`.

### Acceptance criteria

- [ ] `./gradlew assembleDebug` succeeds from a clean checkout on Windows and on Linux; the
      APK installs and every route shows its Polish title.
- [ ] `./gradlew lint testDebugUnitTest` is clean.
- [ ] CI is green on `dev` with the guard removed.
- [ ] `git ls-files --eol` shows LF for every text file except `gradlew.bat`.
- [ ] No dependency outside the stack list without a STATE.md decision.

## Phase 2 — Local data layer & the merge

### Tasks

1. Room schema v1: `lists`, `items`, `categories`, `members`, `outbox_ops`, `list_sync`
   (per-list `seenUpTo`, `synced`, dirty flag). Exported schema JSON committed; migrations
   tested from v1 onward.
2. `core/model` domain types and `core/sync/Merge.kt`: `apply(op, state) -> state` and
   `mergeRemote(local, remoteNode) -> state`, both pure. Property-style unit tests:
   commutativity over shuffled op orders, idempotence, tombstone dominance, check/content
   independence, and local-vs-remote node merges converging whichever side arrives first.
3. `ListRepository`: every mutation is an op — applied to Room in one transaction with an
   outbox insert. `Flow`s per screen. No network in this phase; the outbox simply accumulates.
4. Built-in categories seeded per list; `categoryOrder` defaults from the user's DataStore
   preference or the nine-department order.
5. `assets/products-pl.json` v1 (~600 entries) and `Categorizer` (normalises Polish
   inflection by stem prefix matching; „ziemniaków" → „ziemniak" → `warzywa`); unit tests.
6. Codec between the domain types and the RTDB node shapes of *Storage layout* (maps of
   primitives, as the Firebase SDK wants them), tolerant of unknown fields; round-trip tests.

### Acceptance criteria

- [ ] Merge tests prove convergence over ≥ 1000 random op orders per scenario.
- [ ] Room tests (instrumented or Robolectric — decide and record) cover every DAO used by a
      screen; the exported schema is committed and the migration test passes.
- [ ] `Categorizer` categorises ≥ 90 % of a 100-item sample list taken from a real week's Eat
      My Way export; misses go to `inne`, never to a wrong department.
- [ ] Still no network permission used; still no sign-in.

## Phase 3 — Lists & items on screen

### Tasks

1. **Listy** screen: create, rename, delete (with undo snackbar), reorder by drag; counts.
2. **Lista** screen: grouped items, the „Kupione" section, tap-to-check with the
   strike-through-then-slide animation, undo from „Kupione", „Wyczyść kupione", manual
   reorder within a category, category order editor (drag), empty state.
3. Add bar: text with autocomplete (dictionary + this device's history), quantity/unit
   parsing from the typed text („2 kg ziemniaki"), category proposal shown as a chip the user
   can tap to change before adding, „+" and IME action add. Multiple items by comma.
4. Edit sheet: name, quantity + unit, category, note; delete. Photo slot present but disabled
   until Phase 6.
5. **Ustawienia** skeleton: default category order, theme follows system, version.
6. Compose UI tests on the emulator for: add → check → appears in Kupione → undo; category
   reorder persists; delete + undo. `ci.yml` gains the `instrumented` job (emulator API 34,
   `connectedDebugAndroidTest`), cached AVD.
7. Haptics on check; accessibility: every row and control has a content description, the
   strike-through is also announced.

### Acceptance criteria

- [ ] A private list is fully usable offline, signed out, across process death.
- [ ] Checking an item shows the strike-through immediately and moves it to „Kupione" within a
      second; tapping it there restores it.
- [ ] Instrumented job green in CI; total CI time recorded in STATE.md.
- [ ] TalkBack reads the list sensibly (manual check, recorded).

## Phase 4 — Google sign-in & cloud persistence

### Tasks

1. Sign in with Google (Credential Manager) → Firebase Auth; `/users/{uid}` and
   `/emailIndex` written; sign-out flow; the „another account" sentence.
2. `RemoteLists` over the Firebase SDK: write a list's `meta`, `categories` and `items`
   (multi-path update per op, outbox entry cleared on acknowledgement); read `/userLists`,
   then each list; catch up by `items` ordered by `updatedAt` from `seenUpTo`; merge every
   remote node into Room through Phase 2's `Merge`.
3. First version of `firebase/database.rules.json` for a single user's own lists: owner-only
   read/write, the not-older-than-stored check on items, shape and size validation; rules
   tests in `firebase/` with the emulator; `ci.yml` gains the `rules` job.
4. `OutboxWorker` (unique, network constraint) for writes still pending when the connection
   closes; `goOffline()` 30 s after background.
5. On sign-in: adopt existing private lists (upload them with the owner as the only member).
   On app open and on pull-to-refresh: catch up every list.
6. `/users/{uid}/prefs`: category memory and default order, merged by `updatedAt`.
7. Tests: the rules tests above, and the Room ↔ remote merge driven through a fake
   `RemoteLists` with two writers and a delayed one; an instrumented sign-in smoke test with
   the emulator's Google account is **not** attempted — the fakes are the evidence.

### Acceptance criteria

- [ ] Two devices signed in as the same account converge on a list after edits on both while
      one was offline.
- [ ] A write carrying an older `updatedAt` than the stored one is rejected by the rules
      (rules test) and the device that sent it adopts the newer state.
- [ ] ID tokens appear nowhere in Room, DataStore or logcat.
- [ ] CI green, including the `rules` job.

## Phase 5 — Sharing & real-time

### Tasks

1. `firebase/database.rules.json` extended to the full layout above — members, roles,
   invites, presence, `/userLists` — with rules tests (`firebase/test/rules.test.mjs`,
   emulator) for every allow/deny in the table.
2. Live listener: a child listener on `items` (and `categories`, `meta`) attached while the
   list is on screen; incoming nodes applied through `Merge`; `seenUpTo` advanced.
3. Presence with `onDisconnect`; the „Ania ogląda" line on the home card and in the list's
   top bar.
4. **Udostępnianie** screen: invite by link (`/invites/{token}`, the App Link on the Eat My
   Way host — STATE.md open question 4 — plus the `buymyway://` scheme as fallback), invite by
   email, roles, remove, make private.
5. The remote-change feedback: a change by someone else strikes the row with the actor's
   initial, holds 1.5 s, slides to „Kupione"; an added item fades in under its category; a
   deleted one fades out. Never a full-list refresh.
6. Tombstones older than 30 days removed by the owner's device, with their photos.
7. Removed-member and permission-denied handling: the list is dropped locally with a sentence.
8. Item dates in the edit sheet: „Edytowano <date, time>" from `updatedAt` and, for a bought
   item, „Kupiono <date, time>" from `checkedAt`, formatted in Polish (`pl-PL`, the phone's
   time zone). Phase 5 adds who did it where the member is known („Kupiono … · Ania").
9. „Sortowanie" on the Lista toolbar (STATE.md decision 62): „Według działów", „Alfabetycznie",
   „Ręcznie". The flat views have no category headings. „Ręcznie" drags any item anywhere
   with the existing handle and move actions. The chosen view is this user's, per list
   (DataStore, then `/users/{uid}/prefs/listSort/{listId}`). The manual order is the list's:
   a new content field `manualKey` (Room schema v2 with its migration and migration test,
   `NodeCodec`, the rules' shape and content-group checks), seeded from the department order
   the first time „Ręcznie" is chosen. Pure ordering in `core/model` with unit tests.

### Acceptance criteria

- [ ] Two phones, two accounts, mobile data: a check on one shows on the other in < 1 s
      (p95 over 20 tries recorded in STATE.md), with the animation described.
- [ ] Rules tests cover: non-member read denied, viewer op denied, editor op allowed, op update
      denied, member write by non-owner denied, invite acceptance allowed once and expired
      denied.
- [ ] A member invited by link sees the whole list and its photos as soon as they accept,
      without the owner's phone doing anything.
- [ ] Airplane mode on one phone for ten minutes of edits on both: reconnect converges, no
      duplicates, no lost checks.
- [ ] The edit sheet shows the last edit and, for a bought item, when it was bought.
- [ ] Each of the three views sorts as described. The flat ones show no headings. A manual
      order survives process death, reaches the other member's phone, and does not change
      that member's chosen view.

## Phase 6 — Photos

### Tasks

1. Camera (`ActivityResultContracts.TakePicture`) and gallery (Photo Picker, no storage
   permission); downscale to ≤ 800 px, WebP, quality stepped down until ≤ 80 kB, EXIF
   orientation applied and stripped.
2. Write to `/photos/{listId}/{itemId}` by a `PhotoWorker`; `photoAt` written on the item only
   after the photo write succeeds; replace and remove. Rules: members read, editors write,
   size capped.
3. Loading and caching: decide Coil vs. a small disk cache keyed by `photoAt` (STATE.md
   decision); thumbnail in the row, full-screen viewer with pinch-zoom.
4. Orphan cleanup: photos of items deleted 30 days ago are removed by the owner's device.

### Acceptance criteria

- [ ] A photo taken on one phone appears on the other's row within seconds and is visible
      full-screen; removing it removes the `/photos` node.
- [ ] A 12-megapixel photo becomes ≤ 80 kB; no `READ_EXTERNAL_STORAGE`/`READ_MEDIA_*`
      permission is declared.
- [ ] The photo disk cache is capped (50 MB), the app's storage does not grow with viewing,
      and a photo already cached is not downloaded again.

## Phase 7 — Voice input

### Tasks

1. `RECORD_AUDIO` permission flow with the one-sentence rationale; `SpeechRecognizer` wrapper
   with `pl-PL`, offline preference, partial results shown while speaking, error mapping to
   Polish sentences („Nie słyszę — spróbuj bliżej mikrofonu").
2. `Dictation` parser (pure) — separators, number words, units, quantity placement before or
   after the name; 60+ unit test cases from real utterances.
3. Review sheet: parsed chips with category, edit inline, „Dodaj wszystkie", „Dyktuj dalej".
4. Devices without a recognizer: the mic button hides itself.

### Acceptance criteria

- [ ] „dwa kilo ziemniaków, mleko, masło i chleb" becomes four items with the right quantity,
      unit and categories, after one confirmation tap.
- [ ] Works with the phone offline when the Polish pack is installed (manual, recorded).
- [ ] No audio is stored or sent anywhere but the system recognizer.

## Phase 8 — Import from Eat My Way

### Tasks

1. `EatMyWayImport` parser (pure) for the format above; tolerant of the „(n g)" suffix,
   measures („2 ząbki"), unknown headings, plain lists; 30+ tests including a real export.
2. Share-target intent filter (`ACTION_SEND`, `text/plain`) and „Wklej ze schowka" in the
   home screen's overflow.
3. Preview screen: grouped items, target list picker (existing or new named after the title's
   date range), merge rules (sum same name+unit), then one batch of ops.
4. Record in STATE.md what a structured export from Eat My Way would need, as an open question
   for that project.

### Acceptance criteria

- [ ] Sharing a week's list from Eat My Way on the same phone lands every line in the right
      category with its quantity, in one tap after the preview.
- [ ] Importing the same text twice into the same list sums quantities and adds no duplicate.
- [ ] Any random text shared to the app becomes a line-per-item list, never a crash.

## Phase 9 — Background, notifications & the battery verdict

### Tasks

1. FCM: token registration to `/fcmTokens/{uid}`, refresh, removal on sign-out;
   `FirebaseMessagingService` → expedited `CatchUpWorker` (items since `seenUpTo`).
2. `push/Code.gs` finished as specified; deployment documented in `docs/DEPLOYMENT.md` (manual
   or `clasp`); the URL in `gradle.properties`; the client's debounced, presence-aware call.
3. Notifications: channels „Zmiany na wspólnej liście" and „Nowe udostępnione listy";
   grouped per list („Ania: +3, ✓ 2 — Biedronka"), tap opens the list, `POST_NOTIFICATIONS`
   requested when the user turns notifications on in settings, per-kind toggles.
4. Periodic `CatchUpWorker` (3 h, constraints as in the policy); the `goOffline()` grace
   period; nothing else in the background. Verify Doze behaviour with `adb shell dumpsys
   deviceidle force-idle`.
5. Battery measurement per the policy; numbers in STATE.md.

### Acceptance criteria

- [ ] App closed on phone B: a change on phone A produces a notification on B within 5 s on
      Wi-Fi and mobile data (p95 recorded); tapping it opens the list already updated.
- [ ] Both phones idle overnight with the app installed: the app is absent from the battery
      screen and `batterystats` shows no wakelocks outside worker runs.
- [ ] The push endpoint rejects a request without a valid token and one from a non-member
      (tested by hand with `curl`, recorded).
- [ ] Notifications off in settings → no notification, but the list is still fresh on open.

## Phase 10 — Release engineering & 1.0

### Tasks

1. Release signing from GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD`); locally from `~/.gradle/gradle.properties`; R8 with a tested
   `proguard-rules.pro` (kotlinx.serialization, Room, Firebase keep rules).
2. `deploy.yml` on `v*` tags: `check` (lint, unit tests) → `assembleRelease` → git-cliff
   CHANGELOG commit-back to `main` → GitHub Release with `buy-my-way-vX.Y.Z.apk` and its
   SHA-256.
3. In-app update check: GitHub `releases/latest` once a day, compare `tag_name` with
   `versionName`, banner „Dostępna wersja X — Pobierz"; download via `DownloadManager`,
   install via the package installer (`REQUEST_INSTALL_PACKAGES`).
4. `.claude/skills/release/SKILL.md` adapted from Eat My Way: local install to a connected
   device vs. tag release; the CI-green gate on `dev` before merging to `main`.
5. `README.md` with screenshots (`scripts/screenshots` via the emulator and `adb`),
   `SECURITY.md`, a check that the Buy My Way section of Eat My Way's privacy page matches
   what the app does (STATE.md open question 6), `docs/DEPLOYMENT.md` complete.
6. Tag `v1.0.0` after a week of daily use by the household with no open bug.

### Acceptance criteria

- [ ] A tag produces a Release with a signed APK whose SHA-256 matches; installing it over the
      debug build is refused (different signature) and over the previous release succeeds.
- [ ] The in-app update banner appears on the older build and installs the newer one.
- [ ] README status, screenshots and the „what it does" claims match the app.

## Phase 11 — Google Play closed testing (dropped)

Dropped on 2026-09-21 (STATE.md decision 25): the app is distributed through GitHub Releases
only, and the OAuth consent screen is published *In production*, so no grant expires after
7 days.

## Later — after daily use

Not planned in detail; recorded so nobody forgets them: „Zapisz kopię na Dysku" (an export
of a list to the user's own Drive under `drive.file`, which works for one's own files); a
structured export from Eat My Way;
Wear OS glance of the current list; widgets; „often bought" suggestions from this user's own
history; optional Gemini (BYO key, as in Eat My Way) for smarter dictation; iOS is out of scope.
