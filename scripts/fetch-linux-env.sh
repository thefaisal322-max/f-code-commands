#!/usr/bin/env bash
# Downloads what the terminal's Linux system is made of and puts it where the build expects it:
#
#   - PRoot and talloc (prebuilt for Android) -> app/src/main/jniLibs/<abi>/
#       PRoot runs the Linux programs; Android only lets an app start programs that ship as its
#       native libraries, which is why these executables carry lib*.so names.
#   - Alpine Linux mini root filesystem       -> app/src/main/assets/linux/
#
# Every file is pinned to one commit of its source repository and checked against a SHA-256
# hash, so a build always gets exactly these bytes. Needs curl and sha256sum.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/app/src/main/jniLibs"
ASSETS="$ROOT/app/src/main/assets/linux"

# PRoot (GPL-2.0, https://github.com/termux/proot) and talloc (LGPL-3.0, https://talloc.samba.org)
# as built for Android and published by the Acode editor project.
PROOT_BASE="https://raw.githubusercontent.com/Acode-Foundation/Acode/19ac7151b82291351253d7f3489fb91fc10b5897/src/plugins/proot/libs"

# Alpine Linux 3.21.8 (https://alpinelinux.org), from Alpine's own repository of release images.
ALPINE_BASE="https://raw.githubusercontent.com/alpinelinux/docker-alpine/45ca5a796a9e1c0b9de1e279514331000b7ff921"

fetch() {   # fetch <url> <destination> <sha256>
    local url="$1" dest="$2" want="$3" got
    mkdir -p "$(dirname "$dest")"
    if [ -f "$dest" ] && [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$want" ]; then
        echo "ok (already there)  ${dest#"$ROOT"/}"
        return
    fi
    curl --fail --silent --show-error --location --retry 3 --output "$dest.part" "$url"
    got="$(sha256sum "$dest.part" | cut -d' ' -f1)"
    if [ "$got" != "$want" ]; then
        rm -f "$dest.part"
        echo "error: $url does not have the expected content" >&2
        echo "  expected sha256 $want" >&2
        echo "  got             $got" >&2
        exit 1
    fi
    mv "$dest.part" "$dest"
    echo "ok                  ${dest#"$ROOT"/}"
}

# The files are .tar.gz archives but must not be named so: the Android build tools unpack any
# asset ending in .gz and rename it, and the app would then not find it.

# 64-bit ARM (almost every phone made since 2017)
fetch "$PROOT_BASE/arm64/libproot-xed.so" "$JNI/arm64-v8a/libproot-xed.so" b1751c915b74072eb29448c41426f983d2e7386c882d4831ce026ae1d973c32f
fetch "$PROOT_BASE/arm64/libproot.so"     "$JNI/arm64-v8a/libproot.so"     12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0
fetch "$PROOT_BASE/arm64/libproot32.so"   "$JNI/arm64-v8a/libproot32.so"   2da4cc752708370e84b99e30eed1d8986bb8ac2149f048e585b3662e92b37c19
fetch "$PROOT_BASE/arm64/libtalloc.so"    "$JNI/arm64-v8a/libtalloc.so"    6d02eb1bc6f6a03b2b940f003c7c9c1f2a8e4f16fab13a0f6b5c52c6d1ad56d9
fetch "$ALPINE_BASE/aarch64/alpine-minirootfs-3.21.8-aarch64.tar.gz" "$ASSETS/alpine-aarch64.rootfs" f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001

# 32-bit ARM (older and low-cost phones)
fetch "$PROOT_BASE/arm32/libproot-xed.so" "$JNI/armeabi-v7a/libproot-xed.so" 33b381d1a945dc33a7d66cec72e4eb0491b3c8a079e8cc0efb7f6d3b7256819b
fetch "$PROOT_BASE/arm32/libproot.so"     "$JNI/armeabi-v7a/libproot.so"     19e9a2dd9bca570bfd4c92cdfca3eecd792e91aa0ae21067cc06bf719fcf152c
fetch "$PROOT_BASE/arm32/libtalloc.so"    "$JNI/armeabi-v7a/libtalloc.so"    87aad4fa7232bd2c22daf14da5aae57fbb8723397ce9af62ae1af6df799fc738
fetch "$ALPINE_BASE/armhf/alpine-minirootfs-3.21.8-armhf.tar.gz" "$ASSETS/alpine-armhf.rootfs" a1c10c3b9d7f6febe040404f76d0f5a9a1704f7daa6645d5565db4cc633d31b9

# 64-bit Intel/AMD (Chromebooks, and the emulator the automatic tests run on)
fetch "$PROOT_BASE/x64/libproot-xed.so" "$JNI/x86_64/libproot-xed.so" b29cef0fef7a9b7f9aaf22b66570614db1b6a62f24689af6b6a8a60118f15d52
fetch "$PROOT_BASE/x64/libproot.so"     "$JNI/x86_64/libproot.so"     4ca6f14810548610501d012144abeb4c27c1530e2e37201cabf30cab2c39a585
fetch "$PROOT_BASE/x64/libproot32.so"   "$JNI/x86_64/libproot32.so"   8342faa11418109aa31946ab57add61b29e0f3d242ee63146c4e2d37a103abda
fetch "$PROOT_BASE/x64/libtalloc.so"    "$JNI/x86_64/libtalloc.so"    5a2f0f3697c782a864ae4584cf098612ed1ea0b1f281b052da79677e22f6d453
fetch "$ALPINE_BASE/x86_64/alpine-minirootfs-3.21.8-x86_64.tar.gz" "$ASSETS/alpine-x86_64.rootfs" 6ea461b0225faad280b7e13df878b30fdd6d969dde25ef05f85a1bcec9033348

cat > "$ASSETS/NOTICE.txt" <<'NOTICE'
The terminal's Linux system is made of these programs, each under its own license:

PRoot (GNU GPL v2 or later)
    Runs the Linux programs without root access.
    Source code: https://github.com/termux/proot
    The Android build shipped here was published by the Acode project:
    https://github.com/Acode-Foundation/Acode/tree/19ac7151b82291351253d7f3489fb91fc10b5897/src/plugins/proot

talloc (GNU LGPL v3 or later)
    A library PRoot uses. Source code: https://talloc.samba.org

Alpine Linux 3.21.8
    The Linux system itself. Each package inside it carries its own license.
    Source code and license of every package: https://pkgs.alpinelinux.org
NOTICE
echo "ok                  app/src/main/assets/linux/NOTICE.txt"
