# Nexus Web Room Test Client

A lightweight browser participant for testing the existing Nexus listening-room backend. It is deliberately separate from the Android app and does not modify Android playback, search, background service, or updater code.

## What it can test

- Anonymous browser authentication using the same Supabase project as Nexus.
- Create a room in the browser and join it from Nexus on Android, or join a room created by Android.
- Two-member room capacity and membership heartbeat.
- Room-scoped chat in both directions.
- Realtime playback snapshots and REST polling fallback.
- Playback commands from the browser to the Android player: load a YouTube track, play, pause, and seek.
- Revision compare-and-swap behavior and room diagnostics.

## Important boundary

The browser is a room participant and command/diagnostics console, not a full port of the Android media stack. It does not run NewPipe Extractor or Media3, and it does not decode the same YouTube stream as the Android device. Therefore it can verify that commands reach Android and that Android state returns to the room, but it cannot prove two-device audio drift or sample-accurate synchronization. That final check still needs two Android players.

## Requirements

- Windows 10/11
- Node.js 22.12 or newer (Node 22 LTS) (includes npm)
- Chrome, Edge, or Firefox
- Internet connection

## Run locally

1. Download or clone the Nexus repository.
2. Open the `web-room-test` folder in File Explorer.
3. Click the folder address bar, type `cmd`, and press Enter.
4. Run:

   ```bash
   npm install
   npm run dev
   ```

5. Vite prints a local URL, normally `http://localhost:5173/`. The server binds to your own laptop only, not the local network. Open it in your laptop browser.
6. Keep the terminal window open while testing. Press `Ctrl+C` to stop the website.

The existing Nexus public Supabase URL and publishable key are used as safe defaults. The publishable key is intended for client-side use; the database still enforces authenticated room membership through RLS and RPC permissions. They are client-side credentials protected by the existing authenticated room RPCs and RLS policies. Never replace the publishable key with a service-role or secret key. If you want to configure them manually, copy `.env.example` to `.env.local` and edit the `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` values.

## First phone + laptop test

1. Install/open the current Nexus APK on your Android phone.
2. Start the web client on your laptop.
3. In the browser, choose **Create test room**. The browser creates its own anonymous Supabase identity and displays a room code.
4. In Nexus on the phone, open Listening Room → Join room and enter that code.
5. Confirm the Participants panel shows two members.
6. Send a message from the browser, then send one from Nexus. Both should appear on both devices.
7. On the phone, play a YouTube track using the normal Nexus app. The browser should show the title/state and a newer revision.
8. In the browser, enter a YouTube URL or 11-character video ID and choose **Send track**. The Android phone should receive the track command and resolve it through its existing NewPipe/Media3 path.
9. Use browser Play, Pause and Seek controls. Confirm the phone follows them. Then repeat in the opposite direction using the phone controls.
10. Briefly disconnect/reconnect the laptop network and observe the Realtime status, polling fallback, event log and whether state recovers.

You can reverse steps 3–4: create the room in Nexus and join it from the browser using the invite code.

## Troubleshooting

- **Anonymous sign-in disabled:** the existing Nexus Supabase project must have Anonymous Sign-Ins enabled in Authentication settings. It was enabled for the Android app's room flow.
- **Room code rejected:** use the current five-character code; rooms expire after one hour without activity.
- **Only one participant appears:** make sure both devices joined the same code. A room is limited to two members.
- **Chat works but Realtime says reconnecting:** the client continues polling every five seconds. Check browser internet access and Supabase Realtime status.
- **Browser commands do not load a track:** use a normal public YouTube video URL or its 11-character video ID. Android still performs the actual extraction; browser does not.
- **Playback state is visible but phone does not respond:** capture the browser Event log and Android logcat. The web tool can prove the backend command was accepted; Android logs show whether Media3 received/resolved/applied it.

## Security notes

- No service-role key or database password is used.
- Browser auth uses a separate anonymous user saved in that browser profile. Clearing browser data creates a new identity.
- Room membership, chat and playback remain governed by the existing Supabase authenticated RPCs and RLS.
- This is a test tool. Do not publish it as a public production app without a separate security review and an explicit user-access policy.
