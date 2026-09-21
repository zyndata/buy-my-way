# Phase 0 spike: runbook

Throwaway. This directory is a standalone Gradle build, outside the app's build path, and is
deleted when Phase 0 closes (PLAN.md, Phase 0 task 2). `push/` stays: Phase 9 builds on it.

What it answers:

1. Can account **B**, holding only `drive.file` (+ `drive.appdata`), list, read and update a
   file that account **A**'s copy of the app created and shared with B?
2. Does `appDataFolder` work for `prefs.json`?
3. RTDB op latency phone → phone (20 samples), Drive `files.get` of 20 kB (10 samples).
4. Apps Script → FCM push latency, cold and warm.

You need two Google accounts (A = owner, B = a household member), two phones with Play
services (or one phone and one emulator with a Google APIs image; latency figures then
need to come from real phones on mobile data), and USB debugging.

## 1. Google Cloud / Firebase (A's account, one time)

These are the real project settings. Only the RTDB rules are temporary.

1. <https://console.firebase.google.com> → *Add project* → `buy-my-way`. No Google Analytics.
   Plan: **Spark**.
2. *Build → Realtime Database → Create database* → location **europe-west1** → start in
   **locked mode**. Then *Rules*: paste `spike/database.rules.json` and publish. That opens
   only `/spike` to signed-in users. Revert to locked when the spike ends (step 6).
3. *Build → Authentication → Get started → Sign-in method → Google → Enable*.
4. *Project settings → General → Add app → Android*, package `dev.gorny.buymyway`, and add the
   **debug SHA-1** of each machine:
   - Windows: `5A:DB:9A:F8:A5:CA:AC:CC:15:E0:A4:94:F1:BE:80:E5:2A:49:52:8D`
   - Linux: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1`

   Adding a fingerprint creates the Android OAuth client. Download `google-services.json`
   but **don't commit it yet**. Phase 1 commits it with the scaffold.
5. <https://console.cloud.google.com> (same project):
   - *APIs & Services → Library*: enable the **Google Drive API** (FCM API and Identity
     Toolkit are already on through Firebase).
   - *Google Auth Platform* (OAuth consent screen): External, app name "Buy My Way",
     **Testing** status, **test users** = A, B and the rest of the household. Data access
     (scopes): `…/auth/drive.file`, `…/auth/drive.appdata`.
   - *Credentials*: note the **Web client** id that Firebase created ("Web client (auto
     created by Google Service)"). That's the `serverClientId`.
6. Fill `spike/spike.properties` (copy `spike.properties.example`) with the API key, app id,
   sender id, database URL and Web client id from `google-services.json` / the console.

## 2. Apps Script push sender

1. <https://script.google.com> → New project "Buy My Way push". Paste `push/Code.gs`; in
   *Project settings* tick "Show appsscript.json" and paste `push/appsscript.json`.
2. *Project settings → Google Cloud Platform project → Change project* → the **project
   number** of `buy-my-way` (Firebase → Project settings → General). This is what lets
   `ScriptApp.getOAuthToken()` call FCM without a service-account key.
3. *Script properties*: `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID` = `buy-my-way-c3949`, `FCM_TOKEN`
   (from the phone, step 3.9 below).
4. *Deploy → New deployment → Web app*, execute as **me**, access **Anyone**. Authorise. Put
   the `/exec` URL into `spike.properties` as `pushEndpoint`.

## 3. Run the spike

Build and install on both phones (from `spike/`, with `local.properties` pointing at the SDK):
`./gradlew installDebug`. Watch with `adb logcat -s BMW-SPIKE`. Every result is also on screen.

1. **A**: *Sign in* (account A) → *Authorize Drive* → consent to both scopes.
2. **A**: type B's email → *A1 create* → *A2 share*.
3. **B**: *Sign in* (account B) → *Authorize Drive*. The granted scopes line must show only
   `drive.file` and `drive.appdata`.
4. **B**: *B1 handoff* (reads the folder/file ids A left in RTDB) → *B2 list* → *Read file*
   → *B4 update*. **This is the verdict.** Note each result: success, or the HTTP code and
   message (`404 File not found` is the "no" answer).
5. **B**: *B5 sharedWithMe* (only for information: can B find the folder without being
   given its id?).
6. **A**: *Read file*. Is B's `"writtenBy":"B"` there, and did `version` move?
7. **A** and **B**: *appdata* (create, list, read `prefs.json`).
8. **Latency**: Wi-Fi off on both phones (mobile data). **B**: *RTDB responder* on. **A**:
   *RTDB ping x20*. Copy the `RTDB RTT` and `one-way` lines. Then **A**: *Drive 20 kB x10*.
9. **Push**: on one phone *Copy FCM token* → paste into the script property `FCM_TOKEN`.
   - **Cold**: after the script has been idle for 30 min or more, *Push to self* once. Repeat on
     two or three occasions if you can.
   - **Warm**: *Push to self* five times, ~10 s apart → *Push stats*.
   - **Background**: swipe the app away, trigger a push another way (e.g. from the other
     phone after pasting its token). Note whether the notification arrives and how late.
10. **Day 8** (the Testing-mode question): on day 8 or later, *Authorize Drive* and
    *Push to self* again without re-consenting anything. Note whether either one asks for
    consent again or fails.

## 4. Send back

Paste the log lines from steps 3.4–3.10 (or `adb logcat -s BMW-SPIKE -d > spike.log`), plus:
the public ids (project id, project number, Web client id, Android app id, the RTDB URL, the
Apps Script `/exec` URL) and anything in the consoles that surprised you. They go into
STATE.md as decisions.

## 5. Close

- RTDB rules back to `{ "rules": { ".read": false, ".write": false } }` (Phase 5 writes the
  real ones).
- In A's Drive, delete `Buy My Way/spike`. In both accounts, *Manage Drive apps* shows the
  hidden app data; it can stay.
- `git rm -r spike/`.
