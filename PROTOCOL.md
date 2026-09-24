# Online Teachers protocol v1

One binary-capable WebSocket per phone at `/ws`. Public connections must use WSS. Text frames contain UTF-8 JSON control messages. Binary messages contain one media packet. No WebRTC, transcoding or recording is used.

## Authentication

1. Relay → new socket: `{"type":"challenge","nonce":"<base64url>","expires":<epoch-ms>}`.
2. Client → relay: `{"type":"auth","role":"teacher|admin","proof":"<base64>"}`.
3. `proof = Base64(HMAC-SHA256(key UTF-8 bytes, role + ":" + nonce + ":" + decimal expires))`.
4. Relay → client: `{"type":"authenticated","sessionId":"<random>","expires":<epoch-ms>}`.

The challenge is single-use, expires in 15 seconds and belongs to its socket. Authenticated sessions expire after ten minutes; reconnect and reauthenticate. Role keys are generated as 32 random bytes encoded base64url (43 characters); HMAC uses the encoded string's UTF-8 bytes, not the decoded 32 bytes. The sessionId is a label; the authenticated socket carries authorization and it is not accepted on another connection.

## Pairing and state

Teacher `create_pair` → `pair_code` with `code` and `expires`. Code validity: two minutes, five failed guesses maximum. Admin `pair` with `code` → `paired` to both. Teacher `reset_pair` ends viewing, clears pairing and sends `reset_done` to both. Pairing survives socket reconnection but not a relay process restart.

Teacher sends `status` with `state` in `connecting`, `ready`, `error`. Relay sends Admin `status` with state `offline`, `connecting`, `ready`, `viewing` or `error` and boolean `paired`. Offline indicates a connection absence, not proof of the reason for it.

## Controls

| Sender | Type | Effect |
| --- | --- | --- |
| Admin | `start_view` | Requires pairing and Teacher ready; begins media transmission |
| Admin | `end_view` | Stops media; does not start/stop Teacher's local activated service |
| Admin | `switch_camera` | Toggles front/back within the already active service |
| Admin | `pause_video` / `resume_video` | Pauses video transport; audio continues |
| Admin | `keyframe` | Resends codec config and requests H.264 IDR |
| Admin | `reduce_bitrate` | Reduces video target 20%, minimum 250 kbps |
| Either | `ping`, numeric `at` | Relay echoes `pong`, same `at`; measures relay RTT |
| Teacher | `status`, `create_pair`, `reset_pair` | As above; no Admin equivalent |

Relay sends Teacher `viewer` with boolean `active`, and Admin `viewing` with boolean `active`. It may send `error` with a non-sensitive `message`. Unknown or unauthorized commands close the socket. WebSocket ping/pong also maintains a 20-second heartbeat independently of JSON pings.

## Binary media header

All multi-byte numbers are **big-endian**. Header size: **24 bytes**. Total message size: 24 + payload length, no trailing data.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 2 | Magic bytes `0x4f 0x54` (`OT`) |
| 2 | 1 | Version: `1` |
| 3 | 1 | Kind: `1` video, `2` audio |
| 4 | 1 | Flags: bit 0 codec config, bit 1 keyframe; other bits forbidden |
| 5 | 3 | Reserved, all zero |
| 8 | 4 | Unsigned sequence number, wrapping modulo 2^32 |
| 12 | 8 | Nonnegative signed 64-bit presentation timestamp, microseconds |
| 20 | 4 | Unsigned payload length, 1–524288 bytes |
| 24 | N | Payload |

Teacher uses one sequence counter across video/audio. Configuration packets use timestamp zero. Media timestamps come from the capture/encoder clock and are passed to the decoder; they are not wall-clock timestamps or an end-to-end latency measurement.

### Codec configuration

A CONFIG packet contains UTF-8 JSON, maximum 16384 bytes. CSD values are Base64:

Video: `{"mime":"video/avc","width":854,"height":480,"rotation":90,"csd":["<csd-0>","<csd-1>"]}`

Audio: `{"mime":"audio/mp4a-latm","sampleRate":44100,"channels":1,"csd":["<csd-0>"]}`

Video dimensions are the chosen supported camera/encoder size, not necessarily 854×480. Rotation is the camera sensor orientation for an upright, portrait-mounted Teacher phone. CSD is copied directly from encoder output format. H.264 packets use the MediaCodec output framing (including its start codes); AAC packets are raw AAC access units, not ADTS. Android decoders receive the corresponding CSD when configured.

Send configuration before media, on viewing start, after reconnect and after camera reconfiguration. IDR is requested at viewing/reconnection/camera-change recovery; encoder keyframe interval is one second.

## Buffering and recovery

Teacher sends no media unless an authenticated viewing session is active. Teacher and relay use a 256000-byte queue threshold: they stop adding stale data beyond that threshold and wait for a new video keyframe. A single frame can temporarily exceed that threshold, but payloads are capped. Codec configs are retained in memory. The relay resends configuration before a recovery IDR and requests bitrate reduction/keyframes when congested. The decoder queue has a maximum of 30 pending packets, discards overload and requests keyframes; audio playback uses nonblocking writes and a bounded AudioTrack buffer.

This is low-latency best-effort playback, not a precise lip-sync or jitter-buffer implementation. TCP head-of-line delay remains possible. Validate lip sync and recovery on the real networks and phones before accepting deployment.

## Resource and connection limits

Control: 4096 bytes; 20 messages/second/socket. Media: 524288-byte payload; 2 MB/second active stream. WebSocket deflate off. Initial authentication deadline: 15 seconds. Sessions: ten minutes. Native heartbeat: 20 seconds, close on a missed response at the next tick. Max sockets: 12, global connection accepts: 60/minute. Only one authenticated socket of each role. Duplicate role connections are rejected rather than replacing the old client.
