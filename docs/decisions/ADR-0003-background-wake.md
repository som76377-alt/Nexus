# ADR-0003: No remote wake after process death in v1

- Status: Accepted for v1
- Date: 2026-11-06

## Decision

V1 accepts that a remote room command cannot reliably wake a Nexus process after Android has destroyed its service/process. On next app or media-session activation, the client reconnects and reconciles the newest room snapshot.

## Why

- A Media3 MediaSessionService supports ongoing playback but is not an always-running daemon.
- onPlaybackResumption is a system-requested restoration hook, not a remote command wake-up mechanism.
- FCM high-priority data delivery introduces configuration, authorization, delivery and battery-policy concerns that are not necessary to establish reliable foreground/active-session synchronization.

## Revisit when

Remote PLAY while the app process is absent becomes an explicit product requirement. If so, design and test authenticated FCM room-wake messages separately; do not repurpose the old release-update push service.
