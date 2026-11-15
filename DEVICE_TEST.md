# Nexus device validation

Run this only with a debug APK produced by a green Android CI run from the redesign PR. Do not use a release APK or replace the installed production build.

## Test devices
- Weaker Android 10 device (2 GB RAM): primary stability/performance target.
- iQOO Z9s Pro 5G: secondary comparison device.

## Navigation and keyboard
1. Open Nexus and switch Home → Search → Rooms → You → Home.
2. Confirm the bottom navigation stays at the same screen position and that each tab opens the intended screen.
3. Open Rooms, tap the message composer, show the keyboard, and check that the navigation remains visible/anchored while only the composer and chat area react to the keyboard.
4. Leave the room and re-enter Rooms. Confirm the create/join screen returns when no active room exists.

## Home, library and Search
1. Open Saved, History and Playlists from Home.
2. Add a track to Saved and Watch later from the track overflow menu.
3. Create a playlist, add a track to it, reopen it, and remove a track.
4. Open Search. Tap several genre/mood presets and confirm the existing result-selection/playback path is used.
5. On Home, open the app three times and tap Reroll twice. Compare the batches; note repeated tracks and whether the list shows a broad range of genres.

## Player and settings
1. Start a track, open the Player and confirm album art, title, seek, play/pause and close work.
2. Switch Reduce effects on, repeat Player open/close, and confirm the decorative motion stops while controls still work.
3. Switch haptics off and confirm controls continue to work without haptic feedback.
4. Enable Android battery saver and confirm decorative effects are suppressed.
5. Background the app, return, and confirm audio and room state are unchanged.

## Performance notes to record
- Device model / Android version / available RAM.
- Cold launch and first Home render.
- Home/Search scrolling: visible dropped/janky frames and whether thumbnails blur.
- Reroll duration and whether scrolling remains responsive while it loads.
- Memory pressure, app restarts and battery impact.
- Player start, seek, pause and resume behavior.
- Any navigation or keyboard position change.

## Result format
Record each device separately as PASS, FAIL or NOT TESTED. Attach screenshots or screen recordings for visual defects. Do not infer two-device synchronization from a single-device run.
