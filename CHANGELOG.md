# Changelog

All notable changes to Hingegate are recorded here. Entries are never folded or trimmed.

## Unreleased

### 2026-10-31 — the app
- Posture detection for any book or flip foldable: display-size learning (display 0 swapping panels, as on
  Samsung and Pixel) with Window Manager's FoldingFeature as the fallback, tracked per attached window and resolved
  only on real changes; the posture memory survives restarts, so the first unfold after install still counts.
- Hinge position from Window Manager where the phone reports it, else estimated from the inner and cover screens'
  shapes; the crack is pinned to the panel's natural frame and turns with the hardware.
- Photo-matched crack: a black blot (usually a small dot, sometimes a spiky splash with pale specks and a dark-blue
  rim) on the crease, a band of white/icy cores with pink, green, cyan and lavender fringes (sometimes split by a
  dark violet line) to the screen edge with a soft glow, a lime-yellow trail down the fold and a dark hairline up
  it. A short display-glitch flicker as it appears, then steady.
- Trigger: chance per unfold (5–100 %), "never more than N clean unfolds in a row" (0–10), "crack on the next
  unfold", "show crack now" and "clear crack"; the crack clears on the next fold. Detection log of the last 40
  folds and unfolds.
- Two modes: over everything (a not-touchable overlay window, capped at the system's touch-through opacity, added
  by a specialUse foreground service only while a crack shows; restarted after boot and updates) and live
  wallpaper (separate images for the main and cover screens, the crack under the icons, a sample crack in the
  picker's preview).
- One-screen Material 3 Expressive UI with dynamic colour and edge-to-edge, two columns on the unfolded screen:
  live preview drawn by the real renderer, master switch, mode choice and setup, odds, try-it buttons, detection
  status and the log. Adaptive launcher icon with a monochrome layer.
- 75 JVM unit tests for the posture resolver, hinge estimate, panel transform, crack generator and geometry,
  trigger, flicker, wallpaper image maths, settings rules and UI status decisions.

### 2026-10-31 — project skeleton
- Gradle 9.8.2, AGP 9.5.0, Kotlin 2.4.21, Compose alpha BOM 2026.10.01 (Material3 1.5.0-beta02), min SDK 31,
  target/compile SDK 37 (37.2).
- Release signing from `local.properties`; CI runs unit tests and the full release lint.
