# Store listing assets

What Google Play's store listing shows, kept here so they can be made again.

- `play-icon.png` (512 x 512) and `feature-graphic.png` (1024 x 500) come from `icon/build.py`
  (`mise run icon`), from the same outline as the app icon and in the app's own font.
- `screenshots/` are the demo ("Try the demo") on the `small_phone` emulator at 1080 x 1920
  (`adb shell wm size 1080x1920`, `wm density 420`), with the status bar in Android's demo mode.
  They show only the demo's sample data, never a real account.
