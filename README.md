# Fcode

A code editor for Android with a real Linux terminal built in. Part of the faiNET family of apps, by Faisal Hadawal.

- **Code tab** – the editor: tabs, a file sidebar, live preview, console, and a Run button for HTML, JSX, JavaScript and Python. Run works without internet.
- **Commands tab** – a real terminal, with several terminals side by side.
  - **Linux** (default): a small Alpine Linux system running through PRoot. `bash`, `git`, `./script.sh`, and a package manager: `pkg install NAME` (also `apt install NAME`; both wrap Alpine's `apk`).
  - **Android**: the phone's own shell (`/system/bin/sh`), for when Linux is not wanted or cannot start. Pick the shell in Settings.

Both tabs work on the same real files: the terminal's home folder (`~`) is the editor's project folder.

To reach the phone's shared storage from the terminal, run `setup-storage` (also `termux-setup-storage`). After you allow access, `~/storage/downloads`, `~/storage/shared` and the other usual folders appear. To bring in a single file without that permission, use **Import File** in the sidebar.

Settings has three themes (Classic, Neon glows, Black and white) and three languages (English, Pashto, Dari).

## Install

Open the repository's **Releases** page, download `Fcode.apk` from "Fcode - latest build", and open it on your phone. Android will ask you to allow installing apps from your browser or file manager the first time. Android 8.0 or newer is needed.

Each new build installs over the previous one; you don't need to uninstall first.

## First start of the Linux terminal

The first time the Commands tab opens, the Linux system is unpacked (a few seconds) and the terminal asks whether to install `bash`, `git`, `curl`, `unzip`, `zip` and `nano`. That step needs internet. You can skip it and run `fcode-setup` later.

If Linux cannot start on a phone, the terminal says so and continues with Android's own shell.

## How the APK is built

Every push runs `.github/workflows/build.yml` on GitHub Actions. It:

1. downloads the web libraries the page uses (xterm.js, Font Awesome, the fonts, React, Babel, Pyodide) with `scripts/fetch-web-vendor.sh`,
2. downloads PRoot, talloc and the Alpine Linux root filesystem with `scripts/fetch-linux-env.sh` (each file pinned to a commit and checked by SHA-256),
3. builds the release APK with Gradle,
4. publishes it as the `latest` release.

To build on your own computer you need Node.js, JDK 17, the Android SDK and NDK, and Gradle 8.7 or newer:

```sh
bash scripts/fetch-web-vendor.sh
bash scripts/fetch-linux-env.sh
gradle assembleRelease
```

### Signing

Without any setup, builds are signed with the key in `signing/`. That key is public, so it is only suitable for testing: anyone could sign an APK that Android accepts as an update to yours.

Before giving the app to other people, create a private key and store it in the repository's secrets (**Settings → Secrets and variables → Actions**):

```sh
keytool -genkeypair -keystore fcode-release.keystore -storetype PKCS12 \
        -alias fcode -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 fcode-release.keystore     # the text to paste into FCODE_KEYSTORE_BASE64
```

| Secret | Value |
| --- | --- |
| `FCODE_KEYSTORE_BASE64` | the keystore file as Base64 text |
| `FCODE_KEYSTORE_PASSWORD` | the keystore password |
| `FCODE_KEY_ALIAS` | `fcode` |
| `FCODE_KEY_PASSWORD` | the key password (the same as the keystore password for PKCS12) |

Keep a copy of the keystore file and its password somewhere safe: without them you can never publish an update. The first build signed with the new key cannot install over a build signed with the test key; uninstall the old one once.

## Layout

| Path | What it is |
| --- | --- |
| `app/src/main/assets/www/index.html` | The whole user interface (editor, terminal screen, settings, translations) |
| `app/src/main/java/com/fainet/fcode/MainActivity.java` | The app's single screen: a WebView plus the `FcodeNative` bridge the page calls |
| `app/src/main/java/com/fainet/fcode/ShellSession.java` | Starts a shell on a pseudo-terminal and talks to it |
| `app/src/main/java/com/fainet/fcode/LinuxEnv.java` | Unpacks the Linux system and builds the PRoot command that runs it |
| `app/src/main/java/com/fainet/fcode/ProjectFiles.java` | File access for the editor, inside the terminal's home folder |
| `app/src/main/java/com/fainet/fcode/LocalServer.java` | Small web server behind "Open in Browser" |
| `app/src/main/java/com/fainet/fcode/KeepAliveService.java` | Keeps running shells alive while the app is in the background |
| `app/src/main/jni/fcode_pty.c` | Native code that creates the pseudo-terminal |
| `scripts/` | Download scripts used by the build |
| `signing/fcode-debug.keystore` | The public test key (see Signing) |

`index.html` also works in a normal browser. There is no bridge there, so the Commands tab falls back to a small built-in shell and the project is kept in the browser's storage.

## Known limits

- Programs cannot be run from `~/storage` (the phone's shared storage); copy them into `~` first.
- Android may still stop background work on phones with aggressive battery saving.
- Pashto and Dari text printed inside the terminal is not joined or right-to-left; the terminal draws each character in its own cell.
- JSX files cannot be sent to "Open in Browser"; HTML files can.

## Licenses

Fcode's own code is in this repository. It is built with open-source software, each part under its own license:

- In the editor: [xterm.js](https://github.com/xtermjs/xterm.js) (MIT), [React](https://react.dev) (MIT), [Babel](https://babeljs.io) (MIT), [Pyodide](https://pyodide.org) (MPL-2.0), [Font Awesome Free](https://fontawesome.com) (icons CC BY 4.0, fonts SIL OFL 1.1, code MIT), [Fira Code](https://github.com/tonsky/FiraCode) and [Inter](https://github.com/rsms/inter) (both SIL OFL 1.1). Their license texts are copied into the APK under `assets/www/vendor/licenses/`.
- In the Linux terminal: [PRoot](https://github.com/termux/proot) (GPL-2.0-or-later) and [talloc](https://talloc.samba.org) (LGPL-3.0-or-later), using the Android builds published by the [Acode](https://github.com/Acode-Foundation/Acode) project, and [Alpine Linux](https://alpinelinux.org). They run as separate programs; `assets/linux/NOTICE.txt` in the APK lists where their source code is.
