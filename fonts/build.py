# /// script
# requires-python = ">=3.9"
# dependencies = ["fonttools==4.60.2"]
# ///
"""
Builds the app's font in app/composeResources/font from a pinned upstream release.

Hangar Sans is Pretendard cut down to Latin, punctuation and common symbols, plus the 2,350
Hangul syllables of KS X 1001: every syllable in common use, and all of the Korean strings. That
keeps each weight near a quarter of Pretendard's 1.6 MB. Anything else in Fly.io's own data, such
as a rarer syllable or Cyrillic, falls back to the system font.

It's a modified font under the SIL Open Font License, and Pretendard's licence reserves its name,
so it goes by a new one. The licence ships in app/composeResources/files/licenses.

Run with `mise run fonts`. The download is cached in build/fonts and checked against its sha256.
"""

import hashlib
import sys
import urllib.request
import zipfile
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "build" / "fonts"
FONTS = ROOT / "app" / "composeResources" / "font"
LICENSES = ROOT / "app" / "composeResources" / "files" / "licenses"

PRETENDARD = (
    "https://github.com/orioncactus/pretendard/releases/download/v1.3.9/Pretendard-1.3.9.zip",
    "04be351a74d6bf7d60c480a3087e51d185485d35a52023142af1df19eb8c428a",
)

# What Hangar Sans keeps besides Hangul syllables, by Unicode block.
BLOCKS = [
    (0x0020, 0x036F),  # Latin, Latin-1, Extended-A and B, IPA, modifiers, combining marks
    (0x1E00, 0x1EFF),  # Latin Extended Additional
    (0x2000, 0x218F),  # punctuation, super- and subscripts, currency, letterlike, number forms
    (0x2190, 0x23FF),  # arrows, maths, technical
    (0x2460, 0x24FF),  # enclosed alphanumerics, such as ①
    (0x25A0, 0x27BF),  # shapes, symbols, dingbats
    (0x3000, 0x303F),  # CJK symbols and punctuation
    (0x3130, 0x318F),  # Hangul compatibility jamo, such as ㄱ
    (0x3200, 0x32FF),  # enclosed CJK, such as ㈜
    (0xFF00, 0xFFEF),  # full- and halfwidth forms
]
# The layout features it keeps: shaping, kerning, and the figure styles. Pretendard's stylistic
# sets and alternates would each pull in their own glyphs.
FEATURES = ["ccmp", "locl", "calt", "clig", "liga", "kern", "mark", "mkmk", "cpsp", "case", "tnum", "pnum", "zero", "frac", "numr", "dnom", "sups", "subs"]
# The weights the type scale uses, Bold for headings; Compose picks the nearest for others.
WEIGHTS = {"Regular": "regular", "Medium": "medium", "SemiBold": "semibold", "Bold": "bold"}


def fetch(url, sha256):
    path = CACHE / url.rsplit("/", 1)[1]
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != sha256:
        CACHE.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {url}")
        with urllib.request.urlopen(url) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != sha256:
            sys.exit(f"{url} doesn't match its pinned sha256")
        path.write_bytes(data)
    return path


def ks_x_1001():
    """The Hangul syllables of KS X 1001: the ones EUC-KR encodes in two bytes."""
    return {u for u in range(0xAC00, 0xD7A4) if len(chr(u).encode("euc_kr")) == 2}


def rename(font, family, postscript, description):
    """Renames [font] to [family] wherever its own family name appears, as the OFL requires."""
    names = font["name"]
    old = names.getDebugName(16) or names.getDebugName(1)
    old_postscript = names.getDebugName(6).split("-")[0]
    for record in names.names:
        if record.nameID in (1, 3, 4, 6, 16, 17, 18, 21, 22, 25):
            record.string = record.toUnicode().replace(old_postscript, postscript).replace(old, family)
    names.setName(description, 10, 3, 1, 0x409)
    names.setName(description, 10, 1, 0, 0)
    cff = font["CFF "].cff
    top = cff.topDictIndex[0]
    cff.fontNames = [n.replace(old_postscript, postscript) for n in cff.fontNames]
    top.FamilyName = top.FamilyName.replace(old, family)
    top.FullName = top.FullName.replace(old, family)


def sans(pretendard_zip, weight, hangul):
    with zipfile.ZipFile(pretendard_zip) as z:
        source = CACHE / f"Pretendard-{weight}.otf"
        source.write_bytes(z.read(f"public/static/Pretendard-{weight}.otf"))
    # The source's timestamp carries through, so rebuilding an unchanged source gives the same file.
    font = TTFont(source, recalcTimestamp=False)
    unicodes = [u for u in font.getBestCmap() if u in hangul or any(lo <= u <= hi for lo, hi in BLOCKS)]
    options = subset.Options()
    options.layout_features = FEATURES
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.glyph_names = False
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=unicodes)
    subsetter.subset(font)
    rename(font, "Hangar Sans", "HangarSans", "Pretendard by Kil Hyung-jin, cut down to KS X 1001 Hangul for Hangar.")
    return font


def main():
    pretendard = fetch(*PRETENDARD)
    hangul = ks_x_1001()

    FONTS.mkdir(parents=True, exist_ok=True)
    for weight, suffix in WEIGHTS.items():
        sans(pretendard, weight, hangul).save(FONTS / f"hangar_sans_{suffix}.otf")

    LICENSES.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(pretendard) as z:
        (LICENSES / "pretendard.txt").write_bytes(z.read("LICENSE.txt"))

    for path in sorted(FONTS.iterdir()):
        print(f"{path.relative_to(ROOT)}  {path.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
