# Room-sync acceptance plan

## Goal

Make two-device listening responsive and stable without correcting one phone by needlessly restarting the other. Keep the existing Media3, Supabase, OkHttp and coroutines stack. Do not add a second audio engine or a new ordering system.

## Rules

1. The database playback revision and action ID are authoritative.
2. Local user actions are captured at the MediaSession command boundary; remote corrections bypass that emitter.
3. A stale CAS command is not automatically replayed over a newer revision.
4. New-track preparation is bounded. Prepared-track resume does not enter the new-track barrier.
5. If a peer is not ready within the bounded window, a ready phone starts locally and publishes a RECOVERY anchor.
6. Buffering is not playback progress and must not trigger drift correction.
7. Realtime signals are coalesced; REST reconciliation repairs missed signals and reconnects.
8. Chat and presence updates never become playback commands.
9. Use elapsed realtime for local intervals and calibrated server time for shared anchors.
10. Keep device wake, notification, signing and session-identity protections intact.

## Automated checks

Run Android lint, the complete JVM unit-test suite and debug APK assembly on the exact candidate commit. Run the web-room-test build as a separate check. Review the test report, not just the green summary.

## Physical-device acceptance

Use two Android phones and record diagnostics from both:

- New track from both initiator directions.
- Pause and resume repeatedly, including lock-screen and Bluetooth controls.
- Seek while playing and while buffering.
- Rapid skip/play/pause sequences; latest intent must win.
- One phone on a slower or interrupted network; local buffered playback must not be stalled by the peer.
- Disconnect/reconnect Wi-Fi or mobile data; the returning phone must adopt the latest revision.
- Keep the screen locked for five minutes; verify audio, media controls, CPU/Wi-Fi behavior and room recovery.

Record command-to-remote-response time, timeline drift, buffering duration, seek-correction count, reconnect time and crashes. CI cannot replace physical playback logs.
