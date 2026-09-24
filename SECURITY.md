# Security model

## Assets and trust boundaries

Protect live classroom media, the right to view/control it and persistent role credentials. The Teacher phone, Admin phone and server operator are trusted endpoints. WSS/TLS protects network hops using the device's normal trust store; there is no permissive certificate verifier. The relay sees media. Rooted phones, a compromised host, unlocked physical access, a leaked setup key and external screen recording remain outside these protections.

## Provisioning, authentication and pairing

The operator generates two independent 32-byte credentials using Node crypto and provisions each only to its corresponding phone and server environment. This intentionally requires an operator setup key as well as the six-digit local code: a public endpoint cannot be claimed by the first unauthenticated visitor.

For each new WebSocket, the relay sends a random 32-byte nonce and a server-clock expiry 15 seconds ahead. The phone proves its role key with HMAC-SHA256 over `role:nonce:expires`. The nonce is consumed on the first attempt, used only on its socket and never accepted again. Comparison is constant-time when the lengths match. No raw key travels in the authentication message. Authentication is still protected by WSS against an active network attacker.

A successful connection receives a random session identifier and a ten-minute expiry. Authorization is bound to that authenticated socket; the identifier is informational, not a reusable bearer credential. Expiry forces a new nonce exchange. Every command is checked against the socket role and pairing/viewing state. No additional unsigned URL token grants access.

Only authenticated Teacher can create/reset pairing. A six-digit code lasts 120 seconds and permits at most five wrong guesses across the current pairing window. The authenticated Admin must present it. Successful pairing consumes it. The Teacher app exposes reset only behind a visible confirmation. A server restart drops pairing, requiring renewed Teacher interaction. There is no administrator remote-start message or reboot receiver.

## Limits and denial of service

At most one authenticated connection per role; new duplicate connections are rejected without evicting the existing one. At most 12 pending/connected sockets, 60 accepted sockets per minute globally, 20 control messages per second per socket, 4096 bytes per control message and 524288 bytes per media payload. Active media is capped at 2 MB per second. Heartbeat runs every 20 seconds; unresponsive connections are terminated. Session expiry and challenge deadlines are enforced by the server timer.

The relay disables WebSocket compression, rejects browser Origin headers, validates media headers/configuration, limits queues and requests new keyframes after congestion. These application limits are not a substitute for provider-level volumetric DoS protection. Because this deployment serves just two devices, limits are global; a public flood could deny availability without gaining viewing access.

## Device storage and visibility

Android Keystore creates the non-exported AES-GCM encryption key. A new random IV encrypts every saved value. Backups are disabled. The app does not log keys. No production credential exists in source or test fixtures. Test credentials are confined to test code and must never be used in deployment.

The Teacher service is non-exported, requires normal camera/microphone permission, uses camera/microphone foreground service types and returns START_NOT_STICKY. Both activity UI and notification provide local Stop. Notifications must be enabled for activation; the service checks permission/channel state periodically. Camera/microphone errors stop capture. Android microphone silencing is observed through the recording callback.

## Remaining limitations and deployment gates

- Possession of a role key grants that role; this is credential binding, not hardware attestation. A key copied to another phone can impersonate the role when no current role connection exists. Rotate lost/leaked keys. Do not give ordinary staff the Admin credential.
- The server operator can impersonate both roles and inspect media. Add independently reviewed end-to-end encryption if the operator is outside your trust boundary.
- Pairing is deliberately not durable or shared across server instances. Keep a single instance. Restart means re-pairing locally.
- No application PIN/biometric lock is added on top of Android's device lock. Use a secure lock screen. Anyone with an unlocked Admin phone can use its saved credential.
- Dependency lockfile and CI are included. Review dependency advisories and complete Android builds, lint, real-device privacy tests and audio/video tests before release. The source has not received an independent security audit.
- Actual timing, packet loss recovery, OEM background limits and MediaCodec implementations require phone testing. They cannot be established by server unit tests.

## Revocation

For pairing only: reset on Teacher and create a fresh code. For a lost or compromised device: change its server role secret, redeploy (disconnecting both sessions and clearing pairing), update that phone's replacement and pair locally again. Never submit real keys in bug reports.
