# Online Teachers — visible monitoring edition

Two native Android apps and a Node.js/TypeScript relay for one classroom phone and one administrator phone, including when they are on different internet networks. Source delivery; see TESTING.md for exactly what has been verified. Do not treat untested Android source as a production-certified release.

## Normal, transparent behaviour

- The Teacher phone requests camera, microphone and notification permissions through Android. Permissions are never granted secretly.
- Staff tap **Activate Monitoring** while the Teacher app is visible. A foreground notification says **Monitoring active**, with a **Stop monitoring** action.
- The Teacher screen shows the real connection/viewing state and a local camera preview. Android privacy controls and indicators are preserved.
- **Stop**, reboot, force-stop, service termination or permission removal ends capture. Only someone at the Teacher phone can activate it again. There is no boot receiver, remote launch, accessibility service or hidden launcher.
- During an activated period, the paired Admin can start/end viewing without a separate approval prompt every time. The camera and microphone remain active while ready, including when no Admin is viewing. The Teacher screen and notification explicitly disclose this. No media is transmitted until a viewing session starts.
- The Admin app requests no camera, microphone or storage permissions. It plays the incoming stream. Leaving the Admin app ends viewing.
- The apps and relay do not write recordings. WSS encrypts each network hop. The relay can see media; this is not end-to-end encryption.

## Contents

| Folder/file | Purpose |
| --- | --- |
| `teacher-app/` | Visible activation, permissions, foreground capture, pairing, local Stop |
| `admin-app/` | Secure connection, pairing, viewing, camera switch, pause and playback mute |
| `shared/` | Compose theme, encrypted settings, WebSocket client, Camera2/MediaCodec/AudioTrack, binary framing |
| `server/` | Single-pair authenticated WebSocket relay, tests, Docker deployment |
| `docs/SETUP.md` | Beginner setup and Koyeb deployment |
| `PROTOCOL.md` | Wire format, authentication, controls and media flow |
| `SECURITY.md` / `PRIVACY.md` | Security boundaries and user disclosure |
| `TESTING.md` | Verification results and device acceptance checklist |
| `.github/workflows/verify.yml` | Server checks, Android builds and downloadable debug APK artifacts |

## Build the Android apps

Use Android Studio with JDK 17 and Android SDK Platform 36. Pinned build tools: AGP 8.13.2, Kotlin 2.2.21 and Gradle 8.13. minSdk is 30 (Android 11); target/compile SDK is 36 (Android 16). This deliberately uses a compatible stable toolchain rather than an unverified newest plugin upgrade.

1. Extract this ZIP. Open the **online-teachers** folder in Android Studio.
2. Set the Gradle JDK to **17**. Install SDK Platform **36** and SDK Build Tools **35.0.0** through SDK Manager. Let Gradle sync finish.
3. The official Gradle wrapper JAR is included. To check/download the Gradle distribution, run `gradlew.bat --version` on Windows or `./gradlew --version` on Linux/macOS, then sync again. The included launchers can download the genuine Gradle wrapper and verify its pinned SHA-256. The distribution checksum is also pinned.
4. Select `teacher-app` or `admin-app`, connect the corresponding Android phone, then Run.

Windows command prompt:

```bat
gradlew.bat :shared:testDebugUnitTest :teacher-app:lintDebug :admin-app:lintDebug :teacher-app:assembleDebug :admin-app:assembleDebug
```

Linux/macOS:

```sh
chmod +x gradlew
./gradlew :shared:testDebugUnitTest :teacher-app:lintDebug :admin-app:lintDebug :teacher-app:assembleDebug :admin-app:assembleDebug
```

APK output locations after a successful build:

- `teacher-app/build/outputs/apk/debug/teacher-app-debug.apk`
- `admin-app/build/outputs/apk/debug/admin-app-debug.apk`

Alternatively push the extracted repository to GitHub, open **Actions → Build and test**, and download **Online-Teachers-debug-APKs** after the Android job succeeds. The included workflow has not been run on your account. Release signing is intentionally not configured; keep signing keys outside Git.

## Relay quick start

Install Node.js 24, then:

```sh
cd server
npm ci
npm run keys
npm run build
npm run lint
npm test
npm run test:integration
```

`npm run keys` prints two independent 32-byte credentials. Save them privately. Put `TEACHER_KEY` and `ADMIN_KEY` into your hosting service's secrets and the corresponding setup key into each phone. Never paste actual keys into Git, issues or public screenshots. Copy only the value after `=` into a phone.

For a local server, create an untracked `.env` using `.env.example` and launch:

```sh
node --env-file=.env dist/index.js
```

The raw relay speaks HTTP/WebSocket internally. Public access must pass through HTTPS/WSS termination, such as Koyeb. Both Android builds accept only `wss://.../ws`; there is deliberately no insecure debug exception. For development use a trusted TLS reverse proxy. Local network integration tests use loopback WS directly.

## Upload to GitHub

Run inside the extracted `online-teachers` folder:

```sh
git init
git add .
git commit -m "Add visible Online Teachers apps and relay"
git branch -M main
```

Create an empty GitHub repository, add its URL as `origin`, and push. `.gitignore` excludes local SDK paths, caches, dependencies, build outputs, signing keys and `.env` files. `server/package-lock.json` is included.

## Operating limits

Exactly one authenticated Teacher and one authenticated Admin may connect. A duplicate role connection is rejected. Pairing is in memory: restarting/redeploying the relay requires a new code from the Teacher phone, even if that phone remains locally activated. Use **one** server instance, not horizontal scaling.

The initial camera target is 854×480 at about 15 fps and 650 kbps. Camera2 chooses the nearest supported size (often 848×480 or 640×480), with a maximum of 1280×720. Audio is AAC-LC, mono 44.1 kHz, 48 kbps. Congestion reduces video bitrate down to 250 kbps; it resets on a new local activation. Actual frame rate, foreground longevity, audio/video timing and camera behaviour require validation on your phones. Mount the Teacher phone upright in portrait; Admin viewing supports portrait/landscape and full screen.

Latency shown in the Admin app is **round-trip time to the relay**, not end-to-end camera latency. QR pairing, recording, student apps and multiple viewers are not included. Six-digit pairing is implemented. Preview begins after visible activation rather than opening the camera before permission and consent.

## Reference documentation

- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Compatible Android build toolchain: https://developer.android.com/build/releases/agp-8-13-0-release-notes
- Gradle checksums: https://gradle.org/release-checksums/
- Koyeb monorepos: https://www.koyeb.com/docs/build-and-deploy/monorepo
- Koyeb Git deployment: https://www.koyeb.com/docs/build-and-deploy/deploy-with-git
