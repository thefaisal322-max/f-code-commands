#!/usr/bin/env bash
# Downloads MSYS2 (bash, git, pacman for Windows) for the Windows app's Linux terminal and saves it
# as desktop/linux/msys2-base.sfx.exe: MSYS2's own self-extracting archive, already compressed,
# which unpacks itself on any Windows. The CI workflow runs this before every Windows build.
set -euo pipefail

VERSION=2026-09-27
FILE=msys2-base-x86_64-20260927.sfx.exe
SHA256=ad336cccfda47758b5e15cda993fbba421115cb0b126697daef1ee4dfe37209f

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/desktop/linux"
mkdir -p "$DEST"
rm -f "$DEST"/msys2-base.*
curl -fsSL --retry 3 -o "$DEST/msys2-base.sfx.exe" "https://github.com/msys2/msys2-installer/releases/download/$VERSION/$FILE"
echo "$SHA256  $DEST/msys2-base.sfx.exe" | sha256sum -c -
ls -la "$DEST"
