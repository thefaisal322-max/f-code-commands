# Fcode

A code editor for Android with a real Linux terminal built in. Part of the faiNET family of apps, by Faisal Hadawal.

- **Code tab** – the editor: tabs, a file sidebar, live preview, console, and a Run button for HTML, JSX, JavaScript and Python. Run works without internet.
- **Commands tab** – a real terminal, with several terminals side by side.
  - **Linux** (default): a small Alpine Linux system running through PRoot. `bash`, `git`, scripts, and a package manager: `pkg install NAME` (also `apt install NAME`; both wrap Alpine's `apk`), so Python, Node.js and thousands of other tools can be added.
  - **Android**: the phone's own shell (`/system/bin/sh`), for when Linux is not wanted or cannot start. Pick the shell in Settings.

## Where your files are

On first start Fcode asks to keep your work in the phone's storage. If you allow it, it makes one folder, `Faisal`, with two folders inside:

| Folder | What it is for | Name in the terminal |
| --- | --- | --- |
| `Faisal/codes` | every file made or opened in the Code tab | `~/codes` |
| `Faisal/commands` | where the terminal starts | `~/commands` |

Both show up in the phone's file manager, and both tabs see the same files: a file saved in the editor is in `~/codes` straight away, and a file the terminal writes there appears in the editor's sidebar.

If you choose "Not now", the two folders stay inside the app and move to the phone's storage when you allow access later (Settings → Files, or `setup-storage` in the terminal).

With storage access the terminal also has `~/storage/downloads`, `~/storage/shared` and the other usual folders, as in Termux (`termux-setup-storage` works too). To bring in a single file without that permission, use **Import File** in the sidebar.

Android does not let a file that is in the phone's storage be started directly. For scripts Fcode works around that: when you press Enter on a line that starts with a script from `~/commands` or `~/codes` (`./script.sh`), bash puts the program that runs it in front (`bash ./script.sh`). Compiled programs, and scripts started by other scripts, must be in the terminal's home folder (`~`) or be run as `bash script.sh`.

`termux-open LINK-or-FILE` (also `xdg-open`) opens a link in the phone's browser, or a file with the app that handles it; an `.apk` starts its installation. Tools that open a web page themselves, such as `gh auth login`, use it too.

Pashto, Dari and Arabic words in the terminal are drawn joined and from right to left.

Settings has three themes (Classic, Neon glows, Black and white), three languages (English, Pashto, Dari), and a step-by-step **Guide** in all three.

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
3. builds the release APK with Gradle and publishes it as the `latest` release,
4. installs the app on Android 11 and Android 14 emulators and uses it (see below).

### The automatic test

`scripts/emulator-test.mjs` drives the real app on an emulator: the first-start storage question, the `Faisal` folders, a file made in the editor, preview and Run, then about fifty commands in the Linux terminal (installing packages with `pkg` and `apt`, `git clone` and `git commit`, `curl`, zip and unzip, Python with `pip`, Node.js, scripts in and outside phone storage, unpacking a zip from Downloads and running the script inside it, opening a link and an APK, Pashto text), Android's own shell in a second terminal, themes, languages, and coming back from the background. What it saw, with screenshots, is pushed to the `ci-results` branch.

The emulators have Intel processors; phones have ARM processors. The app's own code is the same on both, but the Linux system and PRoot are different builds for each, so the test does not prove the Linux terminal on every phone.

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
| `app/src/main/java/com/fainet/fcode/Workspace.java` | The `Faisal/codes` and `Faisal/commands` folders and their links in the home folder |
| `app/src/main/java/com/fainet/fcode/ProjectFiles.java` | File access for the editor, inside the codes folder |
| `app/src/main/java/com/fainet/fcode/IntelCompat.java` | Intel/AMD devices only: adjusts Alpine's C library so it runs under Android's rules |
| `app/src/main/java/com/fainet/fcode/LocalServer.java` | Small web server behind "Open in Browser" |
| `app/src/main/java/com/fainet/fcode/KeepAliveService.java` | Keeps running shells alive while the app is in the background |
| `app/src/main/jni/fcode_pty.c` | Native code that creates the pseudo-terminal |
| `scripts/` | Download scripts used by the build, and the emulator test |
| `signing/fcode-debug.keystore` | The public test key (see Signing) |

`index.html` also works in a normal browser. There is no bridge there, so the Commands tab falls back to a small built-in shell and the project is kept in the browser's storage.

## Known limits

- A compiled program in the phone's storage (`~/codes`, `~/commands`, `~/storage`) cannot be started; keep it in `~`. Scripts there start with `./name` only when typed at the bash prompt, otherwise with `bash name`. Links (`ln -s`) cannot be made there either. These are Android's rules for shared storage.
- The Linux terminal is tested automatically on Intel emulators only (see "The automatic test"). If Linux cannot start on a phone, the terminal says so and opens Android's own shell.
- Run needs a reasonably recent "Android System WebView" for Python; on an old one it says to update it.
- Android may still stop background work on phones with aggressive battery saving.
- A Pashto or Dari word in the terminal is drawn correctly, but it still occupies one cell per letter, so the text after it starts a little further right than it would on paper, and the cursor covers the whole word while it is inside one.
- JSX files cannot be sent to "Open in Browser"; HTML files can.

## Licenses

Fcode's own code is in this repository. It is built with open-source software, each part under its own license:

- In the editor: [xterm.js](https://github.com/xtermjs/xterm.js) (MIT), [React](https://react.dev) (MIT), [Babel](https://babeljs.io) (MIT), [Pyodide](https://pyodide.org) (MPL-2.0), [Font Awesome Free](https://fontawesome.com) (icons CC BY 4.0, fonts SIL OFL 1.1, code MIT), [Fira Code](https://github.com/tonsky/FiraCode) and [Inter](https://github.com/rsms/inter) (both SIL OFL 1.1). Their license texts are copied into the APK under `assets/www/vendor/licenses/`.
- In the Linux terminal: [PRoot](https://github.com/termux/proot) (GPL-2.0-or-later) and [talloc](https://talloc.samba.org) (LGPL-3.0-or-later), using the Android builds published by the [Acode](https://github.com/Acode-Foundation/Acode) project, and [Alpine Linux](https://alpinelinux.org). They run as separate programs; `assets/linux/NOTICE.txt` in the APK lists where their source code is.
