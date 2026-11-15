# Nexus stack audit

Audit date: 2026-11-15
Audited source ref: integration/room-v2
Baseline commit: ea2c51e5fe5bc995063d752d9bf400ce0442ad3e

## Confirmed stack

- Native Android application, Kotlin, one :app module. Compile SDK 36, target SDK 36, minimum SDK 24, Java 17.
- UI toolkit is Android framework Views built programmatically. The current source does not use Jetpack Compose or Compose Scaffold. Blueprint references to composables/Scaffold are adapted to presentation-only Views and shared material/navigation tokens.
- MainActivity originally owns Home, Library and You by replacing content inside a ScrollView; its bottom bar is built by NexusUi.bottomNavigation.
- Rooms is a separate ListeningRoomActivity that owns its own copy of bottom navigation and observes the PlaybackService-owned room controller. Room originally used SOFT_INPUT_ADJUST_RESIZE with the bar at the bottom of a vertical root, causing the bar to shift when the IME resized the window.
- Search uses SearchPanel and the existing NexusCatalog.repository().search interface. Search execution and stream resolution remain unchanged.
- Images load with Coil 3.
- Playback uses Media3/ExoPlayer and MediaSession owned by PlaybackService; UI commands go through PlaybackConnection.
- Current local Saved/History persistence uses JSON in Android SharedPreferences (nexus_library), not AndroidX Room. It stores canonical track URLs and display metadata, not short-lived stream URLs. The redesign branch extends this compatible format with Watch later, custom playlists and local play timestamps.
- Supabase REST/Realtime and room state remain in existing room APIs/service controller. No room transport code was changed in this redesign.
- The existing playback/audio cache remains in the playback layer. The redesign removed its management controls from You but did not change the cache implementation.

## Bottom-bar root cause and redesign-branch behavior

The structural issue is confirmed: two Activities independently create navigation bars; Room also used adjustResize. On the redesign branch the Rooms bar stays visible after joining, and the Activity uses adjustNothing plus WindowInsetsCompat to move/pad only the chat composer. The bar is therefore no longer part of the resizable chat layout.

UNVERIFIED: pixel-equal bottom-bar bounds with the keyboard open have not been tested on device or screenshot instrumentation. Rooms remains a separate Activity; the roadmap's strict single-root-Activity requirement is not fully met in this pass.

## Current branch architecture additions

- New View-based MaterialSurface draws a flat token placeholder; no screen loads texture files directly.
- NexusDesignSystem defines dark canvas/support surfaces, three radius roles and a single type-family facade.
- NexusExperienceSettings stores Reduce effects and haptics preferences; effects are disabled on low-RAM and battery-saver conditions.
- LocalLibraryStore stores Saved/History/Watch later/playlists in SharedPreferences; Room DB migration is still outstanding.
- RecommendationMixer is a deterministic 60/40 taste/exploration mixer over configured JSON buckets. Cache/history also use SharedPreferences until Room migration.
- TasteProfileComputer aggregates local play timestamps into up to 100 artist scores with a 30-day half-life. BlendGenerator builds shared-first, alternating 50-track mixes. Pair sharing remains a stub pending approved Supabase table/RLS migration.
- NowPlayingView no longer runs an infinite disc animation; finite spin and tilt are presentation-only.

## Verification

- Baseline Android CI run 43698587696 succeeded on baseline commit ea2c51e5fe5bc995063d752d9bf400ce0442ad3e.
- Baseline Web room test 43698587670 succeeded on the same baseline commit.
- No redesign-branch CI result has been obtained yet.
- No weak-device screenshots, frame-jank measurements, memory measurements or two-device results are available. All are UNVERIFIED.
