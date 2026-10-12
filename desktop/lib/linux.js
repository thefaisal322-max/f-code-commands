'use strict';
// The terminal's Linux on Windows: MSYS2 (bash, git, pacman), shipped inside the installer as a
// self-extracting archive (MSYS2's own .sfx.exe) and unpacked into %LOCALAPPDATA%\Fcode\linux the first time a Linux terminal opens.
// It lives outside the program folder so that installing a new version of Fcode keeps every
// package the user has added.

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

// Change this when the bundled MSYS2 or Fcode's scripts inside it need a fresh unpack
const VERSION = 'msys2-1';

function createLinux({ base, resources }) {
    const linuxDir = path.join(base, 'linux');
    const root = path.join(linuxDir, 'msys64');
    const marker = path.join(linuxDir, 'installed-' + VERSION);
    const bash = path.join(root, 'usr', 'bin', 'bash.exe');
    const archive = path.join(resources, 'linux', 'msys2-base.sfx.exe');

    function isSupported() {
        return fs.existsSync(archive) || fs.existsSync(bash);
    }

    function isInstalled() {
        return fs.existsSync(marker) && fs.existsSync(bash);
    }

    // MSYS2's archive unpacks itself (7-Zip): -y answers yes, -o names the folder
    function unpack() {
        return new Promise((resolve, reject) => {
            const child = spawn(archive, ['-y', '-o' + linuxDir], { windowsHide: true });
            let output = '';
            child.stdout.on('data', d => { output += d.toString(); });
            child.stderr.on('data', d => { output += d.toString(); });
            child.on('error', reject);
            child.on('exit', code => (code === 0 && fs.existsSync(bash) ? resolve() : reject(new Error('unpacking ended with ' + code + ': ' + output.slice(-400)))));
        });
    }

    // Several terminals may open at once: they all wait for the same unpacking
    let installing = null;
    function install(options) {
        if (isInstalled()) return Promise.resolve();
        if (!installing) installing = doInstall(options || {}).finally(() => { installing = null; });
        return installing;
    }

    // MSYS2's first start sets up its package keys and prints a page of messages; do it here,
    // out of sight, so the user's first terminal opens straight at the prompt
    function firstStart({ home, env }) {
        return new Promise(resolve => {
            const spec = command({ home, env });
            const child = spawn(spec.file, ['--login', '-c', 'exit 0'], {
                env: spec.env,
                cwd: home,
                windowsHide: true,
                stdio: 'ignore'
            });
            const timer = setTimeout(() => { try { child.kill(); } catch (e) { /* gone */ } resolve(); }, 300000);
            child.on('error', () => { clearTimeout(timer); resolve(); });
            child.on('exit', () => { clearTimeout(timer); resolve(); });
        });
    }

    async function doInstall(options) {
        if (!fs.existsSync(archive)) throw new Error('the Linux system is missing from this installation');
        fs.rmSync(root, { recursive: true, force: true });
        fs.mkdirSync(linuxDir, { recursive: true });
        await unpack();
        writeScripts();
        if (options.home) await firstStart(options);
        fs.writeFileSync(marker, new Date().toISOString() + '\n');
        removeArchive();
    }

    // Once unpacked, the archive in the program folder is not needed: free the space
    // (an update of Fcode brings it again, and it is removed again)
    function removeArchive() {
        if (!isInstalled()) return;
        try { fs.rmSync(archive, { force: true }); } catch (e) { /* in use: next time */ }
    }

    function write(relative, text, executable) {
        const file = path.join(root, ...relative.split('/'));
        fs.mkdirSync(path.dirname(file), { recursive: true });
        // Unix line endings: bash reads these files
        fs.writeFileSync(file, text.replace(/\r\n/g, '\n'));
        if (executable) {
            try { fs.chmodSync(file, 0o755); } catch (e) { /* MSYS2 decides by the first line anyway */ }
        }
    }

    // Fcode's own files inside the Linux system; rewritten on every start so they follow updates
    function writeScripts() {
        write('etc/fcode/bash.sh', `# Fcode's shell settings. This file is rewritten by the app:
# put your own settings in ~/.bashrc instead.
# The folder in green, as in Termux
PS1='\\[\\e[0;32m\\]\\w\\[\\e[0m\\] $ '
alias ll='ls -l'
alias ls='ls --color=auto'
command_not_found_handle() {
    echo "$1: command not found. If it is a program you can install, try: pkg install $1" >&2
    return 127
}
if [ ! -e /etc/fcode/setup-done ] && [ -t 0 ]; then
    fcode-setup --ask
fi
`);

        write('usr/local/bin/fcode-setup', `#!/bin/bash
# Installs the tools most people expect in a terminal.
PACKAGES='git curl unzip zip nano openssh'
if [ "$1" = "--ask" ]; then
    mkdir -p /etc/fcode && : > /etc/fcode/setup-done    # ask only once
    echo 'Linux is ready.'
    echo
    echo 'Install git, curl, unzip, zip and nano now?'
    printf 'This needs internet. [Y/n] '
    read answer
    case "$answer" in
        n|N|no|No|NO) echo 'Skipped. Run fcode-setup whenever you want them.'; exit 0 ;;
    esac
fi
echo 'Installing...'
if pacman -Sy --noconfirm && pacman -S --needed --noconfirm $PACKAGES; then
    echo
    echo 'Done. More tools: pkg install NAME'
else
    echo
    echo 'That did not work. Check your internet, then run: fcode-setup'
    exit 1
fi
`, true);

        const pkg = `#!/bin/bash
# Termux-style front end for MSYS2's package manager (pacman),
# so 'pkg install python' and 'apt install git' work as on the phone.
UCRT=mingw-w64-ucrt-x86_64-
usage() {
    echo 'Usage: pkg COMMAND [packages]'
    echo '  install NAME...    install packages'
    echo '  uninstall NAME...  remove packages'
    echo '  update             refresh the package list'
    echo '  upgrade            update everything installed'
    echo '  search TEXT        find packages'
    echo '  list-installed     show installed packages'
    echo '  show NAME          describe a package'
}
# Names as on the phone -> names in MSYS2. Windows programs (Python, Node.js, compilers)
# come from the UCRT64 part of MSYS2, so they work like ordinary Windows programs.
translate() {
    case "$1" in
        python|python3|python-dev) echo "\${UCRT}python" ;;
        python-pip|pip|pip3|py3-pip|python3-pip) echo "\${UCRT}python-pip" ;;
        nodejs|nodejs-lts|node|npm) echo "\${UCRT}nodejs" ;;
        build-base|build-essential|gcc|g++) echo "\${UCRT}gcc make" ;;
        clang) echo "\${UCRT}clang" ;;
        gh|github-cli) echo "\${UCRT}github-cli" ;;
        golang|go) echo "\${UCRT}go" ;;
        rust|cargo) echo "\${UCRT}rust" ;;
        ssh|openssh-client) echo openssh ;;
        *)
            if pacman -Si "$1" >/dev/null 2>&1; then echo "$1"
            elif pacman -Si "\${UCRT}$1" >/dev/null 2>&1; then echo "\${UCRT}$1"
            else echo "$1"
            fi ;;
    esac
}
command="$1"
[ $# -gt 0 ] && shift
names=''
for arg in "$@"; do
    case "$arg" in
        -y|--yes|--assume-yes|-q) ;;      # pacman is told not to ask
        -*) names="$names $arg" ;;
        *) names="$names $(translate "$arg")" ;;
    esac
done
case "$command" in
    install|add|in|i)
        pacman -Sy --noconfirm >/dev/null 2>&1
        names=''
        for arg in "$@"; do case "$arg" in -*) ;; *) names="$names $(translate "$arg")" ;; esac; done
        exec pacman -S --needed --noconfirm $names ;;
    uninstall|remove|rm|del|purge) exec pacman -R --noconfirm $names ;;
    update|up) exec pacman -Sy ;;
    upgrade|full-upgrade|dist-upgrade) exec pacman -Syu --noconfirm ;;
    search|se) exec pacman -Ss $names ;;
    list-installed|list) exec pacman -Q ;;
    show|info) exec pacman -Si $names ;;
    files) exec pacman -Ql $names ;;
    clean|autoclean|autoremove) exec pacman -Sc --noconfirm ;;
    ''|help|-h|--help) usage ;;
    *) echo "pkg: unknown command '$command'"; usage; exit 1 ;;
esac
`;
        write('usr/local/bin/pkg', pkg, true);
        write('usr/local/bin/apt', pkg, true);
        write('usr/local/bin/apt-get', pkg, true);

        // Opening links and files: the request is left in a folder the app watches
        const open = `#!/bin/bash
# Opens a link in the browser, or a file with the program that handles it.
name=\${0##*/}
while [ $# -gt 0 ]; do
    case "$1" in
        --content-type) shift; [ $# -gt 0 ] && shift ;;
        --help|-h) echo "usage: $name LINK-or-FILE"; exit 0 ;;
        --*) shift ;;
        *) break ;;
    esac
done
[ $# -gt 0 ] || { echo "usage: $name LINK-or-FILE" >&2; exit 1; }
target=$1
case "$target" in
    [a-zA-Z][a-zA-Z0-9+.-]*://*|mailto:*|tel:*) ;;
    *)
        [ -e "$target" ] || { echo "$name: $target: no such file" >&2; exit 1; }
        target=$(cygpath -w "$(realpath "$target")") ;;
esac
dir=$(cygpath -u "$FCODE_OPEN_DIR")
[ -d "$dir" ] || { echo "$name: this only works in Fcode's terminal" >&2; exit 1; }
request="$dir/$(date +%s%N)-$$.req"
printf '%s\\n%s\\n' "\${FCODE_TERMINAL_ID:-0}" "$target" > "$request.part" && mv "$request.part" "$request"
`;
        write('usr/local/bin/termux-open', open, true);
        write('usr/local/bin/termux-open-url', open, true);
        write('usr/local/bin/xdg-open', open, true);

        const storage = `#!/bin/bash
echo "On Windows your files are already in Documents\\\\Faisal: ~/codes and ~/commands."
echo "Other folders: /c/Users/$USERNAME (your Windows user folder)."
`;
        write('usr/local/bin/setup-storage', storage, true);
        write('usr/local/bin/termux-setup-storage', storage, true);
    }

    // ~/.bashrc and ~/.bash_profile are the user's; Fcode only makes sure they load its settings
    function prepareHome(home) {
        const profile = path.join(home, '.bash_profile');
        const rc = path.join(home, '.bashrc');
        if (!fs.existsSync(profile)) fs.writeFileSync(profile, '# Loads ~/.bashrc for the terminal\n[ -f ~/.bashrc ] && . ~/.bashrc\n');
        if (!fs.existsSync(rc)) fs.writeFileSync(rc, '# Fcode\'s settings (prompt, pkg, ...). Your own settings go below.\n[ -f /etc/fcode/bash.sh ] && . /etc/fcode/bash.sh\n');
    }

    // The program and environment for a Linux terminal
    function command({ home, env }) {
        writeScripts();
        prepareHome(home);
        return {
            file: bash,
            args: ['--login', '-i'],
            env: Object.assign({}, env, {
                MSYSTEM: 'UCRT64',          // Windows builds of Python, Node.js, gcc...
                CHERE_INVOKING: '1',        // start in the folder given, not in ~
                MSYS2_PATH_TYPE: 'minimal',
                HOME: home,
                LANG: 'C.UTF-8',
                TERM: 'xterm-256color',
                COLORTERM: 'truecolor'
            })
        };
    }

    return { isSupported, isInstalled, install, command, removeArchive, root };
}

module.exports = { createLinux };
