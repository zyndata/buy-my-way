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
buymyway.gorny.dev    a static page for invite App Links + assetlinks.json (Phase 5)
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
their profile and preferences); Phase 5 extends it to members, roles and invites.

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

## The push sender (Phase 9)

`push/Code.gs` and `push/appsscript.json` are deployed as an Apps Script **web app**
(*Execute as: me*, *Who has access: anyone*), with the script's Cloud project set to the
Firebase project so `ScriptApp.getOAuthToken()` carries the `firebase.database` and
`firebase.messaging` scopes. No service-account key exists anywhere. The deployment URL goes
into `gradle.properties` as `BUYMYWAY_PUSH_ENDPOINT`. Re-deploying after a change is „Deploy →
Manage deployments → edit → new version"; the URL does not change.

The Phase 0 skeleton has been deployed since 2026-09-21 (project "Buy My Way push" under the
owner's account, Cloud project `270774397521`). Script properties: `FIREBASE_API_KEY`,
`FIREBASE_PROJECT_ID`, and `FCM_TOKEN` for the skeleton's single target, which Phase 9
replaces. What Phase 0 learned the hard way (STATE.md decisions 23 and 24):

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
