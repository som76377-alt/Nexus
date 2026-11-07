# ADR-0002: PlaybackService owns RoomController

- Status: Accepted; implemented (CI and physical-device verification remain separate gates)
- Date: 2026-11-06

## Decision

The single RoomController instance belongs to PlaybackService alongside the existing ExoPlayer and MediaSession. ListeningRoomActivity observes controller state and forwards user intent.

## Implemented responsibilities

- Active room session attachment and detachment
- Explicit local MediaSession intent capture through a ForwardingPlayer adapter
- Playback observation and local command publication
- Realtime subscription and signal coalescing
- Authoritative snapshot reconciliation and revision-CAS conflict recovery
- Room heartbeat
- Clock calibration and recalibration
- Drift correction and guarded seek recovery
- Bounded reconnect and retry jobs

Chat remains a separate UI/data concern. Chat events update the chat view and never trigger playback reconciliation.

## Why

Playback and room coordination should survive Activity recreation and screen navigation while the service is alive. Activity destruction must not silently stop synchronization while audio continues.

## Constraint

Android can still destroy the process/service under platform lifecycle and resource conditions. Service ownership is not a promise of indefinite remote control or remote wake after process death. Two-phone behavior still requires physical-device acceptance tests.
