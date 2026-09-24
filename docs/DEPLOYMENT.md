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

> Phases 0, 5, 9 and 10 filled in the exact steps. Phase 11 (Google Play) was dropped.

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
  **The release key's SHA-1 is `BB:E3:65:E6:A5:06:44:FD:54:54:67:67:28:07:45:4E:96:42:4A:AF`
  and must be registered before the first release**, or „Zaloguj się przez Google" fails on
  every released build while everything that works signed out carries on working — which is a
  confusing way to find out. Add the fingerprint in the console, download the refreshed
  `google-services.json` and commit it. A fingerprint is not a secret.
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
directly. It carries two, both live since Eat My Way v1.17.3 (2026-09-24):

| Key | SHA-256 |
|---|---|
| Windows debug | `34:33:6E:34:…:68:19:DC` |
| **Release** | `0D:EF:EC:73:85:7A:11:DA:09:22:FF:32:27:AC:F5:25:DE:B7:35:F7:9F:8F:06:1C:1E:7A:9F:14:72:1B:C7:DB` |

Add the Linux machine's debug key when it is registered (STATE.md open question 2). A key that
is missing here does not break anything outright: the link opens the browser, and that page's
`buymyway://i/<token>` button carries it through.

Reading a fingerprint — neither needs the release password:

```
apksigner verify --print-certs buy-my-way-vX.Y.Z.apk                 # any built APK
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

Check it on a phone with `adb shell pm get-app-links dev.gorny.buymyway` (`verified` for the
host). That only ever proves the key that signed *the build on that phone*, so the release
fingerprint is confirmed by a release-signed APK, not a debug one.

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

### Bringing Phase 9 live — the whole procedure

The script no longer pushes to one hard-coded device. It checks that the caller is a member of
the list, reads the other members' `/fcmTokens`, sends one data message per device, deletes a
token FCM reports as unregistered, and rate-limits per list. **Until every step below is done,
no push can physically happen** — and the app says nothing about it, because a phone whose
registration is refused simply has no push target.

Everything here is about one project. The ids are public by design (CLAUDE.md), and all of them
come out of [`app/google-services.json`](../app/google-services.json):

| | |
|---|---|
| Firebase project id | `buy-my-way-c3949` |
| Project (Cloud) number | `270774397521` |
| Realtime Database | `https://buy-my-way-c3949-default-rtdb.europe-west1.firebasedatabase.app` |
| Android package | `dev.gorny.buymyway` |
| Web API key | the `api_key[0].current_key` field of `google-services.json` |

Console shortcuts, all for this project:

- Rules → <https://console.firebase.google.com/project/buy-my-way-c3949/database/buy-my-way-c3949-default-rtdb/rules>
- Data → <https://console.firebase.google.com/project/buy-my-way-c3949/database/buy-my-way-c3949-default-rtdb/data>
- Authentication providers → <https://console.firebase.google.com/project/buy-my-way-c3949/authentication/providers>
- Apps Script projects → <https://script.google.com/home> (the project is called **Buy My Way push**)
- **Apps Script executions** → <https://script.google.com/home/executions> — every `doPost`, its
  duration and its error. This is the first place to look when a push does not arrive.

#### Step 1 — publish the database rules

Open the [rules page](https://console.firebase.google.com/project/buy-my-way-c3949/database/buy-my-way-c3949-default-rtdb/rules),
select everything, paste the whole of [`firebase/database.rules.json`](../firebase/database.rules.json),
**Publish**. The only addition since Phase 8b is the `fcmTokens` block
(`firebase/database.rules.json:135`): `.write` for that user alone, a node of exactly
`{at: now}`, and **no `.read` at any depth** — the script reads it as the project's owner, which
is why it needs no service-account key (STATE.md decision 98).

Do this **before** installing the Phase 9 app, as with every phase since Phase 6. The older
rules reject an unknown key, so a phone that writes `/fcmTokens` against them is refused.

#### Step 2 — paste the two script files

In the [Apps Script project](https://script.google.com/home):

1. **`Code.gs`** ← the whole of [`push/Code.gs`](../push/Code.gs). It is a rewrite, not an edit.
2. **`appsscript.json`** ← [`push/appsscript.json`](../push/appsscript.json). If the file is not
   shown: ⚙ **Project Settings** → tick *„Show appsscript.json manifest file in editor"*.
   Two lines are new: `https://www.googleapis.com/auth/firebase.database` **and**
   `https://www.googleapis.com/auth/userinfo.email`. The database's REST API needs **both** —
   with the database scope alone it answers `401 Unauthorized request.`, which reads like a
   missing grant and is not one. That cost an evening the first time (`selfTest` now prints the
   scopes the token really carries, so it cannot cost a second).

#### Step 3 — the script property

⚙ **Project Settings → Script properties → Add script property**:

| Property | Value |
|---|---|
| `FIREBASE_DB_URL` | `https://buy-my-way-c3949-default-rtdb.europe-west1.firebasedatabase.app` |

**No trailing slash** — the script appends `<path>.json` to it (`push/Code.gs:150`).
`FIREBASE_API_KEY` and `FIREBASE_PROJECT_ID` are already there from Phase 0. `FCM_TOKEN` is no
longer read and can be deleted.

#### Step 4 — authorise again, ticking every box

The manifest changed, so the old grant is not enough. In the editor pick the function
**`selfTest`** — *not* `doGet` — and **Run** → *Review permissions* → choose the account →
*Advanced* → *Go to Buy My Way push (unsafe)* → **tick every checkbox** → Allow. A partial grant
is remembered and fails later with „you do not have permission to call UrlFetchApp.fetch"
(STATE.md decision 23).

**Why `selfTest` and not `doGet`.** `doGet` only builds a string with `ContentService`, which
needs no scope at all, so Apps Script never asks for anything and the run „succeeds" while
`firebase.database` is still ungranted — the script then reads nothing and every push comes back
`forbidden`. `selfTest` calls `UrlFetchApp` and reads the database with the script's own token,
so the consent screen actually appears, and its log says which part is wrong:

```
FIREBASE_API_KEY: set
FIREBASE_PROJECT_ID: set
FIREBASE_DB_URL: set
granted scopes:
  https://www.googleapis.com/auth/firebase.database
  https://www.googleapis.com/auth/firebase.messaging
  https://www.googleapis.com/auth/script.external_request
  https://www.googleapis.com/auth/userinfo.email
database read: HTTP 200          ← 401: a scope is missing (userinfo.email above all)
                                 ← 404: FIREBASE_DB_URL is wrong
```

It names any scope that is missing. A manifest that was edited but never re-authorised looks
identical to a correct one everywhere else, which is why this prints what the **token** holds
rather than what the file says.

The log is under *Wykonania / Executions* in the editor's left bar.

#### Step 5 — deploy a *new version*

**Deploy → Manage deployments → ✏️ (edit) → Version: `New version` → Deploy.**

The dialog defaults to the current version, and saving it that way changes nothing: a deployment
keeps the manifest it was created with. The URL does not change, so
[`gradle.properties:10`](../gradle.properties) needs no edit.

#### Step 6 — check the endpoint

```bash
URL=$(grep buymyway.pushUrl gradle.properties | cut -d= -f2-)
curl -sL "$URL"                              # {"ok":true}
curl -sL -d '{"idToken":"x"}' "$URL"         # {"ok":false,"error":"unauthenticated"}
```

**`-L` on both**, and `-d` rather than `-X POST`: an Apps Script web app answers *every* verb
with a 302 to `script.googleusercontent.com`, so without `-L` the body comes back empty and the
check looks broken when it is not. An **HTML page** in the answer means the authorisation is
incomplete — go back to step 4.

#### Step 7 — make the phones register

A phone tries to register its FCM token **once per app start** (`SyncController`'s
`afterSignIn`, which runs once per process for a signed-in account). A phone that was refused
before the rules were published will not try again until it is restarted:

```bash
adb -s <serial> shell am force-stop dev.gorny.buymyway
# then open the app and leave it in the foreground for a few seconds
```

**Never leave a phone force-stopped while testing a push.** `am force-stop` puts the package
into Android's *stopped* state, and FCM broadcasts carry `FLAG_EXCLUDE_STOPPED_PACKAGES`, so a
force-stopped app receives nothing at all — the test then fails for a reason that has nothing to
do with the app. „Closed" for the purposes of the acceptance criterion means the **process** is
gone, not the package stopped: launch the app once, send it to the background
(`input keyevent KEYCODE_HOME`), then `adb shell am kill dev.gorny.buymyway`, which is what
Phase 0 used (STATE.md decision 24).

Detecting the notification on the other phone needs care too:
`dumpsys notification --noredact | grep dev.gorny.buymyway` **always matches**, because that
dump also lists every package's channels and app settings. Grep for a live record instead:

```bash
adb -s <serial> shell "dumpsys notification --noredact | grep -c 'pkg=dev.gorny.buymyway'"
```

**Then confirm on the [data page](https://console.firebase.google.com/project/buy-my-way-c3949/database/buy-my-way-c3949-default-rtdb/data):**
`/fcmTokens` must hold one child per uid, each with one token under it. No token, no push —
and nothing further is worth testing until this is right.

#### Checking a push that did not arrive, in order

1. **[Executions](https://script.google.com/home/executions)** — was `doPost` called at all?
   - *not called* → the sending phone never asked. Either it is not the list's member, every
     other member was present (which is deliberate: `PushSender` skips them), or the list is
     private.
   - *called, `forbidden`* → the caller is not under `/lists/{listId}/members`.
   - *called, `{"sent":0}`* → nobody else has a token under `/fcmTokens`. Back to step 7.
   - *403 / „insufficient authentication scopes"* → steps 2–5 again, above all step 4.
2. **`/fcmTokens`** in the console — one child per uid?
3. **The receiving phone** — Ustawienia → Powiadomienia on, and Android's own notification
   permission granted (`adb shell dumpsys package dev.gorny.buymyway | grep POST_NOTIFICATIONS`).
   Nothing is shown while the list is **open on screen**: that is the presence skip.

#### The „non-member" check (Phase 9 acceptance criterion 3)

A *valid* token belonging to somebody who is not on the list must answer
`{"ok":false,"error":"forbidden"}`. Do **not** try to extract a real token from a phone: this app
never logs an ID token and it should stay that way. Make a throwaway one instead:

1. [Authentication → Sign-in method](https://console.firebase.google.com/project/buy-my-way-c3949/authentication/providers)
   → temporarily enable **Email/Password**.
2. ```bash
   KEY=$(python -c "import json;print(json.load(open('app/google-services.json'))['client'][0]['api_key'][0]['current_key'])")
   curl -s -H 'Content-Type: application/json' \
     -d '{"email":"probe@example.test","password":"probe-123456","returnSecureToken":true}' \
     "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$KEY"
   ```
3. POST that `idToken` to the push URL with any real `listId` → expect `forbidden`.
4. **Disable Email/Password again** and delete the probe user.

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

Ship with the **`/release` skill** (`.claude/skills/release/SKILL.md`), which drives the whole
of this and checks the CI gate before it merges anything. What follows is what the skill does
and what has to exist for it to work.

### The one-time setup

**1. Make the release keystore.** Once, ever. Losing it ends the update path: a new key means a
different signature, and every installed copy would have to be uninstalled before the next one
could install.

```
keytool -genkeypair -v -keystore buy-my-way-release -storetype PKCS12 \
  -alias release -keyalg RSA -keysize 4096 -validity 10000
```

PKCS12 keeps **one** password for the store and the key, so `KEYSTORE_PASSWORD` and
`KEY_PASSWORD` below are the same value.

**The alias of this project's key is `release`.** It is not guessable and it is not optional:
`KEY_ALIAS` must match it exactly, or `assembleRelease` fails in CI with the tag already
pushed. `keytool -list -keystore buy-my-way-release` prints it — the first word of the
`…, PrivateKeyEntry` line — and Android Studio's *Generate Signed App Bundle or APK* dialog
shows it in the **Key alias** field.

**Where it lives: outside this checkout.** The file is `buy-my-way-release`, kept in a sibling
directory of the repository — not in it. `.gitignore` does cover `keystore/`, `*.jks` and
`*.keystore`, but that is a safety net and not the reason it is safe: the file has no
extension, so those patterns would not have caught it, and an ignore rule is a convention that
`git add -f` overrides and that a clone made before the rule never had. Keeping the key out of
the working tree removes the accident instead of guarding against it. This is a public
repository (CLAUDE.md).

The file **and its password belong in the owner's password manager**. Not in a text file beside
the keystore — that undoes the whole point, because then one mistake exposes both at once.
Never in a cloud drive folder that syncs to a machine, never in a chat.

**2. Put it in GitHub Secrets** (Settings → Secrets and variables → Actions):

| Secret | What |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 buy-my-way-release` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("buy-my-way-release"))`) |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `release` |
| `KEY_PASSWORD` | the same value as `KEYSTORE_PASSWORD` (PKCS12) |

They are **repository** secrets on `zyndata/buy-my-way`, which is what `deploy.yml` reads
through `secrets.*`. A user-level (`gh secret set --user`) secret is a Codespaces one and
Actions cannot use it here. From a clone:

```
base64 -w0 /path/to/buy-my-way-release | gh secret set KEYSTORE_BASE64 --repo zyndata/buy-my-way
gh secret set KEY_ALIAS --repo zyndata/buy-my-way --body release
gh secret set KEYSTORE_PASSWORD --repo zyndata/buy-my-way   # prompts; echoes nothing
gh secret set KEY_PASSWORD      --repo zyndata/buy-my-way   # prompts; echoes nothing
```

Piping the file keeps the key out of the shell history and off the screen. `gh secret list
--repo zyndata/buy-my-way` shows the four names (never the values — a secret cannot be read
back, only replaced, so a lost password means a new key and the end of the update path).

`deploy.yml` fails loudly if `KEYSTORE_BASE64` is missing — a Release carrying an unsigned APK
would be worse than no Release.

**3. To build a release locally** (the only way to see R8 at work before a tag), put the same
four values in `~/.gradle/gradle.properties` — **not** in the repository's `gradle.properties`:

```
buymyway.keystore=C:/path/to/buy-my-way-release
buymyway.keystorePassword=…
buymyway.keyAlias=release
buymyway.keyPassword=…
```

Without them `./gradlew assembleRelease` still builds, but the APK is unsigned and installs
nowhere (STATE.md decision 111).

### The release itself

- `deploy.yml` runs **only** on a `v[0-9]+.[0-9]+.[0-9]+` tag. A plain push to `main` does
  nothing.
- It repeats lint and the unit tests, then builds, signs and verifies the APK
  (`apksigner verify`), then git-cliff writes `CHANGELOG.md` back to `main` and the GitHub
  Release is created with `buy-my-way-vX.Y.Z.apk` and `buy-my-way-vX.Y.Z.apk.sha256`.
- **The emulator suite is not here.** `ci.yml` runs it on `dev`, and it never runs on `main`,
  so only a `dev` commit whose CI run is green may be merged and tagged. `/release` checks that
  before it merges; do not skip it.
- The `versionName` and `versionCode` come from `git describe`, so the workflow checks out with
  `fetch-depth: 0`. A shallow clone would build a release that calls itself `0.0.0-dev`.
- A bad release is fixed forward with the next patch version, never by moving a tag: the
  CHANGELOG, the Release and the `versionName` inside every installed APK all come *from* the
  tag, and that last one is already on somebody's phone. **No ruleset enforces this yet**
  (STATE.md open question 3) — unlike Eat My Way, this repository has none, so the discipline
  is the owner's to keep. Protecting `main` and `v*` is worth doing before 1.0.
- **The database rules are not deployed by the workflow.** If a release changes
  `firebase/database.rules.json`, publish them in the console *before* anyone installs the
  build — see *Database rules* above, and Phases 6 and 8b for what it costs not to.

### The in-app update check

The app looks at `https://api.github.com/repos/zyndata/buy-my-way/releases/latest` **when Listy
is shown and the last look was more than 24 hours ago** — in the foreground, with no worker and
nothing that wakes the device (STATE.md decision 108). A newer `X.Y.Z` than the running
`versionName` shows „Dostępna wersja X — Pobierz" on Listy; Ustawienia → „O aplikacji" →
„Sprawdź aktualizacje" forces the check without waiting a day, which is how to test it.

What it will not do: offer anything to a `0.0.0-dev` build, offer a draft or a pre-release, or
download from any URL that is not under `https://github.com/zyndata/buy-my-way/releases/`.
„Pobierz" hands the file to `DownloadManager` and then to the package installer; the first time,
Android asks the user to allow this app to install unknown apps
(`REQUEST_INSTALL_PACKAGES` — a Settings screen, never a runtime dialog).

So a Release must carry an APK asset whose name ends in `.apk`, or no phone will be offered it.
The `.sha256` beside it is for people, not for the app.

## Google Play

Not used (STATE.md decision 25). Releases are GitHub Releases only.

## Rollback

Sideload: install the previous Release's APK over the current one — same signature, lower
`versionCode`, so `adb install -r -d` is needed; a normal user installs the older APK from the
Release page after uninstalling. Data is
unaffected either way — it is in RTDB, and the on-device Room schema is migrated forward only,
so a rollback across a schema version has to be listed in the release notes as „requires
reinstall".
