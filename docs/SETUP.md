# Setup: two phones and one relay

## 1. Generate private keys

Install Node.js 24 on your computer. From `server/`, run `npm ci`, then `npm run keys`.

Keep the generated `TEACHER_KEY` and `ADMIN_KEY` private. These are different, random device credentials, not ordinary passwords. Do not run this again when simply restarting the server: use the same stored keys. To revoke a lost phone, replace that role's key in the server environment, redeploy, enter the new key on the replacement phone and pair again.

## 2. Deploy the relay to Koyeb

1. Push the extracted source folder to a GitHub repository.
2. Create a Koyeb **Web Service** using that GitHub repository.
3. Select the Dockerfile builder. Set **Work directory** to `server` and **Dockerfile location** to `Dockerfile` (relative to that work directory).
4. Choose one instance. Use the Free instance if your account/region offers it and its current limits suit you. Free availability, idle behaviour and bandwidth quotas are hosting-provider terms, not guaranteed by this project.
5. Add environment variables `TEACHER_KEY` and `ADMIN_KEY` as secrets. Set `PORT=8000`.
6. Expose port `8000` as HTTP and route `/` to it. Set an HTTP health check at `/health` on the same port.
7. Deploy. Wait for the service to become healthy.
8. If the assigned hostname is `example.koyeb.app`, open `https://example.koyeb.app/health`. It should return `{"ok":true}`.
9. Enter `wss://example.koyeb.app/ws` in both apps. Do not use `https://` in the app's server field.

Koyeb terminates HTTPS/WSS; the container listens internally without its own certificate. This ZIP contains a Dockerfile, not a guessed `koyeb.yaml` schema. The health endpoint discloses no camera status or credentials. Restart/deploy erases in-memory pairing; staff must create and enter a new code.

## 3. Activate the Teacher phone

1. Install and open **Online Teachers Teacher**.
2. Enter the WSS server address and the **Teacher** key value.
3. Read the live monitoring disclosure, then tap **Activate Monitoring**.
4. Android asks for camera, microphone and (Android 13+) notification permissions. Grant them normally.
5. Tap **Activate Monitoring** again once the permissions are granted.
6. Confirm the local preview, real status, camera/microphone indicators where Android provides them, and the **Monitoring active** notification.
7. Tap **Show one-time pairing code**. It expires after two minutes. Pairing only works once per code and is limited to five wrong guesses.

If notifications are disabled or the monitoring channel is blocked, use the app's **Open Android permission / notification settings** button to enable them. Monitoring refuses to operate with these notifications disabled.

The camera and microphone stay active in Ready state so a paired administrator can start viewing without remote-starting a capture service. This uses battery; the phone's screen and notification say so. Plug in the classroom phone when appropriate and keep it ventilated. No automatic battery-optimization exemption is requested.

## 4. Pair and view on the Admin phone

1. Install **Online Teachers Admin**.
2. Enter the same WSS address and the **Admin** key value; tap **Connect securely**.
3. Enter the six-digit code shown on the Teacher phone, then tap **Pair phones**.
4. Once Teacher Ready is shown, tap **Start live viewing**.
5. Switch camera, mute local playback, pause/resume video, or enter full screen. Muting does not disable the remote microphone. Video pause leaves audio playing.
6. **End session** stops media transport. The Teacher remains visibly activated until local Stop is pressed. Leaving/locking the Admin app disconnects it and ends viewing.

## 5. Stop, reset and reconnect

- Teacher **STOP MONITORING** or notification **Stop monitoring**: releases camera/microphone and disconnects the Teacher. Admin cannot reactivate it remotely.
- Reboot, force-stop or Android killing the service: open Teacher and activate locally again.
- Brief connection loss: the still-activated service retries with exponential backoff. An Admin still on screen may resume its requested viewing session when both links recover.
- New administrator: tap **Reset pairing on this phone** on Teacher, confirm, generate a fresh code and pair again. Rotate the Admin server key as well if the old administrator should lose credentials.
- Lost Teacher phone: rotate the Teacher server secret, redeploy and set up the replacement. Never copy the Admin key to a Teacher phone.
- Delayed duplicate-connection error after a network loss: the old connection can remain until heartbeat timeout (up to approximately 40 seconds), then retry succeeds. A current legitimate viewer is never evicted by a new duplicate.

## Troubleshooting

**Offline:** verify `/health`, matching server hostname, correct role key, notifications and local Teacher activation. Android cannot distinguish a phone that is powered off from one that was force-stopped, so the Admin message says local activation *may* be required.

**No video/audio:** stop, check Android camera/microphone privacy switches and permissions, close another app using the camera, then activate again. Test both front/back cameras. Follow the device checklist in TESTING.md before relying on the installation.

**Choppy stream:** verify each phone's upload/download connection. TCP WebSocket transport can stall under loss; the relay drops queued stale video and requests a new keyframe but cannot make TCP behave like a real-time datagram transport.

**Build failure:** inspect the first Android/Gradle error, ensure JDK 17 and SDK 36 are installed, and allow the dependency download to finish. No Android APK is claimed to be tested unless TESTING.md explicitly records a successful build.
