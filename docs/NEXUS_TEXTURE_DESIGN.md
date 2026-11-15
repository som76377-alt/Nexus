# Nexus texture design — real materials, restrained use

**Status:** Texture direction locked; asset integration follows this study.
**Identity:** The Analog Listening Atelier / The Art of Listening.
**Principle:** Real assets. Real restraint. Deliberate composition. Functional software.

This is a material system for the Android interface, not a collection of decorative backgrounds.

## 1. Visual composition

- The default canvas stays Archive Ivory (`#F1E8D6`); dense lists, search results, chat and settings stay clean and quiet.
- A single large material field may anchor an editorial feature. Avoid a grid of textured cards.
- Material is used to suggest a physical object: paper sleeve, book cloth, or a small metal detail. It must never reduce text contrast.
- Keep the original material colour where possible. Do not recolour a photographed material merely to force it into the palette.
- Use asymmetry, generous negative space and clear type hierarchy. No decorative copy, random badges, fake depth or ornamental UI chrome.

## 2. Approved source candidates

All selected downloadable assets must be checked at the individual asset page before import. The source sites' general licence does not replace asset-level provenance.

| Material role | Candidate | Source and licence | Decision |
|---|---|---|---|
| Olive book-cloth / sleeve | **Book Pattern** | [Poly Haven](https://polyhaven.com/a/book_pattern), CC0 | Approved candidate. Photo-based cotton weave; use as one editorial feature surface, not a global tile. |
| Woven textile accent | **Rough Linen** | [Poly Haven](https://polyhaven.com/a/rough_linen), CC0 | Approved candidate. Page identifies photography by colormass; reserve for a small Library or Room accent. |
| Cotton-rag paper | **Paper 001** | [ambientCG](https://ambientcg.com/view?id=Paper001), CC0 | Not approved yet. The asset page labels its technique “Approximation”; do not present it as a verified photographic scan. Keep the main ivory canvas flat until a genuinely photographed paper source is verified. |
| Oxidized copper | **Metal 058 B** | [ambientCG](https://ambientcg.com/view?id=Metal058B), CC0 | Rejected for this direction: the page labels it “Procedural”. Do not use. |
| Vellum | No verified candidate yet | Search Poly Haven / ambientCG and inspect the individual asset page | Pending. Do not fake translucency with gradients or blur. |
| Patinated metal | No approved copper-specific photo scan yet | Poly Haven photo-based library | Pending. Rusted steel is not copper; do not mislabel or substitute it. |

Poly Haven describes its texture library as photoscanned and its assets as CC0. ambientCG's downloadable assets are CC0, but its catalogue includes different creation techniques; choose only assets whose individual technique meets this project's photo-based requirement.

## 3. Screen placement

### Home
- Archive Ivory remains the calm base.
- Use Book Pattern as one broad, restrained sleeve-like feature field behind the editorial discovery feature.
- Keep the heading, search controls, chips and result rows on clean surfaces.
- No repeated textured card backgrounds.

### Library
- Use Rough Linen only as a small section/header accent, never under dense track rows.
- Album artwork remains the dominant colour and texture source.

### Now Playing
- Keep the matte dark stage visually quiet; the record and tonearm remain the hero.
- Do not add a generic texture overlay over the vinyl or artwork.
- Avoid procedural shadow/lighting effects as substitutes for photographed materials.

### Room
- At most, a small textile accent in the room identity/header.
- Chat bubbles, composer, connection status and controls remain plain, high-contrast functional surfaces.

### Controls
- Buttons, tabs, sliders, search fields and navigation stay clean and legible.
- No textured button fills, bevels, embossed borders or fake metallic gradients.

## 4. Android asset handling

- Import only the colour/albedo image needed for the 2D UI; do not bundle full PBR map sets when Android never uses them.
- Prepare screen-sized WebP variants from the original source image, retaining the original master and source URL in the asset record.
- Never decode an 8K master into a phone-sized view. Use appropriately sized resources and normal Android bitmap/resource caching.
- Do not repeat a texture at a visible scale where seams or obvious repetition become apparent.
- Keep a source record containing asset name, creator, source URL, licence, original filename, original resolution, derived filename and any processing performed.
- No runtime downloading, no generated noise, no procedural fibres, no shader-made material, no AI-generated texture.

## 5. Import gate

A texture may enter `app/src/main/res/drawable-nodpi/` only after:
1. The individual asset page and licence are verified.
2. Its creation technique is compatible with the photo-based requirement.
3. The original file is obtained and its dimensions/format are checked.
4. A phone-sized derivative is created without inventing surface detail.
5. The source and transformation are recorded.

Until an actual binary is present, the app must not reference its resource ID. This avoids broken builds and avoids claiming that an asset has been integrated when it has not.

## 6. Non-goals

- No blanket paper texture on every screen.
- No procedural grain, noise, fibre strokes, ridges, bevels, embossing or fake lighting.
- No PBR runtime renderer for simple 2D surfaces.
- No change to playback, search, room sync, persistence or updater logic.
