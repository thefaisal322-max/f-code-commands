#!/usr/bin/env bash
# Pushes a folder of test results (logs, screenshots) to the ci-results branch, each run in its
# own folder next to the earlier ones' latest copy, so they can be read without signing in to
# GitHub Actions.
#
# Usage: publish-results.sh <folder> <commit message>
# Needs GH_TOKEN, GITHUB_REPOSITORY and GITHUB_SHA (set by GitHub Actions).
set -euo pipefail

SOURCE="${1:?folder to publish}"
MESSAGE="${2:-Test results}"
[ -d "$SOURCE" ] || { echo "Nothing to publish: $SOURCE does not exist."; exit 0; }

REMOTE="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Several jobs publish at the same time, so fetch, add, push, and try again if someone else pushed first.
for attempt in 1 2 3 4 5; do
    rm -rf "$WORK/repo"
    if git clone --quiet --depth 1 --branch ci-results "$REMOTE" "$WORK/repo" 2>/dev/null; then
        :
    else
        mkdir -p "$WORK/repo"
        git -C "$WORK/repo" init --quiet
        git -C "$WORK/repo" checkout --quiet -b ci-results
        echo "Results of the automatic tests. Each folder is replaced by the newest run." > "$WORK/repo/README.md"
    fi
    for item in "$SOURCE"/*; do
        name="$(basename "$item")"
        rm -rf "$WORK/repo/$name"
        cp -r "$item" "$WORK/repo/$name"
        echo "${GITHUB_SHA:-unknown}" > "$WORK/repo/$name/COMMIT"
    done
    git -C "$WORK/repo" add -A
    git -C "$WORK/repo" -c user.name="fcode-ci" -c user.email="fcode-ci@users.noreply.github.com" commit --quiet -m "$MESSAGE" || { echo "Nothing changed."; exit 0; }
    if git -C "$WORK/repo" push --quiet "$REMOTE" ci-results; then
        echo "Published to the ci-results branch."
        exit 0
    fi
    sleep $((attempt * 3))
done
echo "Could not publish the results." >&2
exit 1
