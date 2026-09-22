# Buy My Way 🛒

*Polish: „Kupuję po swojemu"*

A shared shopping list for Android. Keep any number of lists, private or shared with the people
you shop with; when one of you ticks something off at the shelf, the other sees it crossed out
within a second, and a closed app gets a notification. Items are grouped by the part of the shop
they are bought in, can carry a note and a photo, can be dictated, and a whole week's list can be
brought in from [Eat My Way](https://github.com/zyndata/eat-my-way) with one share.

The interface is in **Polish**. The code, comments and documentation are in English.

> **Status: early development. It works as a private shopping list, and after signing in the
> lists are kept in the cloud and on every phone of that account; sharing with other people
> comes next.** [PLAN.md](PLAN.md) holds the specification and the phases (Phase 11, Google
> Play, was dropped); [STATE.md](STATE.md) records what has been decided and what is still
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
> older than the stored one. Phase 5, sharing with other people and live changes, is next.

## Screenshots

<p>
  <img src="docs/screenshots/lists.png" width="240" alt="Listy: one list, „Zakupy na sobote”, 2 of 10 bought">
  <img src="docs/screenshots/list-checking.png" width="240" alt="A list grouped by department; „cebula” has just been ticked and is struck through">
  <img src="docs/screenshots/list-bought.png" width="240" alt="The bottom of the list: „Kupione (2)” expanded, with „Wyczyść kupione”">
</p>

## What makes it different

- **Your lists are on your phone first.** The app works fully offline and without an account;
  a list you never share never leaves the device.
- **Shared lists live in a Firebase project, not on a server of ours.** When you sign in, your
  lists and their photos go to a Firebase Realtime Database that only the list's members can
  read, enforced by the database's rules. The app asks Google for your name and email and
  nothing else: no access to your Drive, your contacts or your location.
- **Live, without draining the battery.** Another person's screen moves in under a second while
  the list is open. When the app is closed, a push message wakes it — no background service,
  no polling.
- **Nothing you did not ask for.** No premium tier, no ads, no leaflets, no price tracking, no
  analytics, no location or contacts permission.
- **Speaks Eat My Way.** The nine departments of the shop are the same, so a shared week's list
  lands in the right places, quantities and all.

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
