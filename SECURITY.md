# Security policy

## Supported versions

Only the latest released version is supported. Report against it whenever possible.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting:
**Security → Advisories → Report a vulnerability** in this repository.

Please do not open a public issue for a security problem.

Expect an initial response within 14 days.

## What this app holds

Buy My Way has no backend and no server-side account of its own. What is sensitive lives on the
user's phone, on the user's Google Drive, and in one short-lived log:

- a **Google OAuth token** with the `drive.file` scope (the folders this app created or was
  given, never the rest of the user's Drive) and `drive.appdata` (the app's private preference
  file);
- a **Firebase Authentication session** for the user's Google account;
- the user's **shopping lists and item photos**, on the list owner's Drive, shared with the
  members the owner named through Drive's own permissions;
- a **log of recent changes** (item id, checked or not, who, when — item names appear in
  `item.put` operations) in Firebase Realtime Database, readable only by the list's members and
  pruned after seven days;
- the members' **names, emails and avatars** as Google reports them, in the database, readable
  by signed-in users so a list can show who is on it;
- **FCM registration tokens**, readable by nobody but the push script.

Tokens are held by the Google and Firebase SDKs and in memory. The app never writes a token to
its database, to preferences, to logs, to an Intent or to a URL.

The push sender is a Google Apps Script running under the owner's account. It accepts a request
only with a valid Firebase ID token whose user is a member of the named list, and it sends
nothing but a list id and a change kind through FCM — item names never travel through push.

## Scope

Security-relevant problems are most likely to look like:

- a path by which the **Drive token or the Firebase session escapes** — into Room, DataStore,
  logcat, a crash message, an Intent extra, a URL, or a request to anything but Google's APIs;
- a **hole in the Realtime Database rules**: a non-member reading a list, a viewer or a
  non-member writing an operation, an operation being modified after creation, a member
  writing their own role;
- a **Drive permission that outlives membership** — a removed member still able to read the
  folder;
- an **invite token that can be guessed, reused past its limit, or read by someone who does
  not hold it**;
- the **push endpoint accepting a request** without a valid token, from a non-member, or often
  enough to be used as a way to drain another user's battery;
- a **photo reachable without Drive's permission check**.
