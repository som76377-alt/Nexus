# Nexus stack audit

Audit date: 2026-11-15
Audited ref: `integration/room-v2`
Audited commit: `ea2c51e5fe5bc995063d752d9bf400ce0442ad3e`

## Confirmed stack

- **Platform/language:** native Android application, Kotlin, one `:app` module. Compile SDK 36, target SDK 36, minimum SDK 24, Java 17.
- **UI toolkit:** Android framework Views built programmatically. The current source does **not** use Jetpack Compose or Compose Scaffold. Blueprint references to composables/Scaffold must be adapted to presentation-only View components and a single Activity-owned navigation/content host; do not migrate the UI toolkit merely to match terminology.
- **Entry/navigation:** `MainActivity` owns Home, Library and You screens by replacing content inside a `ScrollView`; it creates the bottom bar through `NexusUi.bottomNavigation`. Rooms is launched as a separate `ListeningRoomActivity`. This duplicates navigation ownership and prevents one stable bar across all four destinations.
- **Bottom bar and Rooms:** `MainActivity` owns `bottomNavigationHost`; `ListeningRoomActivity` independently creates and owns another bottom navigation view. In Rooms, `ListeningRoomActivity` sets `SOFT_INPUT_ADJUST_RESIZE` and places its bottom navigation at the bottom of a vertical root layout. The IME resizes that whole window/layout, so the bar can move upward when the keyboard appears. The precise pixel/bounds defect is not measured yet; the structural cause is confirmed.
- **Search/catalogue:** `SearchPanel` is a programmatic View using the existing `YoutubeCatalogRepository` / `NexusCatalog` path. Keep it as a black box for this redesign. No provider/extractor/NewPipe changes are in scope.
- **Images:** Coil 3 (`coil3.load`) for artwork and thumbnails.
- **Playback:** Media3 / ExoPlayer and MediaSession are owned by `PlaybackService`; UI callbacks use `PlaybackConnection`. Visual code must not touch this layer.
- **Local library:** `LocalLibraryStore` currently stores Saved and Recently Played metadata as JSON in Android SharedPreferences (`nexus_library`), not AndroidX Room. It stores canonical track URLs and display metadata, not temporary stream URLs. Playlists and Watch later are not implemented in this store.
- **Room backend:** Supabase REST/Realtime through the existing rooms API/repository and service-owned controller. The Rooms Activity observes room state and handles chat presentation. Do not change this path for visual work.
- **Caching:** the existing audio cache is playback-adjacent and currently exposed in You; it is owned by playback/cache classes. Keep it untouched unless a separately permitted task is approved.
- **Design assets:** five licensed font resources and existing vinyl/tonearm PNG assets are present. No verified CC0 leather/dark-stone/brushed-metal scan is registered yet.

## Verification

- Latest exact integration commit checked: `ea2c51e5fe5bc995063d752d9bf400ce0442ad3e`.
- Android CI: run [43698587696](https://github.com/som76377-alt/Nexus/actions/runs/43698587696), completed successfully on that exact SHA.
- Web room test: run [43698587670](https://github.com/som76377-alt/Nexus/actions/runs/43698587670), completed successfully on that exact SHA.
- No device screenshots, low-RAM measurements or two-device results were available during this audit. Those remain **UNVERIFIED**.

## Adaptation notes

The roadmap's Jetpack Compose terms are conceptual UI ownership requirements, not an instruction to introduce Compose. Implement equivalent View-based single-host navigation only if it can preserve existing room/playback ownership without changing playback or room transport. The blueprint's "Room tables" means AndroidX Room database tables; current persistence is SharedPreferences, so the current state must be migrated deliberately and tested rather than assumed to already exist.
