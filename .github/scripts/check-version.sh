#!/usr/bin/env bash
# Usage: check-version.sh
#
# versionName in android-app/module.yaml decides a release: Tag release tags and ships any
# versionName that has no v<versionName> tag yet. This fails when versionName isn't
# MAJOR.MINOR.PATCH or is lower than the highest version already tagged.
#
# In a pull request or the merge queue (BASE_SHA and HEAD_SHA set, and GH_TOKEN for the compare
# API) a bump has to go strictly up: when the change bumps versionName, the merged versionName must
# be higher than the base branch's and than every tag. That stops a bump to a version already
# released, and a pull request whose bump main already made in another, whose changes would then
# merge without a release. A change that leaves versionName alone releases nothing and passes.
set -euo pipefail

module=android-app/module.yaml

read_version() {
  sed -n 's/^ *versionName: *"\(.*\)"$/\1/p'
}

# versionName at a commit, fetched on its own. Shallowly only where the checkout already is (CI):
# --depth in a full clone would make it shallow.
version_at() {
  if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
    git fetch --quiet --no-tags --depth=1 origin "$1"
  elif ! git cat-file -e "$1^{commit}" 2> /dev/null; then
    git fetch --quiet --no-tags origin "$1"
  fi
  git show "$1:$module" | read_version
}

# True when $1 is higher than $2, both MAJOR.MINOR.PATCH.
higher() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)" = "$1" ]
}

version="$(read_version < "$module")"
if [ -z "$version" ]; then
  echo "::error file=$module::Couldn't read versionName from $module."
  exit 1
fi
if ! [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "::error file=$module::versionName \"$version\" isn't MAJOR.MINOR.PATCH."
  exit 1
fi

latest="$(git ls-remote --tags --refs origin 'refs/tags/v*' \
  | sed -n 's|.*refs/tags/v||p' \
  | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' \
  | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1 || true)"

if [ -n "$latest" ] && higher "$latest" "$version"; then
  echo "::error file=$module::versionName $version is lower than the latest release, v$latest. Bump it past $latest."
  exit 1
fi

# Pushes to main and the tag workflows: not lower than the latest tag is all there is to check.
if [ -z "${BASE_SHA:-}" ] || [ -z "${HEAD_SHA:-}" ]; then
  if [ -z "$latest" ]; then
    echo "Hangar $version: no v* tag yet, so it will be released."
  elif [ "$version" = "$latest" ]; then
    echo "Hangar $version: already released as v$latest, so a merge releases nothing."
  else
    echo "Hangar $version: higher than v$latest, so it will be released."
  fi
  exit 0
fi

# Whether the change itself bumps versionName: its head against where it branched off, not the
# merge with whatever the base branch has gained since.
merge_base="$(gh api "repos/$GITHUB_REPOSITORY/compare/$BASE_SHA...$HEAD_SHA" --jq .merge_base_commit.sha)"
base_version="$(version_at "$BASE_SHA")"
if [ "$(version_at "$HEAD_SHA")" = "$(version_at "$merge_base")" ]; then
  if [ "$version" != "$base_version" ]; then
    echo "::error file=$module::versionName is $version here but $base_version on the base branch, though the change doesn't bump it. Update the branch."
    exit 1
  fi
  echo "Hangar $version: versionName unchanged, so a merge releases nothing."
  exit 0
fi

if ! higher "$version" "$base_version"; then
  echo "::error file=$module::This bumps versionName to $version, which isn't higher than the base branch's $base_version. Bump it past $base_version."
  exit 1
fi
if [ -n "$latest" ] && ! higher "$version" "$latest"; then
  echo "::error file=$module::versionName $version is already released as v$latest. Bump it past $latest."
  exit 1
fi
echo "Hangar $version: higher than the base branch's $base_version${latest:+ and v$latest}, so the merge releases it."
