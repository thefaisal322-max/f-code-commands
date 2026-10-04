#!/usr/bin/env bash
# Downloads the web libraries the app's page uses (terminal, icons, fonts) and copies them into
# app/src/main/assets/www/vendor, so the APK works with no internet connection.
# Run from anywhere; needs Node.js and npm. The CI workflow runs it before every build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/www/vendor"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cd "$WORK"
npm init -y >/dev/null
npm install --no-audit --no-fund --ignore-scripts \
    @xterm/xterm@5.5.0 \
    @xterm/addon-fit@0.10.0 \
    @fortawesome/fontawesome-free@6.4.0 \
    @fontsource/fira-code@^5 \
    @fontsource/inter@^5

rm -rf "$DEST"
mkdir -p "$DEST/xterm" "$DEST/fontawesome/css" "$DEST/fontawesome/webfonts" "$DEST/fonts" "$DEST/licenses"

# Terminal emulator
cp node_modules/@xterm/xterm/lib/xterm.js          "$DEST/xterm/xterm.js"
cp node_modules/@xterm/xterm/css/xterm.css         "$DEST/xterm/xterm.css"
cp node_modules/@xterm/addon-fit/lib/addon-fit.js  "$DEST/xterm/addon-fit.js"
cp node_modules/@xterm/xterm/LICENSE               "$DEST/licenses/xterm.txt"

# Icons (all.min.css loads ../webfonts/*.woff2, so the folder layout must stay like this)
cp node_modules/@fortawesome/fontawesome-free/css/all.min.css  "$DEST/fontawesome/css/all.min.css"
cp node_modules/@fortawesome/fontawesome-free/webfonts/*.woff2 "$DEST/fontawesome/webfonts/"
cp node_modules/@fortawesome/fontawesome-free/LICENSE.txt      "$DEST/licenses/fontawesome.txt"

# Fonts
: > "$DEST/fonts.css"
add_font() {   # add_font <css family> <package> <file prefix> <weight>
    local file="$3-latin-$4-normal.woff2"
    cp "node_modules/@fontsource/$2/files/$file" "$DEST/fonts/$file"
    cat >> "$DEST/fonts.css" <<EOF
@font-face {
    font-family: '$1';
    font-style: normal;
    font-weight: $4;
    font-display: swap;
    src: url('fonts/$file') format('woff2');
}
EOF
}
for weight in 400 500 600; do add_font "Fira Code" fira-code fira-code "$weight"; done
for weight in 400 500 600 700; do add_font "Inter" inter inter "$weight"; done
cp node_modules/@fontsource/fira-code/LICENSE "$DEST/licenses/fira-code.txt"
cp node_modules/@fontsource/inter/LICENSE     "$DEST/licenses/inter.txt"

echo "Web libraries copied to $DEST:"
find "$DEST" -type f | sort | sed "s|$DEST/|  |"
