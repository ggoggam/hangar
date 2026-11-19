#!/usr/bin/env python3
"""Store "What's new" notes for a Hangar release, written by an LLM.

Usage:
  release-notes.py hangar [--tag TAG] [--generate] [--out DIR] [--whatsnew DIR] [--metadata DIR]

Tag release runs it with --generate --out when it tags a version, and publishes the files as
assets of a GitHub release for the tag (release-notes-<locale>.txt). The release workflows then run
it with --whatsnew (Play) or --metadata (fastlane's metadata folder): from a tag it downloads that
release's notes, so both stores get the same text; from main, or when the tag has no release, it
writes them itself.

The notes summarise the commits since the previous v* tag, one text per store locale, at most 500
characters (Play's limit; the App Store takes 4000). OPENROUTER_API_KEY is the key,
RELEASE_NOTES_MODEL picks the model. Without a key, or if the model fails, every locale gets a
generic "bug fixes and improvements" line and a warning, so a release never waits on the notes.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_MODEL = "anthropic/claude-sonnet-5.5"
PLAY_LIMIT = 500
# Asked for less than the limit, so an over-long answer is rare rather than routine.
TARGET = 350
MAX_LOG_CHARS = 60_000

# (fastlane locale, Play locale, language the model writes in)
APPS = {
    "hangar": {
        "name": "Hangar",
        "module": "android-app/module.yaml",
        "tag_prefix": "v",
        "path": ":/",
        "about": "An unofficial Fly.io client: watch your Fly.io apps from your phone, with "
        "machines, logs, metrics, releases and usage.",
        "style": "Plain, friendly US English.",
        "locales": [("en-US", "en-US", "English (US)")],
    },
}

FALLBACK = {
    "ko": "• 버그를 고치고 앱을 더 안정적으로 다듬었어요.",
    "en-US": "• Bug fixes and improvements.",
    "ja": "• 不具合の修正と改善を行いました。",
    "es-MX": "• Corrección de errores y mejoras.",
}

PROMPT = """You write the "What's new" text that {name} shows on the App Store and Google Play for \
version {version}.

About the app: {about}

Below are the commits that went into this version since {previous}. Write release notes for the \
app's users, not its developers:

- Only changes a user can notice: new features, changed behaviour, fixed bugs they could have hit. \
Leave out CI, builds, releases, refactors, tests, docs, dependency bumps, the server, logging and \
analytics unless they change what the user sees.
- Plain text. One short line per change, each starting with "• ", most important first, at most \
five lines. If there were fixes not worth a line each, end with a line like "• Small fixes and \
improvements." in that language. If nothing user-facing changed, write just that one line.
- The same text goes to both stores, so don't name a platform (Android, iOS, iPhone); describe \
the change itself.
- Say what the user gets, in everyday words, in the second person where natural. No version \
numbers, no commit, pull request or issue numbers, no names of libraries, services or code.
- Write each language natively, as a native speaker would phrase it for an app store, not as a \
translation of the English. {style}
- Each text at most {target} characters.

Return a JSON object with one key per locale: {keys}.

Commits, oldest first:

{log}"""


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def warn(message: str) -> None:
    print(f"::warning::{message}", file=sys.stderr)


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def read_version(app: str, ref: str) -> str:
    module = APPS[app]["module"]
    match = re.search(r'^ *versionName: *"(.*)"$', git("show", f"{ref}:{module}"), re.MULTILINE)
    if not match:
        sys.exit(f"::error::Couldn't read versionName from {module} at {ref}.")
    return match.group(1)


def previous_tag(app: str, version: str, inclusive: bool) -> str | None:
    """The highest release tag below version (or at it, when inclusive), fetched for git log."""
    prefix = f"refs/tags/{APPS[app]['tag_prefix']}"
    tags = git("ls-remote", "--tags", "--refs", "origin", f"{prefix}*").split()
    versions = [
        ref[len(prefix) :]
        for ref in tags
        if ref.startswith(prefix) and re.fullmatch(r"\d+\.\d+\.\d+", ref[len(prefix) :])
    ]
    older = sorted(
        (
            v
            for v in versions
            if version_key(v) < version_key(version)
            or (inclusive and version_key(v) == version_key(version))
        ),
        key=version_key,
    )
    if not older:
        return None
    tag = f"{APPS[app]['tag_prefix']}{older[-1]}"
    git("fetch", "--quiet", "--no-tags", "origin", f"+refs/tags/{tag}:refs/tags/{tag}")
    return tag


def commit_log(app: str, previous: str | None, ref: str) -> str:
    span = [f"{previous}..{ref}"] if previous else ["-n", "100", ref]
    raw = git(
        "log", "--reverse", "--no-merges", "--format=%s%n%b%x1e", *span, "--", APPS[app]["path"]
    )
    commits = []
    for entry in raw.split("\x1e"):
        entry = entry.strip()
        if not entry:
            continue
        # Squash merges carry the whole PR description; the start of it says enough.
        if len(entry) > 1500:
            entry = entry[:1500] + " …"
        commits.append(entry)
    log = "\n\n---\n\n".join(commits)
    return log[-MAX_LOG_CHARS:]


def ask_model(prompt: str, keys: list[str], api_key: str, model: str) -> dict[str, str]:
    schema = {
        "type": "object",
        "properties": {key: {"type": "string"} for key in keys},
        "required": keys,
        "additionalProperties": False,
    }
    body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
        "max_tokens": 2000,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "release_notes", "strict": True, "schema": schema},
        },
    }
    request = urllib.request.Request(
        "https://openrouter.ai/api/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "X-Title": "ggoggam release notes",
        },
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        answer = json.load(response)
    content = answer["choices"][0]["message"]["content"]
    # Some models wrap JSON in a code fence even when asked for a schema.
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip())
    notes = json.loads(content)
    return {key: str(notes[key]).strip() for key in keys}


def fit(text: str) -> str:
    """Drop whole lines from the end until the text fits Play's limit."""
    lines = text.splitlines()
    while len("\n".join(lines)) > PLAY_LIMIT and len(lines) > 1:
        lines.pop()
    return "\n".join(lines)[:PLAY_LIMIT].strip()


def generate(app: str, ref: str, is_tag: bool) -> dict[str, str]:
    config = APPS[app]
    locales = [locale for locale, _, _ in config["locales"]]
    fallback = {locale: FALLBACK[locale] for locale in locales}

    version = read_version(app, ref)
    # A tag's notes cover what's new since the version before it. A run from main covers what's new
    # since the last release, which may be this same version, rebuilt.
    previous = previous_tag(app, version, inclusive=not is_tag)
    log = commit_log(app, previous, ref)
    print(
        f"{app} {version}: notes for the commits since {previous or 'the start'}", file=sys.stderr
    )
    if not log:
        warn(f"No commits since {previous}, so the notes are the generic line.")
        return fallback

    api_key = os.environ.get("OPENROUTER_API_KEY", "")
    if not api_key:
        warn("OPENROUTER_API_KEY isn't set, so the release notes are the generic line.")
        return fallback
    model = os.environ.get("RELEASE_NOTES_MODEL") or DEFAULT_MODEL

    prompt = PROMPT.format(
        name=config["name"],
        version=version,
        about=config["about"],
        style=config["style"],
        previous=previous or "the first release",
        target=TARGET,
        keys=", ".join(f'"{locale}" ({language})' for locale, _, language in config["locales"]),
        log=log,
    )
    for attempt in range(1, 4):
        try:
            notes = ask_model(prompt, locales, api_key, model)
        except (urllib.error.URLError, TimeoutError, KeyError, IndexError, ValueError) as error:
            detail = (
                error.read().decode(errors="replace")[:500]
                if isinstance(error, urllib.error.HTTPError)
                else ""
            )
            warn(f"{model} failed on attempt {attempt}: {error} {detail}".strip())
            continue
        empty = [locale for locale, text in notes.items() if not text]
        if empty:
            warn(f"{model} wrote no notes for {', '.join(empty)} on attempt {attempt}.")
            continue
        too_long = [locale for locale, text in notes.items() if len(text) > PLAY_LIMIT]
        if too_long and attempt < 3:
            warn(
                f"{model} went over {PLAY_LIMIT} characters for {', '.join(too_long)}; "
                "asking again."
            )
            continue
        return {locale: fit(text) for locale, text in notes.items()}
    warn("The model never wrote usable notes, so they're the generic line.")
    return fallback


def download(app: str, tag: str, directory: Path) -> dict[str, str] | None:
    """The notes Tag <App> release published with the tag's GitHub release, if all are there."""
    result = subprocess.run(
        [
            "gh",
            "release",
            "download",
            tag,
            "--pattern",
            "release-notes-*.txt",
            "--dir",
            str(directory),
        ],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"No release notes on the {tag} release: {result.stderr.strip()}", file=sys.stderr)
        return None
    notes = {}
    for locale, _, _ in APPS[app]["locales"]:
        path = directory / f"release-notes-{locale}.txt"
        if not path.is_file() or not path.read_text().strip():
            print(f"The {tag} release has no {path.name}.", file=sys.stderr)
            return None
        notes[locale] = path.read_text().strip()
    return notes


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("app", choices=sorted(APPS))
    parser.add_argument(
        "--tag", help="the release tag (default: GITHUB_REF when it's a tag, else HEAD)"
    )
    parser.add_argument(
        "--generate", action="store_true", help="always ask the model, never download"
    )
    parser.add_argument("--out", type=Path, help="write <dir>/release-notes-<locale>.txt")
    parser.add_argument("--whatsnew", type=Path, help="write <dir>/whatsnew-<Play locale>")
    parser.add_argument("--metadata", type=Path, help="write <dir>/<locale>/release_notes.txt")
    args = parser.parse_args()

    tag = args.tag
    github_ref = os.environ.get("GITHUB_REF", "")
    if not tag and github_ref.startswith(f"refs/tags/{APPS[args.app]['tag_prefix']}"):
        tag = github_ref.removeprefix("refs/tags/")

    notes = None
    if tag and not args.generate:
        with tempfile.TemporaryDirectory() as directory:
            notes = download(args.app, tag, Path(directory))
    if notes is None:
        notes = generate(args.app, tag or "HEAD", is_tag=bool(tag))

    for locale, play_locale, _ in APPS[args.app]["locales"]:
        text = notes[locale] + "\n"
        print(f"--- {locale} ({len(notes[locale])} characters)\n{text}", file=sys.stderr)
        if args.out:
            args.out.mkdir(parents=True, exist_ok=True)
            (args.out / f"release-notes-{locale}.txt").write_text(text)
        if args.whatsnew:
            args.whatsnew.mkdir(parents=True, exist_ok=True)
            (args.whatsnew / f"whatsnew-{play_locale}").write_text(text)
        if args.metadata:
            (args.metadata / locale).mkdir(parents=True, exist_ok=True)
            (args.metadata / locale / "release_notes.txt").write_text(text)


if __name__ == "__main__":
    main()
