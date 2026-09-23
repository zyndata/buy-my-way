# Deployment

There is no server of ours. „Deployment" means four things, each set up once and then driven by
a tag:

```
GitHub tag vX.Y.Z
      │
      ├─ check     lint + unit tests                       (deploy.yml, Phase 10)
      ├─ build     assembleRelease, signed from secrets
      └─ release   git-cliff → CHANGELOG.md on main + GitHub Release with the APK

Firebase project      Realtime Database (Spark plan), Auth (Google), Cloud Messaging
Google Apps Script    the push sender, deployed from push/ under the owner's account
eatmyway.gorny.dev    /bmw/i/<token> invite page + assetlinks.json, in the Eat My Way repo (Phase 5)
```

> Phases 0, 5, 9, 10 and 11 fill in the exact steps. Until then this file records what each
> piece is for and what must never be committed.

## Firebase / Google Cloud (Phase 0)

One project, `buy-my-way-c3949` (the plain id was taken), on the **Spark** (free) plan. It holds:

- **Realtime Database** in `europe-west1`, rules from `firebase/database.rules.json` (see
  *Database rules* below).
- **Authentication** with the Google provider.
- **Cloud Messaging** — nothing to configure; tokens are registered by the app.
- **OAuth consent screen**: External, Eat My Way's branding (support group, home page and
  privacy page on `eatmyway.gorny.dev`, `gorny.dev` authorised). The app asks for the basic
  sign-in scopes only (STATE.md decision 21), so the screen is published *In production*
  (decision 25). Testing would expire grants after 7 days, the Apps Script's included. No
  verification is needed; the household's accounts may still see the "unverified app"
  notice once.
- **OAuth clients**: one Web client (its id is the `serverClientId` the app passes to Sign in
  with Google) and one Android client per signing SHA-1 — each developer machine's debug key,
  the release key. There is no Play App Signing key: no Google Play (STATE.md decision 25).
- **Drive API**: off. It was enabled only for the Phase 0 spike (2026-09-21).

Public ids (`google-services.json`, the Web client id, the Apps Script URL) are committed.
Nothing here is a secret; what protects the data is the database rules.

## Database rules

`firebase/database.rules.json` is the only authorization layer for the lists (PLAN.md
*Security*). CI tests it against the emulator on every push; what the real project runs is
whatever was last put there by hand. Phase 4 wrote the first version (a user's own lists,
their profile and preferences); Phase 5 extends it to members, roles and invites; Phase 6 to
photos.

To put a new version live (STATE.md decision 61):

1. Wait for the push's CI run to be green: its `rules` job has tested exactly that file.
2. Firebase console → Realtime Database → *Rules*. Replace the whole text with the file's
   content, *Publish*.
3. Check it took: the console's *Rules playground*, a read of `/lists` as an unauthenticated
   user → denied.

Or with the CLI, from `firebase/` (it needs a `firebase login` of the owner's account, which is
never stored in the repository): `npx firebase deploy --only database --project buy-my-way-c3949`.

Rules and app go out together: an app that writes a field the live rules do not know is
refused (unknown fields are rejected), so publish the rules **before** installing a build
that needs them.

**Phase 5's rules and the Phase 4 build do not mix.** The Phase 5 rules key `/emailIndex` by
the address itself (STATE.md decision 63), so a Phase 4 build's profile write (a sha256 key)
is refused, and that build then fails every sync. So: publish the rules, then install the
Phase 5 build on every phone straight away. The sha256 entries under `/emailIndex` that Phase 4
wrote are no longer used, and can be deleted in the console (the 64-character hex keys).

**Publish Phase 6's rules before installing the Phase 6 build.** Under the Phase 5 rules a
photo write is refused, and the build then gives up on that photo. Deleting a list is refused
too, because the delete now also removes `/photos/{listId}`, which the Phase 5 rules do not
allow. The Phase 5 build works under the Phase 6 rules: it writes no photos.

**Publish Phase 8b's rules before installing the Phase 8b build.** „Moje produkty" writes
`/users/{uid}/prefs/products`, and the Phase 6 rules reject an unknown key under `prefs`. Under
them every product would be refused and, because the preferences are pushed in one pass, the
push would stop there. Nothing else is affected: an older build simply never reads or writes
that key, and it holds no list data — only the user's own words and their departments.

## Invite links (Phase 5)

A list is shared by `https://eatmyway.gorny.dev/bmw/i/<token>` (STATE.md decisions 35 and 63).
The host is Eat My Way's, and so are the files, in the **Eat My Way repository** (its decision
458): `public/bmw/invite.html` + `invite.js`, the `Caddyfile` rewrite of `/bmw/i/*` to that
page, and `public/.well-known/assetlinks.json`. They go live with an Eat My Way release
(`/release` there). Until then, and on a phone where Android has not verified the App Link,
a link opens the browser; its page's button opens `buymyway://i/<token>`, which the app also
handles.

`assetlinks.json` lists the SHA-256 of every key that signs a build that should open the links
directly: today the Windows machine's debug key. Add the Linux machine's debug key when it is
registered (STATE.md open question 2), and the release key in Phase 10. Read a fingerprint with
`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`.
Check the verification on a phone with
`adb shell pm get-app-links dev.gorny.buymyway` (it should say `verified` for the host).

## The push sender (Phase 9)

`push/Code.gs` and `push/appsscript.json` are deployed as an Apps Script **web app**
(*Execute as: me*, *Who has access: anyone*), with the script's Cloud project set to the
Firebase project so `ScriptApp.getOAuthToken()` carries the `firebase.database` and
`firebase.messaging` scopes. No service-account key exists anywhere. The deployment URL goes
into `gradle.properties` as `buymyway.pushUrl`, and the build turns it into
`BuildConfig.PUSH_URL`; an **empty value builds an app that never pushes**, which is what a
fork with no script of its own wants. Re-deploying after a change is „Deploy → Manage
deployments → edit → new version"; the URL does not change.

The Phase 0 skeleton has been deployed since 2026-09-21 (project "Buy My Way push" under the
owner's account, Cloud project `270774397521`).

### What Phase 9 changed, and what to do about it

The script no longer pushes to one hard-coded device. It now checks that the caller is a
member of the list, reads the other members' `/fcmTokens`, sends one data message per device,
deletes a token FCM reports as unregistered, and rate-limits per list. Three things must be
done in the Apps Script project **before the new app build can push anything**:

1. **Paste both files again** (`push/Code.gs` and `push/appsscript.json`).
2. **`appsscript.json` gained `https://www.googleapis.com/auth/firebase.database`**, without
   which every database read answers 403 and no push ever goes out. A manifest change needs a
   **new deployment version** (point 2 below) *and* a fresh authorisation (point 3): run
   `doGet` in the editor and tick **every** box.
3. **Add the script property `FIREBASE_DB_URL`** =
   `https://buy-my-way-c3949-default-rtdb.europe-west1.firebasedatabase.app` (no trailing
   slash). `FCM_TOKEN` is no longer read and can be deleted.

Then check it, from any machine:

```
curl -s  <url>                              # {"ok":true}
curl -sL -d '{"idToken":"x"}' <url>         # {"ok":false,"error":"unauthenticated"}
```

A **valid** token whose user is not on the list answers `{"ok":false,"error":"forbidden"}` —
that is the phase's third acceptance criterion, and the way to get a real token by hand is
`adb logcat` on a debug build, or the Firebase Auth REST `signInWithCustomToken`. Anything
that answers with an HTML page means the authorisation is incomplete.

**Publish the Phase 9 database rules before installing the Phase 9 app**, as with every phase
since Phase 6: the older rules reject an unknown key, so a device that writes `/fcmTokens`
against them is simply refused and never receives a push.

What Phase 0 learned the hard way (STATE.md decisions 23 and 24) still holds:

1. **Paste `appsscript.json` before the first deployment.** A deployment version keeps the
   manifest it was created with. A version made before the `oauthScopes` were pasted only has
   the scopes Apps Script guessed (`script.external_request`), and FCM then answers
   `403 insufficient authentication scopes`.
2. **Every manifest change needs a new version.** Manage deployments → ✏️ → *Version: New
   version* → Deploy. The dialog defaults to the current version, and saving it that way
   changes nothing.
3. **Authorise by running a function in the editor, and tick every box.** Google's consent
   screen lists each scope with its own checkbox. A partial grant is remembered, and the
   script fails later with "you do not have permission to call UrlFetchApp.fetch".
4. **Don't revoke "Buy My Way" in Google Account → Connections to redo the consent.** The
   script and the app share one OAuth project, so that also ends the Firebase session of
   every phone signed in with that account.
5. Check a deployment from any machine: `GET <url>` → `{"ok":true}`; `POST` with
   `{"idToken":"x"}` (let curl turn the 302 into a GET: `curl -sL -d …`, not `-X POST`) →
   `{"ok":false,"error":"unauthenticated"}`. Anything else, an HTML error page above all,
   means the authorisation is incomplete.

Quotas that matter (consumer account): 20 000 URL fetches a day, 90 minutes of runtime a day.
A push is one fetch per recipient; a household will not get near either.

## Releasing (Phase 10)

- Secrets in the GitHub repository: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`. The keystore file itself is kept in the owner's password manager; losing it
  means every installed copy has to be uninstalled before the next one installs.
- `deploy.yml` runs only on `v[0-9]+.[0-9]+.[0-9]+` tags. A plain push to `main` does nothing.
- The Release carries `buy-my-way-vX.Y.Z.apk` and a `.sha256`; the app's update check reads
  `releases/latest`.
- Tags are protected by a ruleset (no deletion, no force-update, no bypass actors), as in Eat
  My Way: a bad release is fixed forward with the next patch version, never by moving a tag.

## Google Play

Not used (STATE.md decision 25). Releases are GitHub Releases only.

## Rollback

Sideload: install the previous Release's APK over the current one — same signature, lower
`versionCode`, so `adb install -r -d` is needed; a normal user installs the older APK from the
Release page after uninstalling. Data is
unaffected either way — it is in RTDB, and the on-device Room schema is migrated forward only,
so a rollback across a schema version has to be listed in the release notes as „requires
reinstall".
