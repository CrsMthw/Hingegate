# Changelog

All notable changes to Hingegate are recorded here. Entries are never folded or trimmed.

## Unreleased

### 2026-10-31 — build 2
- The look (after testing build 1 on the Fold 8): the band from the blot to the right edge is much thicker — the
  thickest band seen on build 1 (≈ 13 dp) is now the thinnest, and every crack is 13–30 dp (a large splash 26–30 dp,
  like the reference photos). The fringes, the violet separator and the glow are about 2.2 times build 1's, and the
  white cores take all the rest of the thickness, so a thick band is solid light: between its lines at most a
  hairline of wallpaper shows, and only now and then (as in photo 6) the bottom fringe stands apart across a
  visible gap — the two together never more than about a third of the band. The ink blot now scales with the band:
  a small dot is 0.7–1.0 times the band's thickness across, a large splash 1.4–1.8 times, with its spikes, specks
  and rim to match; every spike grows out of the blot, never beside a dent in it. The trails up and down the fold
  are unchanged. A crack saved by build 1 still loads and shows once at its old size. 20 new JVM tests (187 in
  all): the 13–30 dp bounds over 10 000 seeds, no gap wider than a third of the band and at least two thirds of it
  solid, the lone fringe's odds, the cores filling a thick band, the large splash's band, the blot's proportions,
  every spike starting inside the blot, determinism, a build 1 crack loading, and the largest crack fitting both
  inner screens and the cover-screen preview.
- Real wallpaper on Samsung foldables (build 1, checklist item 34: a visible change after some unfolds, "cracked
  copy set late", and the captured wallpaper forgotten after folding and unfolding with the app open). The forgotten
  capture came from a read at the unfold that One UI answered for the COVER screen (whose wallpaper is live): it
  looked like a new live wallpaper and made the mode let go. Now every read names the inner screen
  (`FLAG_SYSTEM | 4`, which One UI answers for the inner panel whatever the fold state — read from the phones' own
  framework), and nothing is decided from a read until the inner screen has been in use, with no fold or unfold,
  for 1.5 s; anything that asks earlier is checked at the end of that time — also when it asks while an earlier
  check is running. "Use my current wallpaper" tapped during it waits for it.
- Real wallpaper: a wallpaper the user changed is only acted on when a second read half a second later agrees;
  until then nothing is staged or put back. A recapture that fails (a live wallpaper, All files access off, an
  unreadable file) keeps the original and tries once more before letting go, so one odd read can never show "Not
  captured yet". An automatic recapture only keeps the id the check saw, and never one of Hingegate's own copies —
  also when the app was killed in the middle of setting one (each set is recorded before it is made). Nothing is
  ever set over a wallpaper no settled check has seen.
- Real wallpaper: the copies are prepared while the inner screen is in use — the clean copy (the original's own
  file when it already fits the screen, so it is pixel-identical) and the NEXT crack's copy — and handed to the
  system as finished files at the fold, so a quick unfold no longer catches the change. For that the next crack is
  chosen ahead (and kept across restarts); the odds, the guarantee and "Crack on the next unfold" are unchanged, and
  the other two modes look exactly as before. Copies are drawn one at a time. With nothing prepared yet the copy is
  drawn on the spot as before.
  A copy staged at the fold that only finished after the unfold is logged as late, so the log matches what is seen.
- Real wallpaper: logcat diagnostics for testing on the phone (tags `HingegateSwap` and `HingegateCrack`): every
  check with both postures, whether settled, the id read with and without the inner-screen flag and the decision;
  every set with the target, timing, flags, how it was set, the time to find or prepare the image, to record the
  set and to make it, and the id; every prepare with its render and encode times. A live wallpaper from another app
  is now recognised as live (the app may see which apps offer live wallpapers); a wallpaper change on the cover
  screen no longer triggers a check. 48 new JVM tests (235 in all): the settle clock and its scheduled check (one
  asked for while another fires is kept), the two-read confirmation, the failed-recapture rules, a capture's
  verdict, the guard and the last look before a set, a set cut short by the process dying, the prepared images'
  names, plan and cleanup, when the original serves as the clean copy, the pre-chosen crack keeping the odds, the
  late-set logging, and a build 1 state still loading.
- Real wallpaper: a copy staged while folded is confirmed on the spot by reading the inner screen's id (which One UI
  answers for the inner panel even while folded), so a wallpaper the user then sets for the inner screen from the
  cover screen is recognised as theirs and captured at the unfold, instead of being taken for Hingegate's copy and
  replaced by the old one at the next fold (build 1's known limit). 3 more JVM tests (238 in all).

### 2026-10-31 — review fixes
- Real wallpaper: the id the system reports after a set counts as Hingegate's copy only when it changed and is
  not the original's, and on Samsung every set waits for the next check to confirm it — so a copy that never
  reached the inner screen is logged as such ("the copy Hingegate set did not reach the inner screen") instead of
  making the user's own wallpaper count as Hingegate's. A miss is not retried in a loop; a late read-back still
  puts the clean copy back. Saved states where the original's id was wrongly remembered heal on their own.
- Real wallpaper: an exception thrown by the system (for example One UI rejecting the inner-panel flag) is logged
  as a failed set or read instead of crashing the app on every fold.
- Real wallpaper: folding while a changed wallpaper is being read no longer drops the original and stops the
  mode; nothing is staged from the old image and the new one is read at the next unfold.
- Real wallpaper: on foldables with one wallpaper for both screens (most phones other than Samsung) the card,
  and the README, now say that the cover screen shows the copy too; the earlier entry's "the cover screen is not
  touched" holds for Samsung only.
- Hinge estimate: until both screens have been seen, the inner screen is no longer taken for the cover (the
  preview and an un-reported hinge picked the wrong axis on Fold 6 / Fold 7 before the first fold), and the
  estimate is made in the panel's natural orientation, so it is right on a rotated screen too.
- The look: the glow is a halo around the band (it was hidden under the cores and hazed the band's gaps); the
  trails start at the band's edges instead of crossing its fringes beside a small blot; the overlay makes up for
  its 0.8 window opacity, so it looks like the preview and the wallpaper modes.
- Live wallpaper: a hidden engine never keeps a cleared crack (or the other screen's image) in its last frame,
  so a clean unfold cannot flash an old crack.
- UI: the Live wallpaper card speaks only about the inner screen (and asks to unfold to check), keeps "Set as
  wallpaper" available, and follows folds; the top bar says "crack not visible: finish the setup below" when the
  chosen mode cannot show a crack, and no status at all until the settings are read; "Show crack now" needs the
  app switched on; the preview's flicker no longer replays after a rotation or fold; a capture refused for an
  unknown screen size says so; mode labels wrap instead of being cut off at large font sizes.
- Pure decisions moved out of the Android code and tested: the swapper's id rules, the put-back labelling, the
  log sentence for each set and the streak an unfold's log line shows. 38 new JVM tests (167 in all).

### 2026-10-31 — Real wallpaper mode
- A third mode, "Real wallpaper": the user's own still wallpaper is captured (with All files access, the only way
  Android hands it over) and, on a cracked unfold, the inner screen's home wallpaper becomes a copy of it with the
  crack baked in; the clean copy goes back afterwards. On Samsung (one wallpaper per panel) the next unfold's copy
  is staged while the phone is folded, using the inner-panel flag; elsewhere the crack is set at the unfold and
  the clean copy at the fold. The lock screen and the cover screen are not touched.
- Pre-rolling in every mode: the next unfold is decided at the fold (and again when the odds or "crack on the next
  unfold" change while folded); the streak and the log are still written at the unfold.
- If the user changes the wallpaper, the new one is captured before anything else; a live wallpaper or a failed
  read makes the mode let go of the wallpaper. Hingegate's own sets are recognised by their ids, so its cracked
  copy is never taken for a new original. Leaving the mode, switching off or "Put my wallpaper back" restores the
  clean copy.
- Every swap is logged with the flags passed and the id the system returned (staged ahead, set, set late, put
  back, captured, recaptured, failed with the reason).
- Every permission (All files access, Display over other apps, Notifications) is explained in plain words on its
  card and again in a confirmation dialog before Settings or the system prompt opens.
- The mode choice is three-way (Over everything / Live wallpaper / Real wallpaper); the Real wallpaper card notes
  that this mode does not follow screen rotation. The foreground service is now `HingeWatchService` and also keeps
  the Real wallpaper mode alive.
- 54 new JVM tests (129 in all): pre-rolling, the swap planner, wallpaper ids, panel frames and flags, the new log
  lines, the service rule and the capture messages. The release lint's one accepted warning is ScopedStorage on
  MANAGE_EXTERNAL_STORAGE.

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
