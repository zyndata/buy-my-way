# Development

This project is developed from **two machines — Windows and Linux**. Everything needed to work
on it lives in the repository; nothing is configured per machine outside `local.properties`,
`~/.gradle/gradle.properties` and your own git identity.

> Phase 1 fills this file in with the exact versions and the first Gradle tasks. Until then it
> records the rules the scaffold has to satisfy.

## Setup (identical on both machines)

```bash
git clone https://github.com/zyndata/buy-my-way.git
cd buy-my-way
# Android Studio (current stable) with the Android SDK; JDK 21 (the one bundled with Studio
# is fine — point JAVA_HOME at it for command-line builds).
./gradlew assembleDebug            # gradlew.bat on Windows cmd; ./gradlew works in Git Bash
```

`local.properties` (the SDK path) is written by Android Studio and is git-ignored — it is the
one file that legitimately contains an absolute path.

## Gradle tasks — the interface to the project

| Task | What it does |
|---|---|
| `./gradlew assembleDebug` | Debug APK into `app/build/outputs/apk/debug/` |
| `./gradlew installDebug` | Build and install on the connected device or emulator |
| `./gradlew lint` | Android Lint; warnings are errors on our own code |
| `./gradlew testDebugUnitTest` | JVM unit tests (pure Kotlin: merge, parsers, categoriser) |
| `./gradlew connectedDebugAndroidTest` | Instrumented tests on the connected device/emulator (from Phase 3) |
| `./gradlew assembleRelease` | Signed release APK — needs the signing properties below (Phase 10) |
| `npm run changelog` | Regenerate `CHANGELOG.md` from commits (git-cliff) |
| `npm --prefix firebase test` | Realtime Database rules tests against the Firebase emulator (from Phase 5) |

CI runs `lint testDebugUnitTest assembleDebug`, the rules tests and the instrumented suite on
every push to `dev`. Run the same before pushing.

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
- The emulator: one AVD per machine, API 34, Google APIs image (Play services are needed for
  Sign-In and FCM). CI uses the same image.

## Reference material

`D:\Work\eat-my-way\android\` (outside this repository) holds decompiled third-party apps kept
for ideas — Listonic above all, for its category handling, voice input and live-change UX.
Nothing is copied from them; their premium, ads and leaflet features are out of scope by
decision.
