# Store listing assets

What Google Play's store listing shows, kept here so they can be made again.

- `play-icon.png` (512 x 512) and `feature-graphic.png` (1024 x 500) come from `icon/build.py`
  (`mise run icon`), from the same outline as the app icon and in the app's own font.
- `screenshots/` are the demo ("Try the demo") on the `small_phone` emulator at 1080 x 1920
  (`adb shell wm size 1080x1920`, `wm density 420`), with the status bar in Android's demo mode.
  They show only the demo's sample data, never a real account.

The App Store's listing lives with fastlane, in `ios-app/fastlane/`:

- `metadata/` holds the text, category, review notes and URLs, and `age_rating.json` the age
  rating answers. The description follows Google Play's, with the iOS Keychain in place of the
  Android Keystore.
- `screenshots/en-US/` are the same demo screens on a 6.9" iPhone (1320 x 2868) and a 13" iPad
  (2064 x 2752) simulator, with the status bar at 9:41. `mise run screenshots:ios` takes them
  again, tapping through the demo with [AXe](https://github.com/cameroncooke/AXe).

`bundle exec fastlane metadata` in `ios-app/`, with the `ASC_*` variables from RELEASING.md set,
uploads both to the App Store version being prepared. It submits nothing.
