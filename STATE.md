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
| 4     | Google sign-in & cloud persistence     | done    | 2026-09-22 |
| 5     | Sharing & real-time                    | done    | 2026-09-22 |
| 6     | Photos                                 | done    | 2026-09-22 |
| 7     | Voice input                            | done    | 2026-09-23 |
| 8     | Import from Eat My Way                 | done    | 2026-09-23 |
| 8b    | „Moje produkty"                        | pending |           |
| 9     | Background, notifications & battery    | pending |           |
| 10    | Release engineering & 1.0              | pending |           |
| 11    | Google Play closed testing             | dropped | 2026-09-21 |

Statuses: `pending` → `in-progress` → `done` (or `blocked` with a note, or `dropped` by a
decision).

The repository holds the plan, the workflow files, the repository hygiene (2026-09-18), the
push sender's skeleton (`push/`, deployed), from Phase 1 the Android app's scaffold, from
Phase 2 its local data layer, from Phase 3 its screens for private lists, from Phase 4
Google sign-in with the lists kept in Firebase Realtime Database, from Phase 5 sharing
with other people and live changes, from Phase 6 photos on items, from Phase 7 dictation, and
from Phase 8 the import of a shopping list shared out of Eat My Way.

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
in again). CI time: 6 min 44 s for the whole run (instrumented job 6 min 41 s,
lint + unit tests + build 3 min 49 s, in parallel), run 35654135415, green. The next run (35654876097, a docs-only commit) failed once:
a UI test typed into the add bar before the list had loaded on the slower CI emulator. The
test now waits for the bar. Phase 4 is next.

**Phase 4 done (2026-09-22).** Sign in with Google lives in Ustawienia (account, „N zmian
czeka na wysłanie", „Wyloguj się" with a warning when changes are unsent). On sign-in, the
lists made signed out are adopted and uploaded whole. After that every change goes to RTDB
as one multi-path update of its field group, and is removed from the outbox once
acknowledged. A write the rules refuse makes the phone read the list and adopt the newer
state. A catch-up (on opening the app, at most every 30 s, and pull-to-refresh on Listy)
reads `/userLists` and then each list, with items by a server-stamped `changedAt` (decision
54). `/users/{uid}/prefs` carries the default order, the list order and the category memory.
A lost session shows „Zaloguj się ponownie" and keeps everything (decision 23). Signing in
with another account while the phone holds one's lists is refused with a sentence. The first
`firebase/database.rules.json` covers a user's own lists, profile, email index and prefs, and
the CI `rules` job tests it. The owner published it in the console. Decisions 53–61.
**What wakes the device:** only the one-shot `OutboxWorker`, and only when changes were
left unsent as the app left the foreground (decision 58). Nothing periodic.
Verified: 33 rules tests on the Firebase emulator. A deliberately loosened item-ordering rule
made 2 of them fail. 64 JVM tests (10 new, for the write shapes). 27 instrumented tests on the
API 35 emulator, 6 of them new sync scenarios over an in-memory server that mirrors the
rules: two phones on one account converging after edits on both while one was offline (Room
states equal); an older write refused and the newer state adopted; a lost acknowledgement
resent harmlessly; adoption at sign-in; a deleted list gone on the other phone and removed
from RTDB after 30 days; preferences following the account. Skipping the adopt step made
the "older write" test fail. **By hand on the physical S10e against the real project:** the
Phase 3 build was upgraded in place, and signing in adopted and uploaded its 13-item list
(owner and authors set, outbox empty, `seenUpTo` read back from the server). A tick made with
Wi-Fi and mobile data off waited in the outbox and was sent 11 s after the network came back.
„Wyloguj się" emptied the phone. Signing in again brought the list back from RTDB with that
tick („3 / 13"). The app's Room database, its DataStore and a full logcat hold no ID token (no
`eyJ…`). The Firebase session is only in Firebase Auth's own store. The only log line the app
writes names an exception class. **Not verified:** two *physical* phones at once (the
emulator has no Google account, and the S10e alone stood in for the second phone by signing
out and in). The rules refusing a write on the real server, as opposed to on the emulator.
The `OutboxWorker` running after the app left the foreground (the foreground retry sent the
tick). A lost session on a device. The Linux machine (open question 2 still stands). One
flake: after the emulator's system server died and the emulator was rebooted, the Phase 3
strike-through test missed its 1 s window once, and it passed on the rerun. Also found: when
the emulator cannot install anything, `connectedDebugAndroidTest` reports success with **zero
tests**, so a local run is only evidence together with its test count. Phase 5 is next.

**Phase 5 done (2026-09-22).** Lists can be shared. „Udostępnij" (on the list and on its card)
opens Udostępnianie. There the owner invites by link
(`https://eatmyway.gorny.dev/bmw/i/<token>`, 7 days, or `buymyway://i/<token>`) or by e-mail
(anyone who has signed in; nobody is e-mailed), gives „Może edytować" or „Tylko przegląda",
removes people, and can make the list private again. A member can leave. A list taken away
leaves the phone with a sentence. While a list is open, its meta, members, categories and
changed items are live listeners, and presence says „Ania ogląda" on the list and on its card.
Someone else's tick shows their initial, holds 1.5 s, then slides into „Kupione". A viewer
gets no add bar and no taps. The edit sheet shows „Edytowano …" and „Kupiono … · Ania".
„Sortowanie" offers „Według działów", „Alfabetycznie" and „Ręcznie". The last two are flat,
and the manual order is the list's (`manualKey`, Room schema v2), while the view is each
user's. The owner's phone removes 30-day-old tombstones and expired invites from RTDB. The
rules cover members, roles, invites, presence and the photo cleanup. Eat My Way serves the
invite page and `assetlinks.json` (its decision 458), and its privacy page follows.
Decisions 63–69. **What wakes the device:** nothing new. Every listener is attached only while
its screen is shown (decision 65). The only background work is still Phase 4's `OutboxWorker`.
Verified: 63 rules tests on the Firebase emulator (30 new). Loosening the editor check made 2
fail, and they caught a real hole (decision 68). 80 JVM tests (16 new: the sort views, the
Polish collation, placing, invite links, dates, write shapes). 39 instrumented tests on the API
35 emulator (12 new). `SharingTest` runs three accounts on a fake server that mirrors the
rules: an invited editor sees the whole list the moment they accept, with no act on the
owner's phone; an expired or made-up invite is refused; an e-mail viewer is refused a tick,
both on the phone and on the server; a removed member loses the list with a sentence, and one
who leaves loses it without one; ten simulated minutes of edits with one phone offline
converge with no duplicates and every tick kept; a tick arrives through a live listener, and
a watcher who is removed loses the list; the manual order reaches the other member while
their view stays theirs; the owner purges 30-day tombstones from RTDB. Four Compose flows:
someone else's tick with its initial, then „Kupione"; the three sort views; the dates;
read-only for a viewer. Making the live listener drop ticks failed the live test. The migration
test walks v1 → v2. **By hand on two physical phones, two Google accounts** (S10e = A, S23
Ultra = B, both on Wi-Fi, since the S10e has no SIM), against the real project once the rules
were published: the invite link opened the app on B (the host's App Link is not yet verified,
so the link was allowed for the app with `pm set-app-links-user-selection`); B joined and saw
the whole list; adding, ticking and reordering crossed both ways; „… ogląda" and the initial
on someone else's tick were seen on both. **Check latency (`TwoPhoneProbe`, 20 echoes): round
trip median 1028 ms, p95 1068 ms, max 1134 ms, so one way ≈ 514 ms median and ≈ 534 ms p95,
under 1 s.** About 300 ms of each leg is the send debounce (decision 58); the network part
matches Phase 0. **Airplane mode on B, edits on both:** after reconnecting, both phones' Room
held the same rows field for field (49 visible items, 43 ticked), both outboxes were empty,
there were no sync duplicates (every repeated name was typed separately on one phone), and no
tick was lost. **Not verified:** mobile data (neither phone was on it); the full ten minutes
offline (the offline edits spanned about two minutes; the ten-minute case ran only on the fake
server); photos seen by a new member (Phase 6 adds photos; the rules test covers the read);
the Linux machine (open question 2). **After the phase (2026-09-22):** the owner released Eat
My Way with the invite page and `assetlinks.json`. On the S10e, `pm verify-app-links
--re-verify` then gave `eatmyway.gorny.dev: verified`, and an `https://eatmyway.gorny.dev/bmw/i/…`
link opened `MainActivity` directly, with no browser. Only the debug key is listed so far
(decision 63). One mistake on the way: the first rules published were the Phase 4 file.
It was the one on GitHub, since the Phase 5 rules were not pushed yet. Sharing was refused
until the right file was published.

**Phase 6 done (2026-09-22).** Items can carry a photo. „Zrób zdjęcie" (the camera,
through a `FileProvider`) and „Wybierz z galerii" (the Photo Picker) in the edit sheet, with no
camera, storage or media permission declared. The image is scaled to at most 800 px, turned by
its EXIF orientation and encoded as WebP at the best quality that fits in 80 kB; the encoding
leaves no EXIF, so no location travels. The photo shows in its row as a thumbnail and opens full
screen with pinch-zoom, for a viewer too. It waits in `filesDir/photo-outbox` and is sent by
`PhotoWorker` to `/photos/{listId}/{itemId}`; the item's `photoAt` follows only once that write
is acknowledged. „Usuń zdjęcie" clears `photoAt` at once and removes the node after, unless
someone put a newer photo there. Photos are cached in `cacheDir/photos`, capped at 50 MB,
least-recently-used first, and a cached photo is never fetched again. Deleting a list removes
its photos at once; an item's photo goes with its tombstone after 30 days. The rules cover all
of it. Decisions 70–73. **What wakes the device:** `PhotoWorker`, one-shot and unique, with
`NetworkType.CONNECTED`, only after a photo was set or removed and not yet sent. Nothing
periodic; nothing else new.
Verified: 81 rules tests on the Firebase emulator (18 new: who writes a photo, the caps, the
five fields, the writer's own name, a newer `at`, a live item, a deleted list, removing one
photo or all). Loosening the `by` check made the „signed by its writer" test fail. 94 JVM tests
(14 new: the sizing search, the quality ladder, the EXIF swap, the cache's eviction, the photo
node and the write shapes). 59 instrumented tests on the API 35 emulator (20 new). They cover
the pipeline on real Android (a 12-megapixel JPEG with EXIF and GPS → 800 × 600, under 80 kB,
no EXIF chunk left; a sideways photo comes out upright; pure noise shrinks until it fits; the
app declares no camera, storage or media permission — read from the merged manifest at
runtime), the two caches (the cap holds over fifty photos, a cached photo is never fetched
twice, two rows asking at once fetch once, a waiting photo survives a restart and a half-written
one is forgotten) and six two-phone flows over the fake server (a photo reaches the other phone
and `photoAt` follows only the acknowledged write; a replacement wins and a removal never takes
a newer photo; a photo removed while it was being sent does not come back; a viewer sees photos
and cannot change them; a photo waits for its list's upload; deleting a list takes its photos,
as does a 30-day tombstone). Removing the guard that checks the outbox after an upload made the
„removed while it was being sent" test fail.
**By hand on the emulator:** the camera (a real camera app), the Photo Picker (2560 × 1600 PNG
→ 800 × 500, 4 kB), the thumbnail and the full-screen viewer.
**By hand on two physical phones, two accounts** (S10e = A, S23 Ultra = B) against the real
project: A photographed an item with the Samsung camera (a 1.9 MB JPEG → 450 × 800, 1.3 kB
WebP); it showed on B's row **3.8 s** after the camera's „OK" (the camera's own return and two
UI dumps included) and opened full screen there, upright. „Usuń zdjęcie" on A removed it from
both phones and from `/photos` with no refusal. B's cache held both photos (13 kB and 59 kB).
**Not verified:** the 50 MB cap on a phone (it was measured with a small cap on the emulator);
HEIF from a Samsung gallery (the picker gave a JPEG); a photo taken while offline and sent
later on a phone (the fake server covers it).
**Found on the way:** the first photo was refused (`permission denied at photos/…`) because the
project still ran the Phase 5 rules, and the app dropped it without a word. The rules were
published, and the phase adds one log line when a photo is refused, and a paragraph in
docs/DEPLOYMENT.md: publish Phase 6's rules **before** installing this build, because it also
refuses to delete a list. Seen once on the emulator and not reproduced: the edit sheet closed
itself when the camera returned. The test photo left on „mleko" in „Zakupy na sobote" is the
owner's to remove.

**Phase 8b („Moje produkty") was added to PLAN.md on 2026-09-23**, out of Phase 7's daily use
(decisions 80–81). It is not started.

**Phase 7 done (2026-09-23), all three acceptance criteria met.** The add bar has a mic. It asks for `RECORD_AUDIO` at the first
tap (with a sentence where Android says to explain, and a way to the app's settings after a
refusal), then opens „Dyktowanie": „Słucham…" with the words heard so far while speaking, and
the finished sentence as one editable line per item with the department it would go to.
„dwa kilo ziemniaków, mleko, masło i chleb" is four lines. A line is corrected in place („2 l
mleko" re-reads name, quantity and unit through Phase 3's `ItemParser`), its category is
changed on the chip, or it is dropped with „×". „Dyktuj dalej" says one more sentence into the
same sheet; „Dodaj wszystkie" is the only thing that adds; „Anuluj" drops everything. The
recognizer is `pl-PL`, free form, offline preferred, and it is created with the sheet and
destroyed with it. A phone with no recognizer gets no mic button. Decisions 74–77.
**What wakes the device:** nothing new. No worker, no service, no listener; the microphone is
on only while the sheet is open. No new dependency.
Verified: 114 JVM tests (20 new methods over ~75 utterances in `DictationTest`, written as the
Polish recognizer hands text over). 74 instrumented tests on the API 35 emulator (14 new): five
for what is asked of the recognizer (pl-PL, partial results, the offline preference and the
retry that drops only it, no extra that would return audio, every error code mapped, and
`RECORD_AUDIO` as the only new permission, read from the merged manifest at runtime) and nine
Compose flows over the real screens, with the utterances a phone would hear handed to the view
model: one sentence becomes four items with the right quantity, unit and category after one
tap; nothing reaches the list before that tap; a second utterance adds to the same sheet; a
line edited by hand and its category following the new name; a category changed on the chip; a
line removed; „Anuluj" leaving nothing and the next dictation starting empty; the partial words
shown while speaking; and the mic button present only when a recognizer is. 72 rules tests
still pass (Phase 7 changes no rules). Lint clean.
**By hand on the emulator (API 35 tablet, real on-device recognizer):** the mic appears in the
add bar, the system prompt „Allow Buy My Way to record audio?" appears on the first tap, and
after „While using the app" the sheet opens with „Słucham…" and Android's green microphone
indicator lit. „Zatrzymaj" ended it and, with no sound going in, the sheet said „Nie słyszę —
spróbuj bliżej mikrofonu." and offered „Dyktuj dalej" — the error path end to end on a real
recognizer.
**Found on the way:** the first CI run (35832044810) was red where the machine here was green.
CI's emulator is a phone and this one is a tablet, and four dictated lines pushed the sheet's
buttons off a phone screen. Now only the lines scroll (at most 320 dp of them) and the title,
the state and the buttons stay put. The same run showed the tests racing the emulator's own
recognizer, which really listens, so the sheet now takes its `VoiceSource` from the screen and
the test hands it one that opens no microphone; the test also grants `RECORD_AUDIO` through
`UiAutomation`, because an install for a test run grants nothing and the sheet refuses to
listen without it. Reproduced here by putting the tablet emulator at a phone's size
(`adb shell wm size 1080x2280`, `wm density 440`), where the suite is green again.
**On the owner's S10e (2026-09-23), with real speech:** „dwa kilo ziemniaków, mleko, masło i
chleb" was recognised **without any commas**, and the first build made two items out of it
instead of four. The dictionary now decides where one thing ends (decision 78), and the sheet's
buttons no longer collide on a phone (decision 79). Both are covered by new tests, and the build
went back on the phone to be said again.
**Offline dictation works (the owner, on the S10e, 2026-09-23).** The phone has no SIM, so with
Wi-Fi off it has no network at all, and dictation still turned speech into items. That closes
the phase's second acceptance criterion, „works with the phone offline when the Polish pack is
installed": the app asks with `EXTRA_PREFER_OFFLINE` on every attempt (decision 75), and the
recognizer answered without one. Nothing spoken leaves the phone.
**Not verified:** the network retry after a language error (no device here refuses Polish); a
phone with no recognizer at all (the rule is tested, the device is not); the Linux machine
(open question 2). **To do:** `docs/screenshots/list-checking.png` shows the add
bar without the mic, so it is one phase out of date; decision 47 leaves screenshots to the
owner on a real phone.

**Phase 8 done (2026-09-23), all three acceptance criteria met.** A shopping list shared out of
Eat My Way becomes a list here. Buy My Way is a share target for `text/plain`, and Listy's
overflow offers „Wklej ze schowka"; either opens Import, which shows what the text turned out to
hold, grouped under the departments it was printed under, with „×" on any line that should not
come. „Dokąd dodać" picks a list that exists or makes a new one named after the title's date
range („tydzień 15.09 – 21.09"), and one tap writes the lot as a single batch of ops. A line
that names something the list already holds, in the same unit, grows that item's quantity
instead of adding a second row, and one sitting in „Kupione" comes back (decision 36). A heading
the list has no category for becomes one; a line that had no heading is categorised as a typed
one would be. Any other text — anything at all — becomes one item per non-empty line. Decisions
82–87. **What wakes the device:** nothing new. No worker, no service, no listener; the clipboard
is read only on that one tap, and the import writes through the same outbox as typing.
No new dependency.
Verified: 172 JVM tests (50 new: 32 for the format in `EatMyWayImportTest`, over a week's list
in `src/test/resources/eatmyway-export.txt`, and 18 for the merge rules in `ImportPlanTest`).
103 instrumented tests on the API 35 emulator (29 new): 20 in `ImportRepositoryTest` (the batch
reaching Room at one `at`, the departments, the order within one, a new category and its place
in the walk order, a heading the list already has, the name remembered for the add bar, an
import that touches nothing it did not name) and 9 Compose flows in `ImportFlowsTest` (the
preview grouped under its headings; **nothing reaching any list before the button**; one tap
making the list and filling it; the new list's name changed first; a line removed in the
preview never arriving; an existing list chosen instead; the same text twice summing; plain text
as one line per name; a text that names nothing offering no button). 72 rules tests still pass —
Phase 8 changes no rules, because an import writes ordinary items and categories through the
ops the rules already cover. Lint clean.
**The three acceptance criteria:** a week's list lands in the right category with its quantity
in one tap after the preview (`aWeeksListLandsInItsDepartmentsWithItsQuantities`,
`oneTapMakesTheListAndFillsIt`); the same text twice sums and adds no duplicate
(`theSameTextTwiceSumsQuantitiesAndAddsNoDuplicate`, and the same on screen); any text becomes a
line-per-item list and never a crash (`plainTextIsOneItemPerLine`, and `junkIsReadAsNamesAndNeverThrows`
over ten shapes — an empty bullet, dashes alone, control characters, 5000 letters, emoji, HTML,
JSON).
The emulator here is a tablet, so it was put at a phone's size for the run (`wm size 1080x2280`,
`wm density 440`), which is what Phase 7 learned to do (decision 79). The suite reports 104
entries: 103 tests and `TwoPhoneProbe`, which skips itself without its argument (decision 69)
and which AGP writes into the XML as a failure while the task still passes — the same entry CI
has been green with since Phase 5.
**CI: run 35868841823 red, then run 35880120930 green** (all three jobs: lint + unit tests +
build, the rules emulator, and the instrumented emulator). What the red run caught, and why it
was right, is below.
**The first CI run (35868841823) was red where this machine was green, and it was right.**
CI's emulator has no hardware profile, so it is **360 × 640 dp** — shorter than the 411 × 731 dp
this machine's tablet was put at, and shorter than any phone the app has been run on. Two of the
new Compose tests broke on it:
- `theSharedListIsShownGroupedUnderItsDepartments` asserted that all three lines were displayed
  at once. On a 640 dp screen the third is below the fold, and a `LazyColumn` does not even
  compose it. The test now scrolls the list to each line first (`performScrollToNode`), which is
  what a person does, and the same for the „×" it taps. Reproduced here by putting the emulator
  at `wm size 1080x1920`, `wm density 480`, where it failed with the same sentence.
- `theNewListsNameCanBeChangedBeforeImporting` timed out waiting for the import. It types into
  the new list's name, and a CI emulator has no hardware keyboard, so a soft one opens over the
  button below. The **screen** was changed rather than only the test: the name field now carries
  `ImeAction.Done` and clears focus on it, so „Gotowe" puts the keyboard away — a real phone
  wanted that anyway — and the test taps it as a person would.
The header was also made one row shorter (source and count share a line), because on a 640 dp
screen every line the header does not take is a line of the list that can be seen.
**Found on the way (a lesson about this machine, not about the code):** the first two full runs
were red, and neither was the phase's doing. The first failed a Phase 7 dictation test on a
5 s Compose timeout while `testDebugUnitTest` was running beside it — the instrumented suite
must have the machine to itself. The second aborted inside the new `oneTapMakesTheListAndFillsIt`
after ~90 tests, on an emulator that had been up since 21 September and by then was drawing
frames in 6 s (`app_time_stats: avg=6147ms`); no assertion failed and no process crashed, the
runner simply never got a verdict. Both passed when run alone. The suite was then run on a
cold-booted emulator with nothing else on the machine: **104 entries, one probe skip, zero
failures**. The import test's wait was raised from 10 s to 30 s anyway — the import is a few
database writes, so the wait is only about how loaded the machine is, and waiting longer costs
nothing when the work is already done. A **third** full run, at CI's 360 × 640 dp, was green on
all nine import flows and flaked once more on a Phase 7 dictation test, which then passed alone
— the same 5 s timeout, the same machine, and a test CI itself has never failed. This emulator
under a full suite is simply not a reliable witness; **CI is**, which is why the phase is not
called done until its run is green.
**Not verified:** sharing from the real Eat My Way app on a phone — the fixture is a
reconstruction, not a capture (decision 84), so the owner's first real share is the last check;
the Linux machine (open question 2).

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

### 2026-09-22 — Phase 4 (Google sign-in & cloud persistence)

53. **Phase 4 libraries.** From the stack list: `androidx.credentials:credentials` with
    `credentials-play-services-auth`, and `googleid` (Sign in with Google), and
    `androidx.work:work-runtime-ktx` (the `OutboxWorker`). Also `androidx.lifecycle:lifecycle-process`
    at the Lifecycle version already pinned (2.11.0): `ProcessLifecycleOwner` is how the app
    knows it left the foreground, which starts the 30 s grace before `goOffline()`. Not added:
    `kotlinx-coroutines-play-services`, which decision 15 left to this phase. A 15-line
    `Task.await()` does the same. The Web client id (`serverClientId`) is read from
    `R.string.default_web_client_id`, which the google-services plugin generates from the
    committed `google-services.json`, not from `gradle.properties` as PLAN.md's *Google
    identity* says. That is one copy of the id instead of two. The rules tests get their own
    `firebase/package.json` with `firebase-tools` (the emulator and `emulators:exec`),
    `@firebase/rules-unit-testing` and its peer `firebase`, all dev-only, on Node's built-in
    `node:test` runner (no mocha). They run under a `demo-` project id, so they need no login
    and never touch the real project.
54. **Catching up reads items by a server-stamped `changedAt`, not by `updatedAt`.** PLAN.md
    queries `items` by `updatedAt` from `seenUpTo`. But `updatedAt` moves only with an item's
    content: a tick or a delete leaves it where it was, so a device would never learn of them,
    and it is a device's clock, not the server's. So every item write also sets
    `changedAt: ServerValue.TIMESTAMP`, the rules require it to be `now`, `items` is indexed on
    it, and `seenUpTo` is the largest `changedAt` applied, which is server time, as the data
    model says. The meta and the categories are small and are read whole.
55. **In Phase 4 a list's owner is `meta/ownerUid`; there is no members node yet.** Task 5 says
    to upload a private list "with the owner as the only member". But a list with a members
    node is a *shared* list (`NodeCodec`, and PLAN.md: „Udostępnij" creates that node). So
    Phase 4 writes `meta.ownerUid` and `/userLists/{uid}/{listId} = "owner"`, and the rules
    take ownership from `meta.ownerUid`. Phase 5 adds members and roles on top.
56. **How a change reaches RTDB, and what the rules enforce.** Each outbox op becomes one
    multi-path update of the field group it changes (plus `changedAt`), and its outbox entry is
    removed when the write is acknowledged. A list that is not in RTDB yet (created here, or
    adopted at sign-in) goes up whole, in one update, and the ops queued for it are dropped
    because the upload already carries them. A write the rules reject makes the device read
    that node, merge it into Room and drop the op: it adopts the newer state. The rules,
    per node: every stamp (`updatedAt`, `checkedAt`, `deletedAt`, `clearedAt`) only moves
    forward; a changed field group needs a newer stamp; `createdAt`/`createdBy` are
    written once; `ownerUid` never changes; a tombstone is final (content and tick frozen);
    nothing is written under a deleted list; unknown fields are rejected; strings are capped.
    The device trims text to the same caps, so the server never refuses a local change for
    its shape. Two gaps are accepted. Equal stamps from two devices: the server keeps the later
    write, where the merge would pick by actor. That needs the same millisecond on two
    phones. And the rules cannot compare arrays, so a change of `categoryOrder` is guarded
    only by `updatedAt` moving forward. Deleting a list writes `meta.deletedAt` and removes
    its items and categories in the same update (decision 36). The meta stays as the
    tombstone, and the owner's device removes it and the `/userLists` entry 30 days later,
    when a catch-up finds it.
57. **Sign-in, a lost session, another account.** Credential Manager → `GoogleIdTokenCredential`
    → `FirebaseAuth.signInWithCredential`. The Google ID token is passed from one call to the
    next and never stored or logged. Then `/users/{uid}` (`name`, `email`, `photoUrl`,
    `updatedAt`) and `/emailIndex/{sha256(lower-case email)} = uid` are written. The uid and
    email of the signed-in account are kept in DataStore (an id, not a token). That is what
    makes decision 23 work: when the Firebase session is gone without a sign-out
    (`FirebaseAuthInvalidUserException`, or no current user), the app says „Zaloguj się
    ponownie", keeps every list, and keeps making ops under that uid. Signing in with a
    different account while the phone holds another account's lists is refused with a
    sentence that says why and what to do. Sign-out warns when changes are still unsent, then
    clears Room, DataStore, the Firebase session and Credential Manager's state. On sign-in,
    every list without an owner is adopted: it gets the uid as owner, and as the author of
    everything done while signed out. The rules cannot hash, so they cannot check that an
    `/emailIndex` key really is the writer's email. A user could claim another person's
    hash. That only blocks an e-mail invite to that person, and the link still works.
    Recorded as open question 10 for Phase 5.
58. **The connection and the background (what wakes the device).** The RTDB connection is on
    only while the app is in the foreground (plus 30 s) or while an `OutboxWorker` runs. It is
    counted, so the two do not switch each other off. In the foreground: every list is
    caught up at most once per 30 s, and the outbox is flushed on every change. When the app
    goes to the background with unsent changes, one unique one-shot `OutboxWorker` is
    enqueued (`NetworkType.CONNECTED`, exponential backoff). It goes online, sends, and goes
    offline. **That worker is the only thing Phase 4 adds that can wake the device.** It
    runs only when changes were left unsent, and only until they are sent. No periodic work
    (Phase 9), no listener, and no Firebase disk persistence: Room is the truth.
59. **`/users/{uid}/prefs` holds three things, each last-writer-wins by its own stamp.**
    `defaultOrder` and `listOrder` (`{value, updatedAt}`; the home screen's order moves here,
    as decision 44 said), and `categoryMemory/{folded name}` (`{name, categoryId, at,
    changedAt}`). The category memory is the `name_history` table: the category each name
    was last filed under. So a correction made on one phone is used on the other, and so is
    the autocomplete history. A catch-up reads the memory entries by `changedAt` since the
    last read.
60. **No household allow-list (owner, 2026-09-22, answers open question 9).** Any Google account
    that signs in through our signed APK can keep its own lists. Spark cannot bill, and a
    rebuild with another key cannot sign in (decision 34). An allow-list later would change
    only the rules.
61. **The owner pastes the rules into the Firebase console (owner, 2026-09-22).** The file is
    `firebase/database.rules.json`, and `firebase deploy --only database` stays documented as
    the alternative. The CI `rules` job tests the file against the emulator, never the real
    project.

### 2026-09-22 — After Phase 4

62. **Item dates in the edit sheet, and three ways to sort a list (owner, 2026-09-22).** Asked
    after Phase 4. Neither existed, so they were added to PLAN.md, to **Phase 5** (tasks 8–9
    and two acceptance criteria). Phase 5 rewrites the rules anyway, and the manual order
    needs a new item field that the rules must accept.
    - The edit sheet shows „Edytowano …" (`updatedAt`) and, for a bought item, „Kupiono …"
      (`checkedAt`). Both fields already exist, so no data change is needed.
    - „Sortowanie": „Według działów" (today's view, the default), „Alfabetycznie", „Ręcznie".
      The last two are one flat list with no category headings. „Kupione" stays at the bottom.
    - Chosen defaults, open to change when Phase 5 starts: the **view is personal**, per list
      (like the home screen's order, decision 44), so one member's „A–Z" does not change
      another's screen. The **manual order belongs to the list**, like its department order:
      a new content field `manualKey`, last-writer-wins with the rest of the content. That
      means Room schema v2 and its migration. The existing `sortKey` stays the order within
      a department.

### 2026-09-22 — Phase 5 (sharing & real-time)

63. **The owner's answers at the start of Phase 5 (2026-09-22).**
    - **The invite link's host is prepared in the Eat My Way repository, and the owner releases
      it.** Phase 5 adds to `eat-my-way` (its `dev` branch) a static page for `/bmw/i/*`, a
      Caddy rewrite that serves it for every token, and `/.well-known/assetlinks.json`. That
      file names `dev.gorny.buymyway` with the debug SHA-256 of each machine for now. The
      release key does not exist until Phase 10, which adds its SHA-256. The page's button
      opens `buymyway://i/<token>`, so the link works even before Android has verified the
      App Link. Nothing sends e-mail: the owner's server cannot, and nothing needs it.
    - **`/emailIndex` is keyed by the e-mail address itself (answers open question 10).** The
      key is the lower-case address with `.` written as `,` (RTDB keys cannot hold a dot), and
      the rules accept a write only when the key is the writer's own verified Google address
      (`auth.token.email`). Nobody can claim someone else's address. An address containing
      `$ # [ ] /` gets no entry, so that person can be invited by link only. The sha256 entries
      written in Phase 4 are left unused and can be deleted in the console. The index can be
      read one key at a time by any signed-in user, never listed.
    - **„Usuń moje dane" is built in Phase 9 (answers open question 8)**, together with
      `/fcmTokens`, so one action removes everything.
    - **The two-phone checks are done by hand at the end of the phase** (S10e and S23 Ultra,
      two accounts): check latency, airplane mode, the invite link.
64. **How sharing is modelled.**
    - **Membership changes are not ops.** Inviting, accepting, changing a role, removing a
      member, „Uczyń prywatną" and leaving a list are made online, as one acknowledged
      multi-path update each, and the screen says so when there is no connection. Membership
      is owned by the server (the owner writes it), so an outbox and a merge would only add
      ways for it to go wrong. Sharing needs a network anyway. Room's `members` table and
      `lists.shared` are a copy of what was last read.
    - **Roles in the rules.** Reading a list: its owner (`meta/ownerUid`) or anyone under
      `members`. Items and categories: the owner or an editor. The meta's name, category order
      and `clearedAt`: the owner or an editor (PLAN.md *Storage layout*). `ownerUid`,
      `createdAt` and `deletedAt`: the owner only. Members and invites: the owner only, except
      that a signed-in user may add *themselves* with a valid invite (unexpired, for this list,
      with the invite's role, and with the token written in the member node as `invite`), and
      may remove themselves.
    - **„Opuść listę" (an addition).** On a list shared with them, a member's card menu shows
      „Opuść listę" instead of „Usuń", which only the owner may do. It removes their member
      entry and their `/userLists` entry, and the list leaves the phone.
    - **`/userLists/{uid}/{listId}` holds the role.** It is written by the list's owner for
      anyone (an e-mail invite, a role change, a removal) or by the user for themselves, and
      the value must equal the role in the members node written with it (or `owner` for the
      owner).
    - **The invite carries `listName` and `byName` too**, beyond PLAN.md's `{listId, role, by,
      expiresAt}`, so the person accepting sees whose list it is before they are a member and
      can read it. The token is 128 random bits in URL-safe base64 (22 characters). An invite is
      valid for 7 days and can be accepted by several people, each once. The owner's device
      removes expired invites.
    - **A member's name, e-mail and photo are readable by any signed-in user who knows their
      uid** (`/users/{uid}/name|email|photoUrl`; `prefs` stays private). The rules cannot
      express "shares a list with me", and a uid is only ever seen by people who share a list
      with that user. That is what shows „Ania ogląda" and „Kupiono … · Ania".
    - **`/photos` gets only a rule for removing a photo in Phase 5** (owner and editors), so
      that the tombstone cleanup can remove photos with their items. Phase 6 writes the rest.
65. **What is attached while the app is open (refines the *Battery policy* line on
    listeners).** On the list screen: `meta` and `members` as values, `categories` as
    children, `items` as children of the query `changedAt ≥ seenUpTo`, and `presence`. On the
    home screen, besides `/userLists/{uid}`, only the `presence` node of each *shared* list,
    for „Ania ogląda". All of it only while the screen is shown and the connection is held
    (foreground plus the 30 s grace, decision 58). Presence is written as the server time when
    a list screen opens, removed when it closes, and removed by `onDisconnect` otherwise.
    Nothing new runs in the background, so nothing new wakes the device.
66. **RTDB tombstones are removed by the owner's device during a catch-up.** For each list it
    owns, it removes the item and category nodes that `Merge.purgeable` names (gone for more
    than 30 days) and their `/photos` nodes in one update, and then forgets them in Room. So
    for a list that is in RTDB and owned here, the daily local sweep leaves the purge to
    sync. Otherwise the node would stay in RTDB for ever. Other members' phones purge only
    their own Room. Expired invites are removed in the same pass.
67. **The manual order: `manualKey` may be empty.** An item without one is "not placed yet".
    „Ręcznie" shows the placed items by key, then the unplaced ones in department order. Items
    are placed (a batch of `item.put`s, appended in department order) when „Ręcznie" is
    chosen and before a drag in that view, never just because a screen is looking. So two
    phones never write for nothing. An item added in „Ręcznie" gets the next key. One added
    in another view has none and is placed at the end the next time. A viewer never places
    anything. `manualKey` is part of the content group: last-writer-wins with it, in the rules'
    equality checks, and in Room schema v2 (a nullable column, so the migration only adds
    it). The chosen view is per user and per list: DataStore, then
    `/users/{uid}/prefs/listSort/{listId}` (`{value, updatedAt}`).
68. **Found while writing the Phase 5 rules tests: an author could stay the previous one.**
    `updatedBy`, `checkedBy` (and the meta's and a category's `updatedBy`) accepted "the writer,
    or unchanged". So an editor could move `updatedAt` forward and leave `updatedBy` naming
    someone else, and put their edit under another person's name. The hole was already in
    Phase 4, but with one user per list it did not matter. Now an author may stay unchanged only
    while its stamp does too; any newer stamp must carry `auth.uid`. The test „an editor
    cannot write as someone else" failed before the fix and passes after it.
69. **Three smaller things Phase 5 needed.**
    - **The owner's invite index is `/users/{uid}/invites/{token}` (`{listId, expiresAt}`).**
      `/invites` cannot be listed, by design (a token is the secret), so without an index the
      owner's phone could not find its expired invites to remove them (decision 36). It lives
      under the owner's own node, which only they read.
    - **A refused profile write no longer stops sync.** `/users/{uid}` and the e-mail index
      are written at the first sync after sign-in. If the rules ever refuse that (an address the
      index cannot hold, say), sync logs it and goes on, instead of failing every time.
    - **The two-phone check latency is measured by a probe, not by eye.**
      `androidTest/.../probe/TwoPhoneProbe.kt` runs inside the installed, signed-in app on
      both phones: A ticks „ping N", B answers with „pong N", A times the round trip (the echo
      of decision 16). It is skipped without its argument, so CI never runs it. How to run it
      is in docs/DEVELOPMENT.md.

### 2026-09-22 — Phase 6 (photos)

70. **No image library: an in-house cache instead of Coil (PLAN.md Phase 6, task 3).** A
    photo reaches the phone as base64 inside an RTDB node, not as a URL. So Coil would need a
    custom fetcher, and it would still bring its own disk cache and network stack to decode
    ~80 kB WebPs that `BitmapFactory` already decodes. The in-house part is two small caches.
    One is on disk, in `cacheDir/photos`, one file per `{itemId}-{photoAt}`, capped at 50 MB
    and trimmed oldest-used first. The other is in memory, for decoded bitmaps, by byte size.
    A cached photo is never read from RTDB again. Nothing new on the dependency list:
    `FileProvider` (the camera's output `Uri`) is in `androidx.core`, which Activity already
    brings; base64 is `java.util.Base64` (API 26+, and the JVM tests can use it). EXIF: see
    decision 73. The full-screen viewer's pinch-zoom is written on `detectTransformGestures`.
71. **How a photo is made and sent (tasks 1–2).**
    - **Made on the phone:** the camera writes to a file under `cacheDir/camera` through the
      `FileProvider`, and the gallery is the Photo Picker. Neither needs a permission, so the
      manifest declares no camera or storage permission. The image is decoded at a power-of-two
      sample size, scaled so its longer side is at most 800 px, turned by its EXIF
      orientation, and encoded as WebP. The quality steps down from 80 until the file is at
      most 80 kB (81 920 bytes). If quality 30 is still too big, the image shrinks by 15 % and
      the steps start again. Re-encoding a bitmap writes no EXIF, so location and camera
      data never leave the phone.
    - **Sent by `PhotoWorker`:** until it is in RTDB, a photo waits in `filesDir/photo-outbox`,
      not in Room (so no schema change), and the phone shows it from there. The worker writes
      `/photos/{listId}/{itemId}` = `{webp, w, h, by, at}`. Only once that is acknowledged
      does it set `photoAt = at` on the item, with an ordinary `item.put` through the outbox,
      so another phone never sees a `photoAt` whose photo is not there yet. The bytes it sent
      go into the cache, so the phone that took the photo never downloads it back. A photo on
      a list that is not in RTDB yet (signed out, or not uploaded yet) waits. After a flush
      that uploaded a list, sync starts the worker again.
    - **Removing** writes `photoAt = null` at once, as an `item.put`. The worker then removes
      the node, but only if its `at` is not newer than the photo removed: a photo that someone
      else put there in the meantime stays. **Replacing** overwrites the node. The rules let
      `at` only move forward.
    - **The edit sheet's „Zapisz" no longer writes `photoAt`**: the repository keeps the
      stored one. Otherwise a sheet opened before an upload finished could put the old
      value back.
    - **A viewer** sees photos (thumbnail and full screen) and cannot set or remove them.
    - **Sign-out** empties the photo outbox and the cache with the rest of the account's data.
    - **What wakes the device:** `PhotoWorker`, one-shot and unique (`APPEND_OR_REPLACE`), with
      `NetworkType.CONNECTED`, and only after a photo was set or removed and not yet sent. It
      holds the connection while it sends, as `OutboxWorker` does. Nothing periodic.
      Downloads happen only for a row that is on screen, while the list is open.
72. **The `/photos` rules, and the rest of the orphan cleanup (task 4).** A photo node is
    written by the list's owner or an editor, and only for an item that exists and is not
    deleted, under a list that is not deleted. It has exactly `webp` (a non-empty string of
    at most 110 000 characters, which is 80 kB in base64 with room to spare), `w` and `h`
    (1–800), `by` (the writer's uid) and `at`, which never goes back. Members read it, as in
    Phase 5. Removing a single photo stays with the owner and editors. The owner can also remove a
    list's whole `/photos/{listId}`. **Two gaps from Phase 5 are closed:** deleting a list now
    removes its photos at once, as decision 36 says (Phase 5's `list.delete` removed only the
    items and categories), and so does the final removal 30 days later. Photos of items gone
    for 30 days were already removed with them (decision 66). **Not done: a scan for orphan
    nodes.** Listing `/photos/{listId}` would download every photo, and the SDK has no
    shallow read. An orphan can only come from a replace and a remove made at the same moment
    on two phones. It then stays until its item is removed for good.

73. **`androidx.exifinterface` 1.4.2 is added, for the EXIF orientation (changes decision 70).**
    The plan was the framework's `android.media.ExifInterface`. Lint refuses it (`ExifInterface`,
    an error under `warningsAsErrors`): the platform class has known security bugs on older
    Android versions, and the AndroidX one is the maintained copy. The EXIF parser reads
    whatever image a user picks, so this is the one lint check that should not be suppressed
    (decision 29 keeps every check but the version ones). It is one AndroidX artifact, and it
    brings only `annotation` and `jspecify`. It also reads orientation from more formats (WebP,
    PNG, HEIF) on every API level. The app uses it only to read `TAG_ORIENTATION`, and the
    tests use it to write the EXIF they check is gone.

### 2026-09-23 — Phase 7 (voice input)

74. **No new dependency: dictation is `android.speech.SpeechRecognizer` and one pure parser.**
    `core/voice/Dictation.kt` (PLAN.md names that path) splits an utterance and hands each part
    to Phase 3's `ItemParser`, so a dictated „2 kg ziemniaki" and a typed one give the same
    fields (decision 47 asked for exactly this). What dictation adds over typing: „i" / „oraz" /
    „jeszcze" / „plus" / „a także" between items, what a person says before the list („kup
    jeszcze…", „potrzebuję…", „poproszę…") and after it („proszę", „też"), „dwa razy mleko",
    and the full stop the recognizer puts at the end. `data/voice/VoiceRecognizer.kt` wraps the
    framework: `pl-PL`, free form, partial results, one alternative, and nothing that asks for
    the audio itself. No cloud speech library and no Gemini, as decision 11 says.
75. **Offline is preferred, with one retry over the network.** `EXTRA_PREFER_OFFLINE` is set on
    every attempt, so a phone with the Polish pack never sends the speech anywhere. PLAN.md
    says „when the device has the language pack", and there is no cheap way to ask: the
    answer would be a `ACTION_GET_LANGUAGE_DETAILS` broadcast that says which languages exist,
    not which are downloaded. So the app asks offline first and, if the recognizer answers
    `ERROR_LANGUAGE_NOT_SUPPORTED` or `ERROR_LANGUAGE_UNAVAILABLE` (12 and 13, written out
    because reading the API 31 constants is a lint error under minSdk 26), asks once more
    without the preference and stops preferring offline for the rest of that screen. Every
    other error becomes one Polish sentence, „Nie słyszę — spróbuj bliżej mikrofonu." among
    them. **Not verified:** that retry, because both devices here answer in Polish offline
    (the emulator) or were not available (the owner's phones).
76. **The review sheet is one editable line per item, not a chip per item, and the stop button
    lives in the sheet.** Two small deviations from PLAN.md:
    - Task 3 says „parsed chips with category, edit inline". The category *is* a chip with a
      menu, but the item itself is a text field holding the line as the add bar would read it
      („2 kg ziemniaki"). A chip cannot be edited inline without turning into a field anyway,
      and this way one field corrects a misheard name *and* a wrong quantity, through the same
      `ItemParser` the add bar uses. A „×" drops a line.
    - *Voice input* says „the mic button stays in the add bar; a second tap while listening
      stops". The sheet covers the add bar while it is open, so the second tap is „Zatrzymaj"
      in the sheet, in the same place where it then reads „Dyktuj dalej". The add bar's mic is
      what opens the sheet, and it is not shown at all when
      `SpeechRecognizer.isRecognitionAvailable` is false (task 4) or for a viewer (who has no
      add bar).
    Dictation never writes to the list: „Dodaj wszystkie" is the only thing that adds, and it
    adds through the same `repo.addItem` as typing, so reviving a bought item (decision 36) and
    the manual order (decision 67) work the same way.
77. **What the microphone costs, and what wakes the device: nothing new.** `RECORD_AUDIO` is
    asked for at the first tap on the mic, never at start, with a one-sentence rationale where
    Android says the user has been asked before, and a refusal leaves a sentence with a way to
    the app's settings. The recognizer is created when the sheet opens and destroyed when it
    closes, so the microphone is on only while that sheet is on screen. The manifest also gains
    `<uses-feature android:name="android.hardware.microphone" android:required="false">` (a
    phone without one still installs) and a `<queries>` entry for
    `android.speech.RecognitionService`, without which Android 11+ reports no recognizer at all
    and the mic button would hide itself everywhere. **No background work, no service, no
    network of ours:** the speech goes to the system recognizer and the app keeps no audio
    (`onBufferReceived` is ignored, nothing is written to disk).

78. **The dictionary says where one dictated thing ends (found on the owner's S10e,
    2026-09-23).** Google's Polish recognizer writes **no commas at all**: „dwa kilo
    ziemniaków, mleko, masło i chleb" arrives as „dwa kilo ziemniaków mleko masło i chleb", so
    the phase as first written made two items („2 kg ziemniaków mleko masło" and „chleb"), not
    four. Splitting on „i" alone is not enough, and speech gives nothing else to split on. So
    `Categorizer.knownNameLength` answers „how many words from here name one thing I know", and
    `Dictation` cuts wherever a known name follows a name already read, or a quantity followed
    by one („mleko dwa chleby"). The 662-name dictionary (decision 40) was already there; 241 of
    its entries have several words, which is what keeps „mleko kokosowe", „papier toaletowy" and
    „sok pomarańczowy" whole, because the longest entry wins.
    - **The match is stricter than for categorising**: a spoken word may carry at most two
      letters beyond the entry („ziemniaków" ↔ „ziemniaki"), where categorising allows three.
      Three would let a derived adjective start an item („pomarańczowy" ↔ „pomarańcze"), and in
      Polish an adjective belongs to the noun before it.
    - **A word it does not know never starts an item**, so „chleb wiejski" and „mleko od Zosi"
      stay whole. The cost is the other way round: two things it does not know, said with
      nothing between them, stay one line — and that is what the review sheet is for.
    - Nothing is asked of the network, and the dictionary is the one already read from assets.
      The user's own names (`name_history`) are **not** consulted yet; that would help for
      names outside the dictionary and is a small addition later.
79. **The sheet's mic button takes its own row.** On the S10e the three buttons did not fit
    across the screen and „Dodaj wszystkie" was squeezed into a circle with its label broken
    into „Doda j wszy stkie". „Dyktuj dalej" / „Zatrzymaj" is now full width above, with
    „Anuluj" and „Dodaj wszystkie" on the row below.

80. **Dictation also cuts at the names this phone has already seen (owner, 2026-09-23).** The
    `name_history` table is the names the user has typed or dictated before, and it costs
    nothing to use: `NameIndex` is the matcher pulled out of `Categorizer`, and
    `AppContainer.knownNames()` now asks the dictionary and the 500 most-used of those names,
    taking whichever knows more words. So „chleb wiejski" becomes a thing of its own once it
    has been bought once, without any new storage, rules or screen. Used for **cutting only**,
    never for the department, which keeps its own memory (decision 59). A typo in the history
    is mostly harmless here, because the match is by stem: a stored „mlekoo" still meets a
    spoken „mleko" and behaves like the right word. What it cannot do is let the user curate
    anything, which is what Phase 8b is for.
81. **„Moje produkty" is per account and never automatic (owner, 2026-09-23), added to
    PLAN.md as Phase 8b.** Asked at the end of Phase 7: should new products go to a list in
    Firebase that everyone edits, or to an individual one? Chosen: individual, in
    `/users/{uid}/prefs`, which is already private to that account and already has its rules.
    A list every signed-in user may edit is a shared namespace with no review and no way to
    undo someone else's „ser zólty" except by editing their entry; the curated shared list
    the owner wanted already exists as `products-pl.json` in this repository, changed in a
    commit and shipped with the next release. A per-list vocabulary (editable by that list's
    editors) was considered and dropped: the word would then help only on that list, and not
    on the user's own private ones. Nothing is learned automatically — the user taps
    „Zapamiętaj" — because automatic learning is how a dictionary fills with typos.

### 2026-09-23 — Phase 8 (import from Eat My Way)

82. **The parser's package is `core/imports`, not PLAN.md's `core/import`.** `import` is a
    Kotlin keyword, so a package of that name has to be written in backticks at every use site
    and is awkward for anything reading the class from Java. One letter is cheaper. The file is
    `EatMyWayImport.kt` and the class `EatMyWayImport`, exactly as the plan names them.
83. **Phase 8 adds no dependency.** The parser is Kotlin's own regexes over the format's
    vocabulary; the share target is a manifest intent filter (`ACTION_SEND`, `text/plain`, and
    nothing else — no file, no image, no stream); the clipboard is the framework's
    `ClipboardManager`, read through `getSystemService` only when „Wklej ze schowka" is tapped.
    Compose's `LocalClipboard` is not used: the paste is a one-off read, not state a screen
    watches, and the framework class does not move between Compose versions.
84. **The „real export" test fixture is a faithful reconstruction, not a capture.** PLAN.md task
    1 asks for „30+ tests including a real export". No text shared out of the real app exists in
    or beside this repository, so `app/src/test/resources/eatmyway-export.txt` is built from the
    closest real things: the **real ingredient names and real departments** of the owner's 27
    Eat My Way recipes (the same 65 names decision 41 already took from a backup that stays out
    of the repository), printed in the **exact shape** `formatShoppingList` writes — title,
    blank line, department headings in shop-walk order, `• <name> — <amount>` with the
    „(n g)" suffix where Eat My Way would add one. What it cannot prove is that the real app
    still writes that shape; the owner's first real share is what closes that, and a captured
    text can replace the file with no code change.
85. **An import merges on the name and the unit, and measure forms count as one unit.** PLAN.md
    says „sum same name+unit". The name is compared folded (`TextKey`), so „Ziemniaki" meets
    „ziemniaki". The unit is compared through `EatMyWayImport.unitKey`, which folds Eat My Way's
    household measures to the one word they mean, so „1 ząbek" and „2 ząbki" are three cloves
    and not two rows — its `MEASURE_NAMES` table is the reference, and only the words are the
    same, no code is shared. „2 szt." never meets „200 g". A line with no unit meets only
    another with no unit. **The stored unit stays as the text wrote it**, so a row still reads
    „2 ząbki" rather than a dictionary form; when two forms merge, the one already on the item
    is kept, so „1 ząbek" plus „2 ząbki" reads „3 ząbek". That is bad Polish in a case that
    needs two different printings of one measure in one list, and it is one tap to correct.
    Two more rules the plan does not spell out: „no quantity" is not zero, so an uncounted line
    leaves a counted item alone rather than emptying it; and a number outside what an item may
    hold („99999 g") is still an amount, taken off the name and dropped, rather than left to
    read „Ryż — 99999 g".
86. **What the preview offers, and what it deliberately does not.** It shows every line grouped
    under its heading with its amount, „×" removes one, „Dokąd dodać" picks the target and a new
    list's name can be typed over. It does **not** edit names, quantities or departments: the
    list screen and the edit sheet already do that, on real items, and a second editor here
    would be a second place for the same rules to drift. Only lists this user may add to are
    offered (`observeEditableLists`), so a viewer's list is never proposed and then refused.
    Nothing is written before the button: the text is parsed in the view model and the first
    write of any kind is the one batch of ops.
87. **The shared text is held in a flow, and taken exactly once.** `MainActivity` puts what
    `ACTION_SEND` carried into an `imports` flow, as it already does for an invite link
    (decision 57's pattern), and the navigation graph opens Import when it is not null. The
    import screen's view model takes the text with `getAndUpdate { null }` when it is built —
    the one place that cannot miss it — so leaving the screen and coming back cannot import the
    same text twice, and the navigate does not fire again.

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
8. **"Usuń moje dane" in the app?** Phase 9 (decision 63). The privacy page can only offer deletion by email until
   the app has it. A Settings action that deletes the user's lists where they are the owner,
   leaves the others, and removes `/users/{uid}`, `/emailIndex`, `/fcmTokens`,
   `/userLists` and their photos is small once Phase 5 exists. It is proposed for Phase 5
   or 9, and the owner decides when that phase starts.
9. **Limit sign-in to the household?** The RTDB rules (Phase 4) could accept writes only
   from uids listed under an `/allowed` node that only the owner can edit in the console.
   That would stop a stranger's Google account from using the quota even through our own
   APK, but every new user would need a manual step. ~~Phase 4 decides.~~ No allow-list
   (decision 60).
10. ~~**Can `/emailIndex` be squatted?**~~ Answered by decision 63: it is keyed by the e-mail
    itself, checked against `auth.token.email`. The original question: the rules cannot hash, so they cannot check that a key
    is the sha256 of the writer's own email (decision 57). Phase 5, which reads the index for
    e-mail invites, decides whether that matters. One option: key by the email itself (dots
    encoded) and compare with `auth.token.email`.
11. **Two of the README's screenshots are a phase behind.** `docs/screenshots/list-checking.png`
    shows the add bar without the mic that Phase 7 put there, and after Phase 8 `lists.png`
    shows Listy's top bar without the „⋮" that „Wklej ze schowka" lives in. Decision 47 leaves
    screenshots to the owner on a real phone (`adb exec-out screencap`), so they are theirs to
    re-take; `list-bought.png` is unchanged. Screenshots of „Dyktowanie" and of the import
    preview would be worth adding at the same time.
12. **A structured export from Eat My Way — an open question for *that* project, not this one**
    (PLAN.md Phase 8, task 4; raised 2026-09-23). Phase 8 reads the plain text Eat My Way
    already shares, and **no change to Eat My Way is required or requested**. What the text
    cannot carry, and what a structured export would have to add if that project ever wants it:
    - **The department as an id, not as a label.** The text prints „Nabiał i jaja"; the parser
      matches that string back to `nabial`. Both apps already agree on the nine ids
      (decision 9), so an export carrying `department: "nabial"` would survive a label being
      reworded on either side. Today a reworded heading silently becomes a *custom category*
      of the imported list — no data is lost, but the item stops meeting its department.
    - **The amount as a number, a unit and a measure, not as printed text.** „2 ząbki (10 g)"
      is parsed back into 2 + „ząbki", and the grams are thrown away. `{amount: 2, unit: "szt",
      measureName: "ząbek", grams: 10}` would need no Polish plural table on this side
      (decision 85) and would let a summed row print the right form.
    - **A stable ingredient id.** Merging is by folded name today (decision 85), so „Ser żółty
      gouda" and „Ser gouda" are two things. An `ingredientId` would merge them, and would let
      a later import update a row rather than guess.
    - **The list's identity and its range**, so importing the same week twice could be told
      from importing two different weeks. Today they are the same text and therefore sum
      (which is the wanted behaviour for the first, and arguably not for the second).
    - **How it would travel:** the same share sheet, as `application/json` beside the
      `text/plain` (a share can offer both, and an app that parses neither still gets the text),
      or an App Link into `buymyway://`. Either way the plain text stays, because it is what a
      person can read in a messenger.
    Nothing here is planned. It is written down so that the next person to touch Eat My Way's
    `formatShoppingList` knows what this side would gain.
