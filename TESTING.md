# Verification report

Prepared 2026-09-24. This is source code plus build/deployment automation, not a claim of a tested production Android release.

## Executed successfully

| Check | Result |
| --- | --- |
| `npm install` / dependency resolution | Dependencies installed and lockfile generated; subsequent ws security update installed successfully |
| `npm run build` | Passed, TypeScript strict compilation |
| `npm run lint` | Passed, ESLint on source and tests |
| `npm test` | 14 passed, 0 failed |
| `npm run test:integration` | 1 passed, 0 failed using real local HTTP and WebSocket connections |
| `npm audit --omit=dev --audit-level=high` | 0 reported production dependency vulnerabilities at execution time |
| Android XML parsing | All 13 manifest/resource XML files parsed |
| Manifest privacy review/check | Teacher service non-exported, camera/microphone FGS types; no boot receiver; Admin only requests INTERNET |
| Gradle launcher shell syntax | Passed `bash -n gradlew` |
| Gradle wrapper authenticity | Bundled official 8.13 wrapper JAR matches the published SHA-256 |

Core tests cover frame round trips, malformed/truncated/oversized data, codec config validation, authentication, nonce replay, duplicate role rejection, pairing requirement, role separation, pairing expiry/guess limit, backpressure recovery, local reset, Teacher disconnect, session expiry and control flooding. The network integration test runs the actual server entrypoint and verifies `/health`, challenge authentication, six-digit pairing, binary relay and End Session stopping relay.

The initial ws dependency audit identified a high-severity advisory. It was upgraded to 8.21.3 and compilation, lint, all tests and the production audit were rerun successfully. No vulnerable initial ws version remains in package.json or package-lock.json.

## Not verified here

- Android Gradle compilation, Android Lint and the included Kotlin/JUnit tests. JDK 17 is present; Android command-line tools and the official wrapper were downloaded, but Java's configured network routes could not access Gradle/SDK dependency servers. SDK Platform 36/Build Tools could not be installed and a Gradle distribution could not be resolved by Java. This was an environment build blocker, not a successful build.
- No debug or release APK was produced. Use Android Studio or the included GitHub Actions job to build both. Neither that remote job nor its result has been run/verified on your GitHub account.
- Camera/MediaCodec/AudioTrack behaviour, lip sync, actual resolution/fps, Android 11–16 phone compatibility, denied permissions, screen-lock continuation, microphone privacy switch events, reboot/force-stop behaviour and OEM battery policies need physical-device testing.
- Koyeb deployment, HTTPS termination, two different internet networks and reconnect quality on a real mobile network were not exercised. The Dockerfile was not built in this environment.
- Windows PowerShell/batch launchers were inspected but not executed.

The included loopback integration test uses small synthetic codec payloads to validate transport, not decodable real camera footage. Passing that test does not validate the Android encoder/decoder path.

## Device acceptance checklist before use

1. Build both APKs with JDK 17/SDK 36; run Gradle unit tests and both app lint tasks. Resolve any platform/compiler issues before installing.
2. On a clean Teacher install, deny camera, microphone and notifications separately. Confirm activation refuses and Android Settings offers a normal recovery path.
3. Grant normally and activate while Teacher is visible. Confirm local preview, real Ready status, privacy indicators where supported, notification and Stop action.
4. Pair only the intended Admin. Try wrong/expired pairing codes, an unpaired Admin and a second role connection; none may receive a stream.
5. View over different networks. Check clear picture, intelligible audio, acceptable lip sync, front/back switching, pause/resume, playback mute and full screen in both Admin orientations.
6. Minimize and lock Teacher. Verify the notification remains visible/available according to Android policy and viewing continues on the tested device. Check battery restrictions explicitly.
7. Use Teacher Stop and notification Stop. Verify camera/microphone release and Admin media ends promptly. Admin must not remotely restart capture.
8. Reboot and force-stop Teacher. Confirm capture does not restart. Reopen and activate locally.
9. Disable the camera/microphone privacy toggle and notification channel. Confirm visible failure/stop and no silent permission bypass.
10. Briefly interrupt each network. Confirm exponential reconnect, fresh authentication, codec config and keyframe recovery. Check sound/video do not build an increasing delay.
11. Reset pairing locally during viewing. Verify viewing ends and a new code is necessary. Restart the relay and confirm its in-memory pairing is lost.
12. Run at least one 30-minute session on each intended phone model to assess thermal, battery and network behaviour. Decide acceptability from observed results, not the target bitrate alone.
