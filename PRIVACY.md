# Privacy disclosure

Online Teachers shares a classroom phone's live camera and microphone with one paired administrator. Use it only in a classroom where the people present have been informed and appropriate permission has been obtained.

## What the Teacher phone shows

The launcher icon remains visible. Camera and microphone permissions use standard Android dialogs. Android notification permission is requested normally where required. Staff must activate monitoring from a visible screen. The foreground notification says **Monitoring active**, shows the current status and offers **Stop monitoring**. Android's privacy indicators and camera/microphone switches remain under Android's control.

While activated, camera and microphone capture remain active even in Ready state. This is disclosed on the Teacher screen and notification. Encoded media is sent only while the paired Admin is viewing. Local preview contains video only and is displayed while the Teacher screen is open.

Reboot, force-stop, Stop, permission loss, blocked notifications or termination do not silently restart capture. A staff member must reopen Teacher and activate it again. No hidden permission grants, notifications, icons, boot startup, accessibility control, notification suppression or background privilege escalation are included.

## Data and storage

- **On each phone:** server address and that device's setup key, encrypted using an AES-GCM key in Android Keystore. Android app backup is disabled. Clearing app data removes these settings.
- **In transit:** live H.264 video, AAC audio and control/status messages over WSS.
- **At the relay:** two configured role credentials in environment secrets, live socket/session state, pairing code and codec configuration in memory. Pairing is forgotten on relay restart. The application logs no media, keys, pairing codes or authentication proofs.
- **Recordings/analytics:** none implemented. No storage, contacts, location, screen capture or analytics SDK permissions are requested.

The relay decrypts each WSS hop and can access stream contents. This is not end-to-end encrypted against the server operator. Hosting providers may retain infrastructure/connection logs under their own policies. The administrator or another device could externally record the screen; this app cannot prevent that. Notification dismissal and battery handling vary by Android version; the app does not override the user's system controls.

Stop at any time using the Teacher screen or active notification. Reset pairing locally to revoke the current pairing. Rotate a compromised role key in hosting settings to revoke the credential itself.
