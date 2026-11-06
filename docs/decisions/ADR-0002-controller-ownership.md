# ADR-0002: PlaybackService owns RoomController

- Status: Accepted; implementation pending
- Date: 2026-11-06

## Decision

The single RoomController instance belongs to PlaybackService alongside the existing ExoPlayer and MediaSession. ListeningRoomActivity observes controller state and forwards user intent.

## Responsibilities owned by RoomController

- Active room session attachment and detachment
- Playback-change observation and local command publication
- Realtime subscription and signal coalescing
- Snapshot reconciliation and revision conflict recovery
- Room heartbeat
- Clock calibration and recalibration
- Drift correction and guarded seek recovery
- Bounded reconnect and retry jobs

Chat can remain a separate UI/data concern, but chat events must not trigger playback reconciliation.

## Why

Playback and room coordination should survive Activity recreation and screen navigation while the service is alive. Activity destruction must not silently stop synchronization while audio continues.

## Constraint

Android can still destroy the process/service under platform lifecycle and resource conditions. Service ownership is not a promise of indefinite remote control or remote wake after process death.
