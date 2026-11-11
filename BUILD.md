# Build

## One-time setup

1. Install **JDK 17+** — `java -version` must work.
2. Install **Android SDK 35**:
   - easiest: install [Android Studio](https://developer.android.com/studio), open this project once, let it sync;
   - or command line:
     ```bash
     cmdline-tools/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
     ```
3. Point Gradle at the SDK — either export `ANDROID_HOME=/path/to/Android/Sdk`
   or create `local.properties` in the project root:
   ```
   sdk.dir=/path/to/Android/Sdk
   ```
   (Android Studio writes this automatically.)

## Build

```bash
./build.sh          # debug APK
```

or directly:

```bash
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (minified, unsigned)
./gradlew test               # unit tests
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Device must run Android 7.0 (API 24) or newer. Emulators without Google Play services work.

## Sign a release (optional)

```bash
keytool -genkeypair -v -keystore carris.jks -keyalg RSA -keysize 2048 -validity 10000 -alias carris
apksigner sign --ks carris.jks --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## No Gradle wrapper? (missing `gradlew`)

```bash
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```
