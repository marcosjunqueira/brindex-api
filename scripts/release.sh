#!/usr/bin/env bash
# Cuts release vX.Y.Z of brindex-api from the owner's machine. release.yml only publishes a tag that is
# on main AND equals `version` in build.gradle.kts, and a failed run after the tag is public means
# tagging a new patch (a published tag is never moved). So this does the steps in the only order
# that works: check main is clean and current, set the version, commit and tag locally, then push
# main and the tag together (the tag is what starts release.yml).
#
#   scripts/release.sh patch              # or minor, major: bumps the current version
#   scripts/release.sh 1.2.3              # an explicit X.Y.Z
#   scripts/release.sh --dry-run minor    # runs the checks and prints the plan; changes nothing
#
# Nothing is written, committed or pushed until you type the tag at the prompt. If the version was
# already bumped (say, in a merged PR), pass that same version and it only tags. A version below the
# current one or the latest release is refused, and so is an existing tag: moving a tag stays a
# decision taken by hand.
set -euo pipefail

usage() { echo "usage: scripts/release.sh [--dry-run] <major|minor|patch|X.Y.Z>" >&2; exit 2; }
die() { echo "release: $*" >&2; exit 1; }

DRY_RUN=false
BUMP=
for arg in "$@"; do
  case $arg in
    --dry-run) DRY_RUN=true ;;
    -h|--help) usage ;;
    -*) die "unknown option $arg" ;;
    *) [ -z "$BUMP" ] || usage; BUMP=$arg ;;
  esac
done
[ -n "$BUMP" ] || usage

cd "$(dirname "$0")/.."
VERSION_FILE=build.gradle.kts

# The project's `version = "X.Y.Z"` line, read the same way release.yml reads it.
read_version() { sed -n 's/^version = "\(.*\)"$/\1/p' "$VERSION_FILE"; }
write_version() { sed -i.bak "s/^version = \".*\"$/version = \"$1\"/" "$VERSION_FILE" && rm "$VERSION_FILE.bak"; }

CURRENT=$(read_version)
# SemVer X.Y.Z with no leading zeros: docker/metadata-action rejects 0.2.03 only after the tag is
# public, and bash arithmetic would read 08 as octal.
SEMVER='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
echo "$CURRENT" | grep -Eq "$SEMVER" \
  || die "$VERSION_FILE has version '$CURRENT', expected X.Y.Z"
IFS=. read -r MAJOR MINOR PATCH <<<"$CURRENT"
case $BUMP in
  major) VERSION=$((MAJOR + 1)).0.0 ;;
  minor) VERSION=$MAJOR.$((MINOR + 1)).0 ;;
  patch) VERSION=$MAJOR.$MINOR.$((PATCH + 1)) ;;
  *) VERSION=${BUMP#v} ;;
esac
echo "$VERSION" | grep -Eq "$SEMVER" || die "version must be X.Y.Z with no leading zeros, got $BUMP"
TAG=v$VERSION

git fetch -q --tags origin main
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "not on main"
[ -z "$(git status --porcelain)" ] || die "the working tree has uncommitted changes"
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
  || die "main is not the same as origin/main (pull or push first)"
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null; then
  die "$TAG already exists on $(git remote get-url origin)"
fi
# A local tag nothing pushed is what an interrupted run leaves behind; `git tag` would refuse it
# later with a message about the commit instead.
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  die "$TAG exists only locally (an interrupted run?): delete it with git tag -d $TAG"
fi
# main only moves forward: a version below the current one or below the latest release is refused
# (release.yml would publish it without X.Y, X or latest, which never move back).
HIGHEST=$(git tag -l 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n1 || true)
newer_or_equal() { [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" = "$2" ]; }
newer_or_equal "$CURRENT" "$VERSION" || die "$VERSION is lower than the current version $CURRENT"
[ -z "$HIGHEST" ] || newer_or_equal "${HIGHEST#v}" "$VERSION" \
  || die "$VERSION is lower than the latest release $HIGHEST (if that tag is local only, a stale
  leftover, check git ls-remote --tags origin and delete it with git tag -d $HIGHEST)"

echo "release: $(git remote get-url origin)"
echo "release:   $VERSION_FILE  $CURRENT -> $VERSION"
if [ "$CURRENT" = "$VERSION" ]; then
  echo "release:   version already set; tag $TAG on $(git rev-parse --short HEAD) with no new commit"
else
  echo "release:   commit \"Release $VERSION\" on main, tag $TAG"
fi
echo "release:   push main and $TAG to origin (starts release.yml)"

if $DRY_RUN; then
  echo "release: dry run, nothing changed"
  exit 0
fi

[ -r /dev/tty ] || die "no terminal to confirm on; run it interactively"
read -r -p "Release $TAG? Type the tag to confirm: " ANSWER </dev/tty
[ "$ANSWER" = "$TAG" ] || die "not confirmed, nothing changed"

if [ "$CURRENT" != "$VERSION" ]; then
  write_version "$VERSION"
  [ "$(read_version)" = "$VERSION" ] || { git checkout -- "$VERSION_FILE"; die "could not set the version in $VERSION_FILE"; }
  git commit -qm "Release $VERSION" -- "$VERSION_FILE" \
    || { git checkout -- "$VERSION_FILE"; die "the commit failed; nothing was pushed"; }
fi
git tag -a "$TAG" -m "Release $VERSION" \
  || die "tagging failed after the commit; nothing was pushed. Undo the local release with:
  git reset --hard origin/main"

# --atomic: main and the tag land together or not at all, so the tag never exists without the
# commit it points at being on main (release.yml checks that first).
git push -q --atomic origin main "refs/tags/$TAG" \
  || die "push failed; nothing was published. Undo the local release with:
  git tag -d $TAG && git reset --hard origin/main"
echo "release: $TAG pushed on $(git remote get-url origin)"
echo "release: release.yml is running: $(git remote get-url origin | sed -E 's#^(git@|https://)github.com[:/]#https://github.com/#; s#\.git$##')/actions/workflows/release.yml"
