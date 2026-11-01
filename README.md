# Hingegate

*Giving emotional support to our sheep.*

Hingegate is emotional support from Android foldable owners to a certain fruit's flock. Now and then when you
unfold your phone, the inner screen gets a cracked-hinge look: a black ink blot on the crease, a bright
multi-coloured band from it to the edge of the screen, a bright line down the fold and a dark line up it. Fold the
phone and it is gone.

Nothing on your phone is broken. The crack is drawn, not earned, and your hinge is fine. It always was.

## Ewe are not alone

Android foldables have been folding for years. The fruit flock has only just joined us, and settling in is never
easy. Its members may, for reasons we are far too polite to mention, be feeling a little alone with their
crease.[^1] They should not have to. No sheep should crease alone.

Hingegate was not inspired by photos, which can be unkind, but by the fruit foldable itself: a device we hold in the
highest regard and honour with the sincerest form of flattery. We will not dwell on the core issue. Nor is this a
joke at anyone's expense: some people have had quite enough expense already. Call it shear solidarity.

## Screenshots

Taken on the Pixel Fold emulator. Every crack is different.

### Solidarity, as rendered

On the home screen, over other apps, and over Hingegate itself.

<table>
  <tr>
    <td><img src="assets/screenshots/crack-home.jpg" width="400"/></td>
    <td><img src="assets/screenshots/crack-home-splash.jpg" width="400"/></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/crack-clock.png" width="400"/></td>
    <td><img src="assets/screenshots/crack-contacts.png" width="400"/></td>
  </tr>
  <tr>
    <td colspan="2" align="center"><img src="assets/screenshots/crack-over-hingegate.png" width="400"/></td>
  </tr>
</table>

### The app, unfolded

Light and dark.

<table>
  <tr>
    <td><img src="assets/screenshots/app-unfolded-light.png" width="400"/></td>
    <td><img src="assets/screenshots/app-unfolded-dark.png" width="400"/></td>
  </tr>
</table>

### The app, on the cover screen

Light, dark, the modes and About.

<table>
  <tr>
    <td><img src="assets/screenshots/app-cover-light.png" width="200"/></td>
    <td><img src="assets/screenshots/app-cover-dark.png" width="200"/></td>
    <td><img src="assets/screenshots/app-cover-modes.png" width="200"/></td>
    <td><img src="assets/screenshots/app-cover-about.png" width="200"/></td>
  </tr>
</table>

## Three ways to show support

- **Over everything**: the crack is drawn over every app, like a real display defect, and your wallpaper stays as
  it is. Needs "Display over other apps" and runs a small foreground service.
- **Live wallpaper**: Hingegate becomes your live wallpaper (you pick an image for the main screen and one for the
  cover screen) and the crack is drawn on the wallpaper only, under your icons. No permission needed.
- **Real wallpaper**: your own still wallpaper stays, and on a cracked unfold the inner screen's home wallpaper is
  swapped for a copy of it with the crack drawn in; the clean copy goes back afterwards. Needs a still (not live)
  wallpaper and "All files access", and runs the same small foreground service. The crack is drawn for the way the
  phone opens, so it does not follow screen rotation. On Samsung foldables only the inner screen's home wallpaper
  changes; on foldables with one wallpaper for both screens, the cover screen shows the copy too. The lock screen
  is not touched.

## Permissions

No wool is pulled over anyone's eyes: every permission is explained in plain words on its card, and confirmed in a
dialog, before Hingegate opens Settings or a system prompt.

- **Display over other apps** (Over everything): the crack ignores touches, so your taps go through to the app
  underneath, and nothing on your screen is read.
- **All files access** (Real wallpaper): the only way Android lets an app read the current wallpaper. It is used
  for nothing else.
- **Notifications** (optional): only for the foreground service's "Hingegate is running" notice.

Hingegate has no internet access, so your wallpaper never leaves your phone.

## How often

Everything hinges on two settings:

- **Chance on each unfold**: how likely any one unfold is to crack.
- **Guarantee** (optional): never more than N clean unfolds in a row.

**Try it** can crack the next unfold whatever the odds, or show and clear a crack by hand, and **Recent unfolds**
logs each fold and unfold, and why it did or did not crack.

## Works on

Any foldable on Android 12 or later. The hinge position comes from Android's window manager where the phone reports
it, and is estimated from the screen shapes where it does not.

Other orchards are not supported. We are confident they manage perfectly well on their own.

## Credits

Made by CrsMthw ([GitHub](https://github.com/CrsMthw)) and Claude. If Hingegate has brought comfort to the flock,
the About section in the app has a [Buy Me a Coffee](https://buymeacoffee.com/crsmthw) button (its links open in
your browser). A coffee costs considerably less than a hinge repair.[^2]

MIT licensed, see [LICENSE](LICENSE).

## Install

Download the latest APK from the [Releases](../../releases) page and install it on your foldable. You may need to
allow installs from unknown sources in Android's settings.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

[^1]: Not that anything happens near their hinge. We would never suggest it.
[^2]: We assume. Prices vary by orchard.
