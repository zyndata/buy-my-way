# Buy My Way 🛒

*Polish: „Kupuję po swojemu"*

A shared shopping list for Android. Keep any number of lists, private or shared with the people
you shop with; when one of you ticks something off at the shelf, the other sees it crossed out
within a second, and a closed app gets a notification. Items are grouped by the part of the shop
they are bought in, can carry a note and a photo, can be dictated, and a whole week's list can be
brought in from [Eat My Way](https://github.com/zyndata/eat-my-way) with one share.

The interface is in **Polish**. The code, comments and documentation are in English.

> **Status: planned, nothing built yet.** [PLAN.md](PLAN.md) holds the specification and the
> twelve phases; [STATE.md](STATE.md) records what has been decided and what is still open.
> Phase 0, a spike on Drive sharing, is in progress: the tooling is in `spike/` and `push/`,
> and the measurements are pending.

## What makes it different

- **Your lists stay on your Drive.** The documents and photos of every list live in a
  `Buy My Way` folder on the list owner's Google Drive, shared with the members through
  Drive's own permissions. There is no server of ours and no database holding your lists.
- **Live, without draining the battery.** A tiny, short-lived log of changes in Firebase
  Realtime Database is what moves another person's screen in under a second while the list is
  open. When the app is closed, a push message wakes it — no background service, no polling.
- **Nothing you did not ask for.** No premium tier, no ads, no leaflets, no price tracking, no
  analytics, no location or contacts permission.
- **Speaks Eat My Way.** The nine departments of the shop are the same, so a shared week's list
  lands in the right places, quantities and all.

## Repository

- [PLAN.md](PLAN.md) — specification and phases
- [STATE.md](STATE.md) — progress, decisions, open questions
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — local setup on Windows and Linux
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) — releases, Firebase, the push script, Play
- [CHANGELOG.md](CHANGELOG.md) — generated from commit messages by git-cliff

## Licence

MIT — see [LICENSE](LICENSE).
