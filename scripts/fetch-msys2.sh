#!/usr/bin/env bash
# Downloads MSYS2 (bash, git, pacman for Windows) for the Windows app's Linux terminal and saves it
# as desktop/linux/msys2-base.tar.gz. It is repacked as .tar.gz because every Windows 10 and 11
# can unpack that with its own tar.exe. The CI workflow runs this before every Windows build.
set -euo pipefail

VERSION=2026-09-27
FILE=msys2-base-x86_64-20260927.tar.xz
SHA256=ea2f31a0b6ade63914ce441ffb022f0f6aa96982bfefa2326460a26d5fb01322

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/desktop/linux"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fsSL --retry 3 -o "$WORK/$FILE" "https://github.com/msys2/msys2-installer/releases/download/$VERSION/$FILE"
echo "$SHA256  $WORK/$FILE" | sha256sum -c -

mkdir -p "$DEST"
PYTHON="$(command -v python3 || command -v python)"
"$PYTHON" - "$WORK/$FILE" "$DEST/msys2-base.tar.gz" <<'PY'
import gzip, lzma, shutil, sys
with lzma.open(sys.argv[1]) as source, gzip.open(sys.argv[2], 'wb', compresslevel=6) as target:
    shutil.copyfileobj(source, target, 1 << 20)
PY
ls -la "$DEST"
