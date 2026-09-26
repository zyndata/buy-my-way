# Security policy

## Supported versions

Only the latest released version is supported. Report against it whenever possible.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting:
**Security → Advisories → Report a vulnerability** in this repository.

Please do not open a public issue for a security problem.

Expect an initial response within 14 days.

## What this app holds

Buy My Way has no backend of its own. What is sensitive lives on the user's phone and in the
owner's Firebase project:

- a **Firebase Authentication session** for the user's Google account. Sign in with Google asks
  for the basic profile only; the app requests **no Google Drive access** and holds no Google
  OAuth token of its own;
- the user's **shopping lists and item photos** (downscaled, EXIF stripped) in Firebase
  Realtime Database, readable only by the list's members under the database rules — and, as
  with any Firebase project, by the project's owner in the Firebase console;
- the user's **preferences** (category memory, default order), readable only by that user;
- the members' **names, emails and avatars** as Google reports them, in the database, readable
  by signed-in users so a list can show who is on it;
- **FCM registration tokens**, readable by nobody but the push script;
- an optional **allow-list of e-mail addresses** (`/access`), written by the owner in the
  Firebase console and readable by nobody through the rules. When it is switched on, every
  rule grant also requires a verified address on it.

Tokens are held by the Firebase SDK and in memory. The app never writes a token to its
database, to preferences, to logs, to an Intent or to a URL.

The app can also **install a new version of itself**. It asks GitHub once a day, while the user
is looking at the home screen, for this repository's `releases/latest`; it offers only a
`X.Y.Z` newer than the running one, and it downloads only from a URL under
`https://github.com/zyndata/buy-my-way/releases/`. The file goes to the app's own external
files directory and is handed to Android's package installer, which asks the user before
anything is installed — and which refuses any APK not signed with the same key. Nothing about
the user or their lists travels with the request.

The push sender is a Google Apps Script running under the owner's account. It accepts a request
only with a valid Firebase ID token whose user passes the allow-list (when it is on) and is a
member of the named list, and it sends
nothing but a list id and a change kind through FCM — item names never travel through push.

## Scope

Security-relevant problems are most likely to look like:

- a path by which the **Firebase session escapes** — into Room, DataStore, logcat, a crash
  message, an Intent extra, a URL, or a request to anything but Google's APIs;
- a **hole in the Realtime Database rules**: a non-member reading a list or a photo, a viewer
  or a non-member writing an item, an older write overwriting a newer one, a member writing
  their own role;
- **access that outlives membership** — a removed member still able to read the list or its
  photos;
- a way **past the allow-list**, or to **read it** — an account not on it reading or writing
  anything, or anyone learning which addresses are on it;
- an **invite token that can be guessed, reused past its limit, or read by someone who does
  not hold it**;
- the **push endpoint accepting a request** without a valid token, from a non-member, or often
  enough to be used as a way to drain another user's battery;
- a **photo reachable without the rules' membership check**;
- a way to make the **update check install something else** — a release document that points
  the download somewhere other than this repository's Releases, or a downloaded file reachable
  by another app before the installer sees it.
