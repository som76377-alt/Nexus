# Pixel-art UI in the browser: implementation notes

**Purpose:** Build small, legible, deliberately pixelated interactive art for NEXUS without replacing the artwork with a flattened decorative image or generic CSS shapes.

## Findings

### 1. Choose the representation for the job

For a small decorative plant that needs exact geometry, layering, and simple motion, inline SVG is a practical starting point. SVG keeps each leaf, vine tile, and blossom editable in source; groups can be layered and animated independently.

Use an actual low-resolution raster sprite or a low-resolution Canvas when the art is fundamentally a pixel canvas, frame animation, or a larger collection of game sprites. When enlarging raster pixel art, use nearest-neighbour-style scaling (CSS image-rendering: pixelated or crisp-edges) and test at the actual display size. Browser resampling can still look uneven when CSS dimensions do not map cleanly to device pixels.

SVG's shape-rendering="crispEdges" is a hint, not a magic pixel-art filter. It cannot turn soft, arbitrary vector curves into well-designed pixel art. The artwork itself must be composed of deliberate square clusters.

### 2. Design the sprite before styling the card

Recommended sequence:

1. Establish the outer silhouette and branch direction.
2. Place the main vine and its branches on a regular grid.
3. Add large leaf clusters that attach to those branches.
4. Use a limited palette with distinct dark outline, shadow, base, and highlight roles.
5. Add tiny flowers as accent pixels, not as competing focal points.
6. Inspect at the actual phone-size display scale; remove details that collapse into noise.

The useful principle is **clusters, not confetti**: adjacent pixels should describe an edge, plane, vein, or shadow. Randomly scattered colors and repeated identical icons tend to read as decoration rather than coherent natural forms. A limited palette and a readable silhouette do more than adding more gradients.

### 3. Keep grid logic consistent

The current Biology study uses a 4 CSS-pixel tile grid. Stems follow orthogonal, hand-authored routes; leaf and flower sprites are arrays of tile-sized rectangles. Rotations are restricted to quarter turns so tile edges remain aligned to the grid. On narrow screens, the art is repositioned or the surrounding layout changes instead of applying an arbitrary fractional scale.

If the sprite is converted to raster or Canvas later, keep its native art resolution separate from its display dimensions and test the scaling at the intended device-pixel ratio. The image-rendering property is mainly relevant to raster images and canvases; for the hand-built SVG, grid-aligned geometry and shape-rendering="crispEdges" are the primary choices.

### 4. Animate the growth, not the whole card

Use a small number of intentional states: stem tiles appear in order down the vine, leaves sprout in groups, and blossoms arrive last. Discrete opacity changes and grid-aligned translation preserve the blocky visual language better than smoothly scaling each shape from zero (which often makes pixels blur or gives the plant a rubbery feel).

Keep the default artwork visible even when animation is disabled. Honor prefers-reduced-motion and ensure the button remains usable without animation. Avoid perpetual movement unless it communicates something useful.

### 5. Separate visual and functional acceptance tests

**Visual checks**
- The bush reads as one connected climbing plant.
- Leaves remain recognizable as pixel clusters, not smooth generic icons.
- Flowers are small accents with a consistent palette.
- The intended top-left origin and trailing growth are obvious.
- Silhouette and text remain clear at phone size.
- Pixel edges remain deliberate when the viewport changes.

**Functional checks**
- The Biology control is a real keyboard-accessible button.
- Tap/click updates its accessible pressed state and status text.
- Growth can be replayed.
- Reduced-motion preference reveals the complete plant without animating it.
- The page remains usable at narrow widths.

A successful commit or valid HTML is not proof of visual quality. The rendered page must be inspected at target size and compared with the art direction. If live-browser inspection is unavailable, report that limitation explicitly.

## Source reading

- MDN, [SVG shape-rendering](https://developer.mozilla.org/en-US/docs/Web/SVG/Reference/Attribute/shape-rendering): crispEdges is a rendering hint to emphasize edges; it does not design the art.
- MDN, [CSS image-rendering](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/image-rendering): explains pixelated and crisp-edges scaling for raster artwork, including practical scaling limitations.
- MDN, [Crisp pixel art look](https://developer.mozilla.org/en-US/docs/Games/Techniques/Crisp_pixel_art_look): explains low-resolution artwork and nearest-neighbour-style enlargement, including uneven appearance at non-integer scaling.
- MDN, [prefers-reduced-motion](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-motion): documents respecting motion preferences.
- Wayline, [Making a game-ready sprite](https://www.wayline.io/learn/pixel-art/4): practical guidance on silhouette, consistency, and checking sprites at actual size.
- Wayline, [Color and limited palettes](https://www.wayline.io/learn/pixel-art/2): practical guidance on value separation and controlled palettes.

## Current implementation

The prototype lives in biology-button.html. It builds all the foliage from editable SVG rectangles generated from authored grid shapes and branch routes. No flattened bush image, icon pack, emoji, or remote visual asset is used. The research notes are design constraints and test criteria, not a claim that a live-browser visual review has already passed.
