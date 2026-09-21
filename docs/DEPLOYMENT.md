# Deployment

There is no server of ours. „Deployment" means four things, each set up once and then driven by
a tag:

```
GitHub tag vX.Y.Z
      │
      ├─ check     lint + unit tests                       (deploy.yml, Phase 10)
      ├─ build     assembleRelease, signed from secrets
      ├─ release   git-cliff → CHANGELOG.md on main + GitHub Release with the APK
      └─ play      upload the AAB to the closed-testing track (Phase 11)

Firebase project      Realtime Database (Spark plan), Auth (Google), Cloud Messaging
Google Apps Script    the push sender, deployed from push/ under the owner's account
buymyway.gorny.dev    a static page for invite App Links + assetlinks.json (Phase 5)
```

> Phases 0, 5, 9, 10 and 11 fill in the exact steps. Until then this file records what each
> piece is for and what must never be committed.

## Firebase / Google Cloud (Phase 0)

One project, `buy-my-way`, on the **Spark** (free) plan. It holds:

- **Realtime Database** in `europe-west1`, rules from `firebase/database.rules.json`
  (deployed with `firebase deploy --only database` — Phase 5).
- **Authentication** with the Google provider.
- **Cloud Messaging** — nothing to configure; tokens are registered by the app.
- **OAuth consent screen** in *Testing* mode with the household as test users; scopes
  `drive.file` and `drive.appdata`. *Testing* mode is deliberate: no verification is needed
  below 100 test users, and the app will never have more.
- **OAuth clients**: one Web client (its id is the `serverClientId` the app passes to Sign in
  with Google) and one Android client per signing SHA-1 — each developer machine's debug key,
  the release key, and Play App Signing's key once Phase 11 exists.
- **Drive API** enabled.

Public ids (`google-services.json`, the Web client id, the Apps Script URL) are committed.
Nothing here is a secret; what protects the data is the database rules and Drive's permissions.

## The push sender (Phase 9)

`push/Code.gs` and `push/appsscript.json` are deployed as an Apps Script **web app**
(*Execute as: me*, *Who has access: anyone*), with the script's Cloud project set to the
Firebase project so `ScriptApp.getOAuthToken()` carries the `firebase.database` and
`firebase.messaging` scopes. No service-account key exists anywhere. The deployment URL goes
into `gradle.properties` as `BUYMYWAY_PUSH_ENDPOINT`. Re-deploying after a change is „Deploy →
Manage deployments → edit → new version"; the URL does not change.

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

## Google Play (Phase 11)

Play App Signing with our release key as the upload key, a closed-testing track, the data
safety form answered from `SECURITY.md`, and the privacy policy page. The Play service account
JSON is a GitHub Secret (`PLAY_SERVICE_ACCOUNT_JSON`) and nothing else.

## Rollback

Sideload: install the previous Release's APK over the current one — same signature, lower
`versionCode`, so `adb install -r -d` is needed; a normal user installs the older APK from the
Release page after uninstalling. Play: promote the previous release in the console. Data is
unaffected either way — it is on Drive, and the on-device Room schema is migrated forward only,
so a rollback across a schema version has to be listed in the release notes as „requires
reinstall".
