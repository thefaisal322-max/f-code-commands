# Fcode

A code editor for Android with a real shell built in. Part of the faiNET family of apps, by Faisal Hadawal.

- **Code tab** – the editor: tabs, run HTML / JSX / JavaScript / Python, console, live preview.
- **Commands tab** – a real terminal. It runs Android's own shell (`/system/bin/sh`) on a pseudo-terminal, so commands such as `ls`, `cd`, `cp`, `mv`, `rm`, `mkdir`, `cat`, `grep`, `chmod` and `tar` are the phone's real ones, working on real files.

Both tabs work on the same real files: the shell's home folder (`~`) is the editor's project folder. A file made with `touch` shows up in the editor's sidebar, and a file edited in the Code tab is what `cat` prints.

To reach the phone's shared storage from the shell, run `setup-storage` (also available as `termux-setup-storage`). After you allow access, `~/storage/downloads`, `~/storage/shared` and the other usual folders appear. To bring in a single file without that permission, use **Import File** in the sidebar.

## Install

Open the repository's **Releases** page, download `Fcode.apk` from "Fcode - latest build", and open it on your phone. Android will ask you to allow installing apps from your browser or file manager the first time.

Each new build installs over the previous one; you don't need to uninstall first.

## How the APK is built

Every push runs `.github/workflows/build.yml` on GitHub Actions. It:

1. downloads the web libraries the page uses (xterm.js, Font Awesome, Fira Code, Inter) with `scripts/fetch-web-vendor.sh`,
2. builds the debug APK with Gradle,
3. publishes it as the `latest` release.

To build on your own computer you need Node.js, JDK 17, the Android SDK and NDK, and Gradle 8.7 or newer:

```sh
bash scripts/fetch-web-vendor.sh
gradle assembleDebug
```

## Layout

| Path | What it is |
| --- | --- |
| `app/src/main/assets/www/index.html` | The whole user interface (editor and terminal screen) |
| `app/src/main/java/com/fainet/fcode/MainActivity.java` | The app's single screen: a WebView plus the `FcodeNative` bridge |
| `app/src/main/java/com/fainet/fcode/ShellSession.java` | Starts and talks to the shell |
| `app/src/main/java/com/fainet/fcode/ProjectFiles.java` | File access for the editor, inside the shell's home folder |
| `app/src/main/jni/fcode_pty.c` | Native code that creates the pseudo-terminal |
| `signing/fcode-debug.keystore` | Signing key for test builds. It is public; use a private key before publishing to a store |

`index.html` also works in a normal browser. There is no real shell there, so the Commands tab falls back to a small built-in shell that only knows the project's own files.

## Not done yet

- Android's shell is `sh`, not `bash`, and has no package manager. Adding a bundled Linux environment (for `bash`, `git`, `unzip`, `python`, package installs) is planned.
- Themes and Pashto / Dari translations are planned.

## Licenses

The app bundles [xterm.js](https://github.com/xtermjs/xterm.js) (MIT), [Font Awesome Free](https://fontawesome.com) (icons CC BY 4.0, fonts SIL OFL 1.1, code MIT), [Fira Code](https://github.com/tonsky/FiraCode) and [Inter](https://github.com/rsms/inter) (both SIL OFL 1.1). Their license texts are copied into the APK under `assets/www/vendor/licenses/`.
