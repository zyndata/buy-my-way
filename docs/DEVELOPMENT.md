# Development

This project is developed from **two machines — Windows and Linux**. Everything needed to work
on it lives in the repository; nothing is configured per machine outside `local.properties`,
`~/.gradle/gradle.properties` and your own git identity.

## Setup (identical on both machines)

```bash
git clone https://github.com/zyndata/buy-my-way.git
cd buy-my-way
./gradlew assembleDebug            # gradlew.bat on Windows cmd; ./gradlew works in Git Bash
```

What has to be installed:

- **A JDK, 21 or newer**, on `JAVA_HOME`. CI uses Temurin 21; the Gradle build runs on any
  newer JDK too (Android Studio's bundled JBR is fine). The app itself is compiled to Java 17
  bytecode, so the JDK version does not change the APK.
- **Android Studio** (current stable) or just the **Android SDK**. The Gradle plugin downloads
  the compile platform (API 37) and build tools itself on the first build if they are missing,
  as long as the SDK licences are accepted (`sdkmanager --licenses`).
- Nothing else: the Gradle wrapper (9.7.1, checksum pinned in
  `gradle/wrapper/gradle-wrapper.properties`) fetches Gradle, and every library version is in
  `gradle/libs.versions.toml`.

`local.properties` (the SDK path) is written by Android Studio and is git-ignored — it is the
one file that legitimately contains an absolute path. Written by hand on Windows, escape the
colon and the backslashes, or Lint's `PropertyEscape` check fails the build:

```
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

On Linux it is simply `sdk.dir=/home/you/Android/Sdk`.

## What the build is made of

| | |
|---|---|
| Gradle / AGP / Kotlin | 9.7.1 / 9.4.1 / 2.4.20 (AGP's built-in Kotlin, plus the Compose compiler plugin) |
| SDK levels | minSdk 26, compileSdk and targetSdk 37 |
| UI | Jetpack Compose (BoM), Material 3, Navigation Compose, single activity |
| Firebase | BoM with `auth`, `database`, `messaging`; configured from the committed `app/google-services.json` |
| Data | Room (KSP) with the schema exported to `app/schemas` and committed, DataStore preferences, kotlinx.serialization |
| Checks | Android Lint with warnings as errors; Kotlin `allWarningsAsErrors` |

Versions are pinned in `gradle/libs.versions.toml` and recorded in STATE.md (decision 26).
Lint does not complain about newer library versions (decision 29): dependabot proposes them
once a month.

**Version numbers come from git** (decision 28). `versionName` is `git describe --tags` without
the `v` (`1.2.0`, or `1.2.0-3-gabc1234` three commits later, `-dirty` with uncommitted changes);
before the first tag it is `0.0.0-dev`. `versionCode` is `X·1 000 000 + Y·10 000 + Z·100` for
`vX.Y.Z`, plus the number of commits since the tag (at most 99). A shallow clone without tags
builds as `0.0.0-dev`, which is why CI checks out the full history.

## Gradle tasks — the interface to the project

| Task | What it does |
|---|---|
| `./gradlew assembleDebug` | Debug APK into `app/build/outputs/apk/debug/` |
| `./gradlew installDebug` | Build and install on the connected device or emulator |
| `./gradlew lint` | Android Lint; warnings are errors on our own code |
| `./gradlew testDebugUnitTest` | JVM unit tests (pure Kotlin: merge, parsers, categoriser) |
| `./gradlew connectedDebugAndroidTest` | Instrumented tests (Room DAOs, the repository, migrations, the Compose screen flows) on every device `adb` sees: a connected phone, the emulator, or both. It uninstalls the app afterwards, so the debug build's lists are gone; `installDebug` again to keep using it |
| `./gradlew assembleRelease` | Signed release APK — needs the signing properties below (Phase 10) |
| `npm run changelog` | Regenerate `CHANGELOG.md` from commits (git-cliff) |
| `npm --prefix firebase test` | Realtime Database rules tests against the Firebase emulator (`firebase/test/rules.test.mjs`). Run `npm --prefix firebase ci` once first |

CI runs `lint testDebugUnitTest assembleDebug`, `connectedDebugAndroidTest` on an API 35
emulator, and the rules tests, as three jobs, on every push to `dev`. Run the same before
pushing.

The rules tests need Node 22+ and a Java runtime (the database emulator is a Java program;
the JDK that builds the app will do). `firebase emulators:exec` downloads the emulator on the
first run and starts it under the project id `demo-buy-my-way`: a demo id needs no login and
never reaches the real project. Nothing in `firebase/` needs a Firebase account.

## Google Sign-In on a debug build

Sign in with Google checks the signing certificate of the APK against the Android OAuth clients
registered in the Firebase project. Each machine has its own debug keystore, so **each
machine's SHA-1 has to be registered** once:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

(PowerShell: the keystore is at `$env:USERPROFILE\.android\debug.keystore`.) Add the SHA-1 in
the Firebase console → Project settings → Your apps → Android app → *Add fingerprint*, then
download the refreshed `google-services.json` and commit it. Both machines' fingerprints stay
registered; a fingerprint is not a secret.

Registered so far: the Windows machine (`5A:DB:9A:F8:…:52:8D`). **The Linux machine's SHA-1 is
still to be added** at its first build (STATE.md open question 2). Until then a debug build
from Linux runs, but „Zaloguj się przez Google" in Ustawienia fails with „Nie udało się
zalogować" on it. Everything that works signed out still works.

`google-services.json` holds only public identifiers (project id, app id, the Android API key,
OAuth client ids). PLAN.md's *Security* section wants the API key restricted in Google Cloud
to this package and the registered SHA-1s; either way, what protects the data is the Realtime
Database rules.

## Signing a release locally (Phase 10)

Never in the repository. In `~/.gradle/gradle.properties`:

```
BUYMYWAY_KEYSTORE=/absolute/path/to/release.keystore
BUYMYWAY_KEYSTORE_PASSWORD=…
BUYMYWAY_KEY_ALIAS=…
BUYMYWAY_KEY_PASSWORD=…
```

CI reads the same four values from GitHub Secrets (the keystore as base64).

## Cross-platform rules

- **LF line endings** in the repository, enforced by `.gitattributes`; `gradlew.bat` is the
  one CRLF file. `git ls-files --eol` shows the state.
- **No absolute paths, no drive letters** anywhere but `local.properties`.
- **No platform-only scripts in the build path.** Anything that has to run on both machines is
  a Gradle task or a Node script.
- **File names are case-sensitive** on Linux and in CI; Windows will not tell you.
- The emulator: one AVD per machine with a **Google APIs / Play Store image** (Play services
  are needed for Sign-In and FCM), API 34 or newer — the Windows machine uses API 35. CI runs
  `connectedDebugAndroidTest` on API 35 `google_apis` x86_64 (STATE.md decision 38). A phone
  connected over `adb` works as well: Gradle runs the suite on every device `adb` sees.

## Two phones, two accounts (Phase 5)

Sharing and live changes are tested on the emulator against a fake server that mirrors the
rules (`SharingTest`), because the emulator has no Google account. What only two real phones
can show is measured by hand:

- **Check latency.** `app/src/androidTest/.../probe/TwoPhoneProbe.kt` runs inside the installed,
  signed-in debug app and is skipped everywhere else (CI included). Both phones share a list
  named „Pomiar". Install on each with `ANDROID_SERIAL=<serial> ./gradlew installDebug
  installDebugAndroidTest`: an install, not `connectedDebugAndroidTest`, which would uninstall
  the app and sign it out. Then start phone B, and then phone A:

  ```
  adb -s <B> shell am instrument -w -e probe b -e class dev.gorny.buymyway.probe.TwoPhoneProbe dev.gorny.buymyway.test/androidx.test.runner.AndroidJUnitRunner
  adb -s <A> shell am instrument -w -e probe a -e class dev.gorny.buymyway.probe.TwoPhoneProbe dev.gorny.buymyway.test/androidx.test.runner.AndroidJUnitRunner
  adb -s <A> logcat -d -s BuyMyWayProbe
  ```

  A ticks „ping N", B ticks „pong N" the moment it sees it, and A times the round trip on
  its own clock, 20 times (the echo of STATE.md decision 16). The `RESULT` line gives the
  median and p95.
- **Invite link, airplane mode, the animation**: by hand, and recorded in STATE.md.
- **Photos (Phase 6)**: `PhotoSyncTest` covers them between phones on the fake server, and
  `PhotoProcessorTest` turns a 12-megapixel JPEG with EXIF into the stored WebP. By hand, on two
  phones: a photo taken on A shows in B's row, full screen too, and „Usuń zdjęcie" removes
  `/photos/{listId}/{itemId}` (check in the console). The emulator's camera app works for the
  camera path; the Photo Picker needs an image in the emulator's gallery first
  (`adb push some.jpg /sdcard/Pictures/`, then open Google Photos or reboot so MediaStore
  sees it).

`ANDROID_SERIAL` picks one device when several are connected. Without it, Gradle's
connected tasks use every device `adb` sees.

## Dictation (Phase 7)

`DictationTest` (JVM) covers the parser over ~75 utterances, and `DictationFlowsTest` drives
the review sheet by handing the view model the `VoiceEvent`s a phone would produce, so neither
needs a microphone. What needs a real one:

- **Speaking Polish into it.** An API 35 emulator has a recognizer (its on-device service
  answers, and the error path „Nie słyszę — spróbuj bliżej mikrofonu." can be seen there by
  tapping „Zatrzymaj" in silence), but no audio goes in, so the words themselves are checked on
  a phone: tap the mic in the add bar, say „dwa kilo ziemniaków, mleko, masło i chleb", and
  four lines should appear with their departments.
- **Offline.** Turn off Wi-Fi and mobile data with the Polish pack installed (Ustawienia
  systemowe → Języki → Mowa / Rozpoznawanie mowy); dictation should still work, because the
  app always asks with `EXTRA_PREFER_OFFLINE` (STATE.md decision 75).
- **A phone with no recognizer** has no mic button at all. `adb shell pm query-services -a
  android.speech.RecognitionService` says whether a device has one.

## Reference material

`D:\Work\eat-my-way\android\` (outside this repository) holds decompiled third-party apps kept
for ideas — Listonic above all, for its category handling, voice input and live-change UX.
Nothing is copied from them; their premium, ads and leaflet features are out of scope by
decision.
