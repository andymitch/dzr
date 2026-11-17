#!/usr/bin/env bash
# Prints the next release for one app as key=value lines (for $GITHUB_OUTPUT).
#
# Usage: scripts/next-version.sh <android|macos> <major|hotfix|beta>
#   major:  next vN                     (android-v1, macos-v1, ...)
#   hotfix: vN.X off the latest vN      (android-v1.1, ...)
#   beta:   vN-beta.X of the NEXT major (android-v2-beta.1, ...), a GitHub pre-release
#
# Tags are prefixed per app. The pre-v1 tags (shared v0.1 to v0.6, then
# <app>-v0.X) only matter as the previous release for the notes.
#
# Android needs a versionCode that always grows: N*100 + X for vN / vN.X, and
# (N-1)*100 + 50 + B for vN-beta.B, so a beta sits between the last hotfix of
# vN-1 and vN itself. v0.X releases used X, which stays below 100.
set -euo pipefail
APP=$1
TYPE=${2:-major}
P="$APP-"

tags() { git tag -l | { grep -E "^${P}v$1\$" || true; }; }
counter() { sed -E "s/^${P}v$1//" | sort -n | tail -1; }

LATEST_MAJOR=$(tags '[0-9]+' | counter '')
PRERELEASE=false
case "$TYPE" in
  major)
    N=$(( ${LATEST_MAJOR:-0} + 1 ))
    VERSION="$N"; CODE=$(( N * 100 ))
    ;;
  hotfix)
    [ -n "$LATEST_MAJOR" ] || { echo "No vN release of $APP to hotfix" >&2; exit 1; }
    N=$LATEST_MAJOR
    X=$(tags "${N}\\.[0-9]+" | counter "${N}\\.")
    X=$(( ${X:-0} + 1 ))
    [ "$X" -lt 50 ] || { echo "Out of hotfix numbers for v$N" >&2; exit 1; }
    VERSION="$N.$X"; CODE=$(( N * 100 + X ))
    ;;
  beta)
    N=$(( ${LATEST_MAJOR:-0} + 1 ))
    B=$(tags "${N}-beta\\.[0-9]+" | counter "${N}-beta\\.")
    B=$(( ${B:-0} + 1 ))
    [ "$B" -lt 50 ] || { echo "Out of beta numbers for v$N" >&2; exit 1; }
    VERSION="$N-beta.$B"; CODE=$(( (N - 1) * 100 + 50 + B ))
    PRERELEASE=true
    ;;
  *) echo "Unknown release type: $TYPE" >&2; exit 1 ;;
esac

PREV=$(git tag -l | { grep -E "^(${P})?v[0-9]+(\.[0-9]+)?\$" || true; } \
  | awk '{ v = $0; sub(/^[a-z]+-/, "", v); print v, $0 }' | sort -V | tail -1 | cut -d' ' -f2)

echo "version=$VERSION"
echo "code=$CODE"
echo "tag=${P}v$VERSION"
echo "prev=$PREV"
echo "prerelease=$PRERELEASE"
