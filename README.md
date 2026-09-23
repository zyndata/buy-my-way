# Buy My Way 🛒

*Polish: „Kupuję po swojemu"*

A shared shopping list for Android. Keep any number of lists, private or shared with the people
you shop with; when one of you ticks something off at the shelf, the other sees it crossed out
within a second, and a closed app gets a notification. Items are grouped by the part of the shop
they are bought in, can carry a note and a photo, can be dictated, and a whole week's list can be
brought in from [Eat My Way](https://github.com/zyndata/eat-my-way) with one share.

The interface is in **Polish**. The code, comments and documentation are in English.

> **Status: early development. It works as a shopping list you can share: invite someone
> by link or e-mail, and each of you sees the other's changes on the open list in about half
> a second, photos included; you can dictate what to buy instead of typing it, and share a
> week's list out of Eat My Way straight into it. Notifications for a closed app come next.**
> [PLAN.md](PLAN.md) holds the specification and the phases (Phase 11, Google Play, was
> dropped); [STATE.md](STATE.md) records what has been decided and what is still
> open.
> Phase 0, a spike on Drive sharing and the Google project, is done. Its verdict: another
> member cannot read a shared Drive file under the `drive.file` scope, so shared lists live in
> Firebase Realtime Database instead (STATE.md decisions 19–24). A change reached the other
> phone in ~0.1 s, and a push reached a closed app in under 3 s.
> Phase 1 is done: the app builds (`./gradlew assembleDebug`, see
> [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)).
> Phase 2 is done: the lists live in a local database on the phone, and one tested merge
> settles changes made on two phones.
> Phase 3 is done: lists and items on screen. You can create lists and put them in your own
> order. Type „2 kg ziemniaki, mleko" to add two items, each under its part of the shop. A tap
> strikes an item through and moves it to „Kupione". Items and departments can be dragged
> into the order you walk the shop, and a deleted list or item comes back with „Cofnij".
> Everything works offline and without an account.
> Phase 4 is done: sign in with Google in Ustawienia, and your lists, including the ones made
> before, go to Firebase Realtime Database. Another phone signed in to the same account gets
> them, and edits made on both, even offline, settle the same way on both. Pull down on Listy
> to fetch changes. The database rules keep each user's lists to that user and refuse a write
> older than the stored one.
> Phase 5 is done: „Udostępnij" invites by link or e-mail, as an editor or read-only; the owner
> can change roles, remove people or make the list private, and anyone else can leave it. An
> open list is live: someone else's tick shows their initial and slides into „Kupione", and
> „Ania ogląda" says who is looking. The edit sheet says when an item was edited and bought,
> and „Sortowanie" shows a list by department, A–Z, or in your own order. Measured on two
> phones: a tick reached the other in ≈ 0.5 s (p95).
> Phase 6 is done: an item can carry a photo — taken with the camera or picked from the
> gallery, without giving the app the camera, storage or photo permission. It is shrunk to at
> most 800 px and 80 kB, turned the right way up and stripped of everything the camera wrote
> into it, location above all, before it leaves the phone. It shows in the row and opens full
> screen.
> Phase 7 is done: the mic in the add bar takes a whole sentence — „dwa kilo ziemniaków,
> mleko, masło i chleb" — and shows it as four lines with their departments, to correct before
> „Dodaj wszystkie" adds them. Nothing is added without that tap, the speech goes to the
> phone's own recognizer and nowhere else, and it is preferred offline.

> Phase 8 is done: share a shopping list out of Eat My Way (or paste any text with „Wklej ze
> schowka" on Listy) and it becomes a list here. The preview shows every line under the part of
> the shop it was printed under, „×" drops one, and you pick an existing list or a new one
> named after the week. Importing the same list twice sums the quantities instead of adding
> everything again, and anything that is not an Eat My Way list is read as one item per line.
> Phase 9, notifications for a closed app, is next.

## Screenshots

<p>
  <img src="docs/screenshots/lists.png" width="240" alt="Listy: one shared list, „Zakupy na sobote”, 6 of 13 bought, with „… ogląda” (the name hidden) under its name">
  <img src="docs/screenshots/list-checking.png" width="240" alt="The list by department, with the share icon in the top bar and „… ogląda” (the name hidden) under the list's name">
  <img src="docs/screenshots/list-bought.png" width="240" alt="The bottom of the list: „Kupione (4)” expanded, with „Wyczyść kupione”">
</p>

## What makes it different

- **Your lists are on your phone first.** The app works fully offline and without an account;
  until you sign in, no list leaves the device.
- **Shared lists live in a Firebase project, not on a server of ours.** When you sign in, your
  lists and their photos go to a Firebase Realtime Database that only the list's members can
  read, enforced by the database's rules. The app asks Google for your name and email and
  nothing else: no access to your Drive, your contacts or your location.
- **Live, without draining the battery.** Another person's screen moves in under a second while
  the list is open. When the app is closed, a push message wakes it — no background service,
  no polling.
- **Nothing you did not ask for.** No premium tier, no ads, no leaflets, no price tracking, no
  analytics, no location or contacts permission.
- **Say it instead of typing it.** The mic reads one sentence as many items, with their
  quantities and departments, and always asks you to look before anything is added. It uses
  the recognizer that is already on the phone, offline where the Polish pack is installed; the
  app records nothing itself and keeps no audio.
- **Speaks Eat My Way.** Share a week's shopping list out of Eat My Way and it lands here in the
  right places, quantities and all: the nine departments of the shop are the same. Import the
  same list again and the quantities are summed, not doubled up. Any other text you share or
  paste becomes one item per line.

## Repository

- [PLAN.md](PLAN.md) — specification and phases
- [STATE.md](STATE.md) — progress, decisions, open questions
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — local setup on Windows and Linux
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) — releases, Firebase, the push script
- [CHANGELOG.md](CHANGELOG.md) — generated from commit messages by git-cliff

## Licence

MIT — see [LICENSE](LICENSE). The icons in `app/src/main/res/drawable` (except the launcher
icon) are [Material Symbols](https://github.com/google/material-design-icons) by Google,
Apache License 2.0.
