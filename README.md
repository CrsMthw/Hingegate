# Hingegate

A prank for Android foldables. Every so often when you unfold the phone, Hingegate makes the inner screen look
like its hinge just failed: a black ink blot on the crease, a bright multi-coloured line running from it to the
edge of the screen, and a thin line running down the fold. Fold the phone and it is gone again.

It was inspired by photos of a certain new foldable whose inner screens all seem to crack the same way at the
hinge.

## Two ways to show the crack

- **Over everything**: the crack is drawn over every app, like a real display defect. Your wallpaper stays as it
  is. Needs the "Display over other apps" permission and runs a small foreground service.
- **Wallpaper**: Hingegate becomes your live wallpaper (you pick an image for the main screen and one for the
  cover screen) and the crack is drawn on the wallpaper only.

You choose how often it happens: a chance per unfold, and an optional "never more than N clean unfolds in a row".

## Works on

Any foldable on Android 12 or later. The hinge position comes from Android's window manager where the phone
reports it, and is estimated from the screen shapes where it does not.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
