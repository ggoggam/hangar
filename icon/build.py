# /// script
# requires-python = ">=3.9"
# dependencies = ["pillow==11.3.0"]
# ///
"""
Draws the app icon: a Quonset hangar, a semicircle with an arched door, in the app's own black
and white. One flat colour and a cut-out door, after the style of Fly.io's balloon, but not its
colour or shape, since Hangar isn't Fly.io's app.

The mark is drawn on Android's 108 dp adaptive icon canvas. A launcher shows the middle 72 dp, and
the mark stays inside the 66 dp safe zone that every mask keeps. iOS gets the same 72 dp square.

Writes the Android foreground (also the themed icon's monochrome layer), the iOS icon's light,
dark and tinted images, the light and dark logos the README shows, and Google Play's 512 px icon
and 1024 x 500 feature graphic, which set the name in the app's own font. The rest of the icon (the adaptive icon, its background colour and the
asset catalogue's Contents.json) is written by hand.

Run with `mise run icon`.
"""

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android-app" / "res" / "drawable" / "ic_launcher_foreground.xml"
IOS = ROOT / "ios-app" / "src" / "Assets.xcassets" / "AppIcon.appiconset"
LOGO = ROOT / "icon"
STORE = ROOT / "store"
FONT = ROOT / "app" / "composeResources" / "font"

CENTRE = 54  # of the 108 dp canvas
RADIUS = 21  # the roof: 58% of the 72 dp a launcher shows
FILLET = 1  # rounds the two corners where the roof meets the ground
# The baseline sits a little below the centre, so the semicircle's weight sits in the middle.
BASELINE = CENTRE + 0.46 * RADIUS
DOOR_HALF_WIDTH = 4.66
DOOR_HEIGHT = 10.92

INK = "#171717"
PAPER = "#FAFAFA"

# The iOS image covers the 72 dp a launcher shows, drawn four times over and scaled down so its
# edges are smooth.
IOS_SIZE = 1024
SUPERSAMPLE = 4
VIEWPORT = (18, 72)
# The README's logo is the iOS square with its corners rounded, about as much as a home screen does.
LOGO_CORNER = 16


def outline():
    """The mark as one closed outline, a list of ("line", x, y) and ("arc", cx, cy, r, from, to)
    steps with angles in degrees, clockwise from +x as on screen. The door is traced into the
    outline as a notch, so no seam shows where it meets the ground."""
    # Each fillet's circle touches the baseline and sits just inside the roof's circle.
    x = math.sqrt((RADIUS - FILLET) ** 2 - FILLET**2)
    tilt = math.degrees(math.atan2(FILLET, x))
    door_top = BASELINE - DOOR_HEIGHT + DOOR_HALF_WIDTH
    return [
        ("line", CENTRE - x, BASELINE),
        ("arc", CENTRE - x, BASELINE - FILLET, FILLET, 90, 180 + tilt),
        ("arc", CENTRE, BASELINE, RADIUS, 180 + tilt, 360 - tilt),
        ("arc", CENTRE + x, BASELINE - FILLET, FILLET, -tilt, 90),
        ("line", CENTRE + DOOR_HALF_WIDTH, BASELINE),
        ("line", CENTRE + DOOR_HALF_WIDTH, door_top),
        ("arc", CENTRE, door_top, DOOR_HALF_WIDTH, 0, -180),
        ("line", CENTRE - DOOR_HALF_WIDTH, BASELINE),
    ]


def point(cx, cy, r, degrees):
    return cx + r * math.cos(math.radians(degrees)), cy + r * math.sin(math.radians(degrees))


def path_data():
    """The outline as SVG path data, which is what a VectorDrawable's pathData takes."""
    n = lambda v: f"{round(v, 2):g}"
    parts = []
    for step in outline():
        if step[0] == "line":
            parts.append(f"{'L' if parts else 'M'}{n(step[1])},{n(step[2])}")
        else:
            _, cx, cy, r, start, end = step
            x, y = point(cx, cy, r, end)
            sweep = 1 if end > start else 0
            parts.append(f"A{n(r)},{n(r)} 0 0,{sweep} {n(x)},{n(y)}")
    return " ".join(parts) + " Z"


def polygon(scale, origin):
    """The outline as points, with each arc cut into segments too short to see."""
    points = []
    for step in outline():
        if step[0] == "line":
            points.append((step[1], step[2]))
        else:
            _, cx, cy, r, start, end = step
            count = max(8, int(abs(end - start) * r * scale / 40))
            points += [point(cx, cy, r, start + (end - start) * i / count) for i in range(count + 1)]
    return [((x - origin) * scale, (y - origin) * scale) for x, y in points]


def render(background, ink, size=IOS_SIZE):
    big = size * SUPERSAMPLE
    origin, span = VIEWPORT
    image = Image.new("RGB", (big, big), background)
    ImageDraw.Draw(image).polygon(polygon(big / span, origin), fill=ink)
    return image.resize((size, size), Image.LANCZOS)


def write_android():
    ANDROID.parent.mkdir(parents=True, exist_ok=True)
    ANDROID.write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Written by icon/build.py; change the icon there. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{INK}"
        android:pathData="{path_data()}" />
</vector>
"""
    )


def write_ios():
    IOS.mkdir(parents=True, exist_ok=True)
    # The light icon can't have an alpha channel: App Store Connect rejects one that does.
    render(PAPER, INK).save(IOS / "AppIcon.png", optimize=True)
    render(INK, PAPER).save(IOS / "AppIcon-dark.png", optimize=True)
    # iOS tints the tinted icon by its brightness, so it is white on black.
    render("#000000", "#FFFFFF").convert("L").save(IOS / "AppIcon-tinted.png", optimize=True)


def logo(background, ink):
    origin, span = VIEWPORT
    return f"""<!-- Written by icon/build.py; change the icon there. -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="{origin} {origin} {span} {span}" width="128" height="128">
  <rect x="{origin}" y="{origin}" width="{span}" height="{span}" rx="{LOGO_CORNER}" fill="{background}"/>
  <path fill="{ink}" d="{path_data()}"/>
</svg>
"""


def write_logo():
    # The same pair as the iOS icon: the README picks one by the reader's GitHub theme.
    (LOGO / "logo.svg").write_text(logo(PAPER, INK))
    (LOGO / "logo-dark.svg").write_text(logo(INK, PAPER))


def write_store():
    STORE.mkdir(exist_ok=True)
    # Play masks the icon's corners itself, so it's the full square, as on iOS.
    render(PAPER, INK, 512).save(STORE / "play-icon.png", optimize=True)

    # The feature graphic: the mark beside the name and what the app is, on the icon's paper.
    width, height = 1024, 500
    big = Image.new("RGB", (width * SUPERSAMPLE, height * SUPERSAMPLE), PAPER)
    draw = ImageDraw.Draw(big)
    origin, span = VIEWPORT
    mark = 340 * SUPERSAMPLE
    left, top = 70 * SUPERSAMPLE, (height * SUPERSAMPLE - mark) // 2
    draw.polygon([(x + left, y + top) for x, y in polygon(mark / span, origin)], fill=INK)
    name = ImageFont.truetype(str(FONT / "hangar_sans_bold.otf"), 128 * SUPERSAMPLE)
    line = ImageFont.truetype(str(FONT / "hangar_sans_regular.otf"), 34 * SUPERSAMPLE)
    text_left = 440 * SUPERSAMPLE
    draw.text((text_left, 248 * SUPERSAMPLE), "Hangar", font=name, fill=INK, anchor="ls")
    draw.text((text_left, 312 * SUPERSAMPLE), "Your Fly.io apps, on your phone", font=line, fill="#525252", anchor="ls")
    draw.text((text_left, 358 * SUPERSAMPLE), "Unofficial, not affiliated with Fly.io", font=line, fill="#737373", anchor="ls")
    big.resize((width, height), Image.LANCZOS).save(STORE / "feature-graphic.png", optimize=True)


if __name__ == "__main__":
    write_android()
    write_ios()
    write_logo()
    write_store()
    print(f"Wrote {ANDROID.relative_to(ROOT)}, {IOS.relative_to(ROOT)}, {LOGO.relative_to(ROOT)}/logo*.svg and {STORE.relative_to(ROOT)}")
