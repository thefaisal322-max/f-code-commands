// Runs the real app on an Android emulator and checks that it works: storage folders, the
// editor's files, the Linux terminal (packages, git, scripts) and Android's own shell.
//
// The app's page runs in a WebView; the debug build lets tools talk to it over the Chrome
// DevTools Protocol, so this script can call the page's own functions and read the terminal.
//
// Usage: node scripts/emulator-test.mjs <debug apk> <results folder>
// Needs: adb on PATH with one emulator running, Node.js 22 or newer.

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';

const [apk, outDir = 'test-results'] = process.argv.slice(2);
if (!apk) {
    console.error('usage: node scripts/emulator-test.mjs <debug apk> <results folder>');
    process.exit(2);
}

const PACKAGE = 'com.fainet.fcode';
const ACTIVITY = PACKAGE + '/.MainActivity';
const DEVTOOLS_PORT = 9333;

mkdirSync(outDir, { recursive: true });
const logFile = join(outDir, 'log.txt');
writeFileSync(logFile, '');
const results = [];   // { name, ok, required, detail }

function log(...parts) {
    const line = parts.join(' ');
    console.log(line);
    appendFileSync(logFile, line + '\n');
}

function adb(...args) {
    try {
        return execFileSync('adb', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
        return (e.stdout || '') + (e.stderr || '');
    }
}

function adbBinary(...args) {
    return execFileSync('adb', args, { maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function check(name, ok, detail = '', required = true) {
    results.push({ name, ok: !!ok, required, detail: String(detail).slice(0, 2000) });
    log((ok ? 'PASS' : (required ? 'FAIL' : 'note')) + '  ' + name + (detail ? '\n      ' + String(detail).trim().split('\n').join('\n      ').slice(0, 2000) : ''));
}

/* ---------- Chrome DevTools Protocol, just enough of it ---------- */

class Page {
    constructor(socket) {
        this.socket = socket;
        this.nextId = 1;
        this.waiting = new Map();
        socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id && this.waiting.has(message.id)) {
                const { resolve, reject } = this.waiting.get(message.id);
                this.waiting.delete(message.id);
                if (message.error) reject(new Error(message.error.message));
                else resolve(message.result);
            }
        });
    }

    send(method, params = {}) {
        const id = this.nextId++;
        return new Promise((resolve, reject) => {
            this.waiting.set(id, { resolve, reject });
            this.socket.send(JSON.stringify({ id, method, params }));
            setTimeout(() => {
                if (this.waiting.delete(id)) reject(new Error('no answer to ' + method));
            }, 60000);
        });
    }

    // Runs JavaScript in the app's page and returns its value
    async run(expression) {
        const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
        if (result.exceptionDetails) {
            const what = result.exceptionDetails.exception && result.exceptionDetails.exception.description;
            throw new Error('page error: ' + (what || result.exceptionDetails.text));
        }
        return result.result.value;
    }

    // Runs JavaScript in the page for its effect only (the value may be something that cannot be sent back)
    async do(expression) {
        const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: false });
        if (result.exceptionDetails) {
            const what = result.exceptionDetails.exception && result.exceptionDetails.exception.description;
            throw new Error('page error: ' + (what || result.exceptionDetails.text));
        }
    }

    async screenshot(name) {
        try {
            const shot = await this.send('Page.captureScreenshot', { format: 'png' });
            writeFileSync(join(outDir, name + '.png'), Buffer.from(shot.data, 'base64'));
        } catch (e) {
            log('  (page screenshot ' + name + ' failed: ' + e.message + ')');
        }
        // the whole screen as well: status bar, system dialogs, keyboard
        try {
            writeFileSync(join(outDir, name + '-screen.png'), adbBinary('exec-out', 'screencap', '-p'));
        } catch (e) {
            log('  (screen capture ' + name + ' failed: ' + e.message + ')');
        }
    }

    close() {
        try { this.socket.close(); } catch (e) { /* already closed */ }
    }
}

async function connectToApp() {
    for (let attempt = 0; attempt < 60; attempt++) {
        const sockets = adb('shell', 'cat', '/proc/net/unix').split('\n')
            .map(line => (line.match(/@(webview_devtools_remote_\d+)/) || [])[1])
            .filter(Boolean);
        for (const name of sockets) {
            adb('forward', 'tcp:' + DEVTOOLS_PORT, 'localabstract:' + name);
            try {
                const pages = await (await fetch('http://127.0.0.1:' + DEVTOOLS_PORT + '/json')).json();
                const page = pages.find(p => p.type === 'page' && /appassets\.androidplatform\.net/.test(p.url));
                if (page) {
                    const socket = new WebSocket(page.webSocketDebuggerUrl);
                    await new Promise((resolve, reject) => {
                        socket.addEventListener('open', resolve, { once: true });
                        socket.addEventListener('error', () => reject(new Error('websocket failed')), { once: true });
                    });
                    const connected = new Page(socket);
                    await connected.send('Runtime.enable');
                    // wait until the page's own script has finished starting up
                    for (let i = 0; i < 100; i++) {
                        const ready = await connected.run("typeof switchMode === 'function' && document.readyState === 'complete'").catch(() => false);
                        if (ready) return connected;
                        await sleep(200);
                    }
                    connected.close();
                }
            } catch (e) {
                // not this socket, or not ready yet
            }
        }
        await sleep(1000);
    }
    throw new Error('could not reach the app\'s page');
}

async function startApp() {
    adb('shell', 'am', 'force-stop', PACKAGE);
    await sleep(500);
    log(adb('shell', 'am', 'start', '-W', '-n', ACTIVITY).trim());
    return connectToApp();
}

/* ---------- the terminal ---------- */

const TERMINAL_TEXT = `(() => {
    const term = activeTerminal;
    if (!term) return '';
    const buffer = term.xterm.buffer.active;
    const lines = [];
    for (let i = 0; i < buffer.length; i++) {
        const line = buffer.getLine(i);
        const text = line.translateToString(true);
        if (line.isWrapped && lines.length) lines[lines.length - 1] += text;   // rejoin long lines
        else lines.push(text);
    }
    while (lines.length && !lines[lines.length - 1]) lines.pop();
    return lines.join('\\n');
})()`;

async function terminalText(page) {
    return page.run(TERMINAL_TEXT);
}

async function type(page, text) {
    await page.do('FcodeNative.write(activeTerminal.id, ' + JSON.stringify(text) + ')');
}

async function waitForTerminal(page, pattern, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    let text = '';
    while (Date.now() < deadline) {
        text = await terminalText(page);
        if (pattern.test(text)) return { found: true, text };
        await sleep(500);
    }
    return { found: false, text };
}

let commandCounter = 0;

// Runs one command in the active terminal and returns what it printed and its exit code
async function runCommand(page, command, timeoutMs = 60000) {
    const marker = 'FCODE_END_' + (++commandCounter);
    await page.do('activeTerminal.xterm.clear()');
    await type(page, command + '; echo ' + marker + ':$?\r');   // \r is what the Enter key sends
    // The marker is not always at the start of a line: a file without a final newline ends
    // right before it. The typed command never matches, because there the marker is followed by "$?".
    const { found, text } = await waitForTerminal(page, new RegExp(marker + ':\\d+'), timeoutMs);
    if (!found) {
        await type(page, '\x03');   // Ctrl+C, so the next command starts clean
        await sleep(1000);
        return { output: text, code: -1, timedOut: true };
    }
    const at = text.search(new RegExp(marker + ':\\d+'));
    const code = Number(text.slice(at + marker.length + 1).match(/^\d+/)[0]);
    // drop the echoed command line (which contains the marker text too)
    const output = text.slice(0, at).split('\n').filter(line => !line.includes(marker)).join('\n').trim();
    return { output, code, timedOut: false };
}

async function expectCommand(page, name, command, pattern, { timeoutMs = 60000, required = true } = {}) {
    const result = await runCommand(page, command, timeoutMs);
    const ok = !result.timedOut && pattern.test(result.output);
    check(name, ok, '$ ' + command + '\n' + result.output + (result.timedOut ? '\n(timed out)' : '\n(exit code ' + result.code + ')'), required);
    return ok;
}

// Did Android start an activity for a link or file since the log was last cleared?
function startedActivity(pattern) {
    const log = adb('logcat', '-d', '-s', 'ActivityTaskManager:I', 'ActivityManager:I');
    const line = log.split('\n').filter(l => /START u0/.test(l) && pattern.test(l)).pop();
    return line ? line.replace(/^.*START u0/, 'START u0').slice(0, 400) : '';
}

async function backToApp() {
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
    await sleep(800);
    adb('shell', 'am', 'start', '-n', ACTIVITY);
    await sleep(2500);
}

// Colour of the first character of the line the cursor is on: "~:2" is a green ~
const PROMPT_COLOUR = `(() => {
    const b = activeTerminal.xterm.buffer.active;
    const cell = b.getLine(b.baseY + b.cursorY).getCell(0);
    return cell.getChars() + ':' + (cell.isFgPalette() ? cell.getFgColor() : 'not-palette');
})()`;

// Right-to-left words: is the first letter drawn to the right of the last one, in one piece?
const RTL_PIECES = `(() => {
    const out = [];
    activeTerminal.pane.querySelectorAll('.xterm-rows > div').forEach(row => {
        for (const piece of row.children) {
            const node = piece.firstChild;
            if (!node || !/[\\u0600-\\u06FF]/.test(node.nodeValue || '')) continue;
            const text = node.nodeValue;
            const x = j => { const r = document.createRange(); r.setStart(node, j); r.setEnd(node, j + 1); const box = r.getBoundingClientRect(); return box.left + box.width / 2; };
            out.push({ text, rightToLeft: text.length < 2 || x(0) > x(text.length - 1) });
        }
    });
    return JSON.stringify(out);
})()`;

/* ---------- the test ---------- */

async function main() {
    log('Device: Android ' + adb('shell', 'getprop', 'ro.build.version.release').trim()
        + ' (API ' + adb('shell', 'getprop', 'ro.build.version.sdk').trim() + '), '
        + adb('shell', 'getprop', 'ro.product.cpu.abi').trim());

    adb('uninstall', PACKAGE);
    const install = adb('install', '-r', apk);
    check('The APK installs', /Success/.test(install), install);
    adb('logcat', '-c');

    /* --- first start, before storage access is decided --- */
    let page = await startApp();
    await sleep(1500);
    await page.screenshot('01-first-start');
    check('First start shows the "where to keep files" screen',
        await page.run("document.getElementById('storagePanel').classList.contains('active')"));
    let info = JSON.parse(await page.run('FcodeNative.info()'));
    log('App info: ' + JSON.stringify(info));
    check('Without storage access the files stay inside the app', info.sharedWorkspace === false && info.storageAccess === false, JSON.stringify(info));
    await page.do('skipStorage()');
    check('The starter index.html is open', (await page.run("document.getElementById('codeEditor').value.length")) > 100,
        'active file: ' + await page.run('activeFileName'));
    await page.run("nativeFs('fsWrite', 'made-before-access.txt', 'kept\\n').ok");
    await page.screenshot('02-editor');
    page.close();

    /* --- allow storage access, start again: the Faisal folder --- */
    log(adb('shell', 'appops', 'set', '--uid', PACKAGE, 'MANAGE_EXTERNAL_STORAGE', 'allow').trim());
    log(adb('shell', 'appops', 'set', PACKAGE, 'MANAGE_EXTERNAL_STORAGE', 'allow').trim());
    page = await startApp();
    await sleep(1500);
    info = JSON.parse(await page.run('FcodeNative.info()'));
    log('App info: ' + JSON.stringify(info));
    check('With storage access the work lives in the Faisal folder', info.sharedWorkspace === true, JSON.stringify(info));
    const folderListing = adb('shell', 'ls', '-la', '/sdcard/Faisal', '/sdcard/Faisal/codes', '/sdcard/Faisal/commands');
    check('Faisal/codes and Faisal/commands exist on the phone\'s storage',
        /codes/.test(folderListing) && /commands/.test(folderListing), folderListing);
    check('Files made earlier moved into Faisal/codes', /made-before-access\.txt/.test(folderListing) && /index\.html/.test(folderListing), folderListing);
    check('The storage screen is not shown again', !(await page.run("document.getElementById('storagePanel').classList.contains('active')")));

    // a file made in the Code tab lands in Faisal/codes
    await page.run("nativeFs('fsWrite', 'site/page.html', '<h1>made in the editor</h1>').ok");
    const made = adb('shell', 'cat', '/sdcard/Faisal/codes/site/page.html');
    check('A file created in the Code tab is in Faisal/codes', /made in the editor/.test(made), made);
    check('The sidebar names the folder', (await page.run('codesLabel("")')) === 'Faisal/codes', await page.run('codesLabel("")'));

    // preview with a linked stylesheet
    await page.run("nativeFs('fsWrite', 'site/style.css', 'h1 { color: rgb(255, 0, 0); }').ok");
    await page.run("nativeFs('fsWrite', 'site/index.html', '<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1 id=\"t\">Hello</h1></body></html>').ok");
    await page.do("nativeOpenPath('site/index.html')");
    await page.do('runLivePreview()');
    await sleep(2500);
    const previewColour = await page.run("(() => { const d = document.getElementById('previewFrame').contentDocument; const h = d && d.getElementById('t'); return h ? getComputedStyle(h).color : 'no preview'; })()");
    check('The preview loads the stylesheet the page links to', previewColour === 'rgb(255, 0, 0)', previewColour);
    await page.screenshot('03-preview');
    await page.do('closeLivePreview()');

    // Run a Python file without internet libraries
    await page.run("nativeFs('fsWrite', 'main.py', 'print(\"python says\", 6 * 7)').ok");
    await page.do("nativeOpenPath('main.py')");
    await page.do('runLivePreview()');
    let pythonLog = '';
    for (let i = 0; i < 120; i++) {
        pythonLog = await page.run("document.getElementById('consoleLogs').innerText");
        if (/python says 42|Error|error|WebView/.test(pythonLog)) break;
        await sleep(500);
    }
    // Python needs a WebView from 2022 or newer; old emulator images ship an older one that
    // real phones have long since updated through the Play Store.
    const webViewVersion = Number(((await page.run('navigator.userAgent')).match(/Chrome\/(\d+)/) || [])[1]) || 0;
    log('WebView version: ' + webViewVersion);
    if (webViewVersion >= 100) {
        check('Run executes a Python file', /python says 42/.test(pythonLog), pythonLog);
    } else {
        check('Run on an old WebView says how to get Python working', /Android System WebView/.test(pythonLog), pythonLog);
    }
    await page.do('closeConsole()');

    /* --- the Linux terminal --- */
    await page.do("switchMode('commands')");
    let state = await waitForTerminal(page, /\[Y\/n\]|\$\s*$|Using Android's own shell/, 180000);
    await page.screenshot('04-terminal-first-start');
    log('--- terminal after first start ---\n' + state.text + '\n---');
    const linuxStarted = /\[Y\/n\]/.test(state.text) || (/\$\s*$/.test(state.text) && !/Using Android's own shell/.test(state.text));
    check('The Linux terminal starts', linuxStarted, state.text);

    if (linuxStarted) {
        if (/\[Y\/n\]/.test(state.text)) {
            await type(page, 'y\r');
            state = await waitForTerminal(page, /Done\. More tools|That did not work/, 600000);
            log('--- terminal after installing the tools ---\n' + state.text.split('\n').slice(-25).join('\n') + '\n---');
            check('bash, git and the other tools install', /Done\. More tools/.test(state.text), state.text.split('\n').slice(-15).join('\n'));
            await waitForTerminal(page, /\$\s*$/, 20000);
        }
        await page.screenshot('05-terminal-ready');
    }

    // Nothing else can pass if Linux cannot start a program, so find that out first
    const linuxRuns = linuxStarted
        && await expectCommand(page, 'Linux starts programs', '/bin/busybox echo program-$((1+1))', /^program-2$/m, { timeoutMs: 30000 });

    if (linuxRuns) {
        await expectCommand(page, 'Arithmetic and echo', 'echo hello-$((6*7))', /^hello-42$/m);
        // The checks read the terminal's memory; this one looks at what is actually drawn
        const drawn = await page.run("activeTerminal.pane.querySelector('.xterm-rows').innerText");
        check('The terminal draws its text on the screen', /hello-42/.test(drawn) && /\$/.test(drawn), drawn.trim().slice(0, 300) || '(nothing is drawn)');
        await expectCommand(page, 'The terminal starts in the commands folder', 'pwd; pwd -P', /\/commands$/m);
        await expectCommand(page, 'The prompt folder is ~/commands', 'echo "$PWD" | sed "s|$HOME|~|"', /^~\/commands$/m);
        await expectCommand(page, 'It is bash', 'echo $BASH_VERSION; bash --version | head -n 1', /GNU bash/);
        await expectCommand(page, 'git is there', 'git --version', /git version \d/);
        await expectCommand(page, 'curl reaches the internet', 'curl -sI https://example.com | head -n 1', /HTTP\/\S+ 200/, { timeoutMs: 60000 });
        await expectCommand(page, 'pkg install works (python3)', 'pkg install -y python3 >/dev/null 2>&1; python3 -c "print(6*7)"', /^42$/m, { timeoutMs: 300000 });
        await expectCommand(page, 'apt install works too (jq)', 'apt install -y jq >/dev/null 2>&1; echo \'{"a":5}\' | jq .a', /^5$/m, { timeoutMs: 180000 });
        await expectCommand(page, 'An unknown command names its package', 'htop --version', /pkg install htop|htop \d/);
        await expectCommand(page, './script.sh runs in the home folder', 'printf \'#!/bin/sh\\necho script-ran-$1\\n\' > ~/t.sh; chmod +x ~/t.sh; ~/t.sh ok', /^script-ran-ok$/m);
        await expectCommand(page, 'bash script.sh runs in the commands folder', 'cd ~/commands; printf \'echo from-commands-$((2+3))\\n\' > s.sh; bash s.sh', /^from-commands-5$/m);
        check('The prompt folder is green', (await page.run(PROMPT_COLOUR)) === '~:2', await page.run(PROMPT_COLOUR));
        // Android refuses to start files in phone storage; Fcode puts the interpreter in front when Enter is pressed
        await runCommand(page, 'cd ~/commands; printf \'#!/data/data/com.termux/files/usr/bin/bash\\necho direct-run-$1\\n\' > d.sh; printf \'#!/usr/bin/env python3\\nprint("python-direct", 6 * 7)\\n\' > p.py; printf \'echo no-first-line\\n\' > n.sh');
        await expectCommand(page, './script.sh works in the commands folder (phone storage)', './d.sh one', /^direct-run-one$/m);
        await expectCommand(page, './script.sh without a first line works there too', './n.sh', /^no-first-line$/m);
        await expectCommand(page, './script.sh piped into another command', './d.sh two | tr a-z A-Z', /^DIRECT-RUN-TWO$/m);
        await expectCommand(page, 'The Code tab\'s files are in ~/codes', 'ls ~/codes; cat ~/codes/site/page.html', /made in the editor/);
        await expectCommand(page, 'A file written by the terminal reaches the editor', 'echo from-terminal > ~/codes/from-terminal.txt; cat ~/codes/from-terminal.txt', /^from-terminal$/m);
        const seen = JSON.parse(await page.run("JSON.stringify(nativeFs('fsRead', 'from-terminal.txt'))"));
        check('The editor reads the terminal\'s file', seen.ok && /from-terminal/.test(seen.content), JSON.stringify(seen));
        await expectCommand(page, 'git clone into the home folder', 'cd ~; rm -rf hw; git clone -q --depth 1 https://github.com/octocat/Hello-World.git hw 2>&1; ls hw', /README/, { timeoutMs: 180000 });
        await expectCommand(page, 'git commit works', 'cd ~/hw; git config user.email t@example.com; git config user.name T; echo x > x.txt; git add x.txt; git commit -qm test 2>&1; git log --oneline | head -n 1', /test/, { timeoutMs: 60000 });
        log('--- how git stored its files ---\n' + (await runCommand(page, 'ls -la ~/hw/.git/objects/pack/ | head -n 12; cat /etc/gitconfig')).output + '\n---');
        await expectCommand(page, 'rm -rf removes a folder', 'cd ~; rm -rf hw; ls hw 2>&1 | head -n 1; [ -e hw ] || echo gone', /^gone$/m);
        await expectCommand(page, 'git clone into the commands folder (phone storage)', 'cd ~/commands; rm -rf hw2; git clone -q --depth 1 https://github.com/octocat/Hello-World.git hw2 2>&1; ls hw2', /README/, { timeoutMs: 180000 });
        await expectCommand(page, 'zip and unzip', 'cd ~/commands; rm -rf z z.zip; mkdir z; echo inside > z/a.txt; zip -qr z.zip z; rm -rf z; unzip -oq z.zip; cat z/a.txt', /^inside$/m);
        await expectCommand(page, 'git works in the commands folder too', 'cd ~/commands; rm -rf proj; mkdir proj; cd proj; git init -q 2>&1; echo hi > a.txt; git add a.txt; git -c user.name=T -c user.email=t@example.com commit -qm first 2>&1; git log --oneline | head -n 1; cd ..', /first/);
        // The tip comes with the next prompt, so this one is typed on its own
        await page.do('activeTerminal.xterm.clear()');
        await type(page, 'cd ~/commands; cp /bin/busybox bb\r');
        await sleep(1500);
        await type(page, './bb echo hi\r');
        const tip = await waitForTerminal(page, /Tip: .*copy them to ~ first/, 10000);
        check('A tip explains why a program cannot start from phone storage', tip.found, tip.text);
        await runCommand(page, 'rm -f ~/commands/bb');
        await expectCommand(page, 'Programs start with an empty environment too', 'env -i /bin/sh -c "ls / | head -n 1; echo empty-env-ok"', /^empty-env-ok$/m);
        await expectCommand(page, 'Android\'s own commands still run', 'getprop ro.build.version.sdk', /^\d+$/m, { required: false });

        // What the user asked for at the very start: unpack a zip from Downloads and run its script
        adb('shell', 'mkdir', '-p', '/sdcard/Download');
        await runCommand(page, 'rm -rf /tmp/fa ~/faisal-app; mkdir -p /tmp/fa/faisal-app; printf \'#!/bin/bash\\ncd "$(dirname "$0")"\\ngit init -q . 2>&1\\ngit add -A\\ngit -c user.name=F -c user.email=f@example.com commit -qm publish 2>&1\\necho published-$(git log --oneline | wc -l)\\n\' > /tmp/fa/faisal-app/publish.sh; echo "<h1>app</h1>" > /tmp/fa/faisal-app/index.html; (cd /tmp/fa && zip -qr /sdcard/Download/faisal-app.zip faisal-app); ls -l /sdcard/Download/faisal-app.zip');
        await expectCommand(page, 'termux-setup-storage gives ~/storage/downloads', 'termux-setup-storage; sleep 3; ls ~/storage/downloads/', /faisal-app\.zip/);
        await expectCommand(page, 'pkg install -y unzip', 'pkg install -y unzip 2>&1 | tail -n 1', /OK:/, { timeoutMs: 120000 });
        await expectCommand(page, 'cd ~ && unzip -o ~/storage/downloads/faisal-app.zip', 'cd ~ && unzip -o ~/storage/downloads/faisal-app.zip', /inflating: faisal-app\/publish\.sh/);
        await expectCommand(page, 'bash ~/faisal-app/publish.sh', 'bash ~/faisal-app/publish.sh', /^published-1$/m);
        await expectCommand(page, 'pip installs a Python package', 'pkg install -y python-pip >/dev/null 2>&1; pip install -q --no-input six 2>&1 | tail -n 2; python3 -c "import six; print(\'six\', six.__version__)"', /^six \d/m, { timeoutMs: 300000, required: false });
        await expectCommand(page, 'pkg install nodejs', 'pkg install -y nodejs >/dev/null 2>&1; node -e "console.log(\'node\', 1+1)"', /^node 2$/m, { timeoutMs: 400000, required: false });
        await expectCommand(page, 'System facts', 'cat /etc/alpine-release; id; uname -m', /^3\.\d+/m, { required: false });
        await runCommand(page, 'cd ~/commands');
        await expectCommand(page, './p.py runs with python3', './p.py', /^python-direct 42$/m);

        // Pasting several lines at once, then Enter
        await page.do('activeTerminal.xterm.clear()');
        await page.do('activeTerminal.xterm.paste("echo paste-one\\necho paste-two")');
        await sleep(500);
        await type(page, '\r');
        const pasted = await waitForTerminal(page, /^paste-one$[\s\S]*^paste-two$/m, 10000);
        check('Several pasted lines run', pasted.found, pasted.text);

        // Pashto and Dari words are drawn joined and from right to left
        await page.do('activeTerminal.xterm.clear()');
        await type(page, 'echo "سلام نړۍ"; echo "کوډ لیکل په تلیفون کې اسانه دي."; echo "file: کتاب.pdf"\r');
        await waitForTerminal(page, /کتاب\.pdf\n/, 10000);
        await sleep(600);
        const pieces = JSON.parse(await page.run(RTL_PIECES));
        check('Pashto words are drawn in one piece, right to left',
            pieces.some(p => p.text === 'سلام نړۍ' && p.rightToLeft) && pieces.every(p => p.rightToLeft), JSON.stringify(pieces));
        await page.screenshot('06b-pashto-in-terminal');

        // termux-open / xdg-open: a link, a file, an APK
        adb('logcat', '-c');
        await runCommand(page, 'termux-open https://example.com/fcode-link');
        await sleep(2500);
        let started = startedActivity(/dat=https:\/\/example\.com/);
        check('termux-open opens a link in the browser', !!started, started || 'no activity was started');
        await backToApp();
        check('The shell is still there after the browser opened', (await runCommand(page, 'echo back-$((1+1))')).output.includes('back-2'));

        adb('push', apk, '/sdcard/Download/fcode-test.apk');
        adb('logcat', '-c');
        await runCommand(page, 'xdg-open ~/storage/downloads/fcode-test.apk');
        await sleep(3000);
        started = startedActivity(/typ=application\/vnd\.android\.package-archive/);
        check('xdg-open on an .apk starts Android\'s installer', !!started, started || (await terminalText(page)));
        await page.screenshot('06c-apk-opened');
        await backToApp();

        const missing = await runCommand(page, 'termux-open /etc/hostname-that-is-not-there');
        await page.do('activeTerminal.xterm.clear()');
        await type(page, "termux-open 'javascript:alert(1)'\r");
        const refused = await waitForTerminal(page, /cannot open links that start with javascript/, 8000);
        check('termux-open refuses what it should not open', /no such file/.test(missing.output) && refused.found, missing.output + '\n' + refused.text);
        await page.screenshot('06-terminal-after-tests');
    }

    /* --- a second terminal with Android's own shell --- */
    await page.do("setShellMode('android')");
    await page.do('createTerminal()');
    state = await waitForTerminal(page, /\$\s*$/, 30000);
    check('A second terminal opens with Android\'s shell', state.found && (await page.run('terminals.length')) === 2, state.text);
    await expectCommand(page, 'Android shell: echo', 'echo android-$((3*3))', /^android-9$/m);
    await expectCommand(page, 'Android shell: starts in ~/commands', 'echo "$PWD" | sed "s|$HOME|~|"', /^~\/commands$/m);
    await expectCommand(page, 'Android shell: sees the Code tab\'s files', 'ls ~/codes', /from-terminal\.txt|index\.html/);
    check('Android shell: the prompt folder is green', (await page.run(PROMPT_COLOUR)) === '~:2', await page.run(PROMPT_COLOUR));
    adb('logcat', '-c');
    await runCommand(page, 'termux-open https://example.com/from-android-shell');
    await sleep(2500);
    const startedFromAndroid = startedActivity(/dat=https:\/\/example\.com/);
    check('Android shell: termux-open opens a link', !!startedFromAndroid, startedFromAndroid || 'no activity was started');
    await backToApp();
    await page.screenshot('07-android-shell');
    await page.do("setShellMode('linux')");

    /* --- themes and languages --- */
    await page.do("switchMode('code'); setTheme('neon'); nativeOpenPath('index.html')");
    await sleep(600);
    await page.screenshot('08-theme-neon');
    await page.do("setTheme('mono'); switchMode('commands')");
    await sleep(600);
    await page.screenshot('09-theme-mono-terminal');
    await page.do("setTheme('classic'); switchMode('code'); setLanguage('ps'); openSettings()");
    await sleep(600);
    await page.screenshot('10-settings-pashto');
    check('Pashto is applied', (await page.run("document.getElementById('modeTabCommands').textContent.trim()")) === 'کمانډونه');
    await page.do("showSettingsView('about'); document.getElementById('guideStart').scrollIntoView()");
    await sleep(500);
    await page.screenshot('10b-guide-pashto');
    const guideTexts = JSON.parse(await page.run("JSON.stringify(Array.from(document.querySelectorAll('#guideStart .policy-text, #guideStart .policy-section-title')).map(e => e.textContent.trim()))"));
    check('The guide is there and in Pashto', guideTexts.length >= 18 && guideTexts.filter(text => /^[\x00-\x7F]+$/.test(text)).length <= 1,
        guideTexts.length + ' texts; not translated: ' + JSON.stringify(guideTexts.filter(text => /^[\x00-\x7F]+$/.test(text))));
    await page.do("showSettingsView('main')");
    await page.do("setLanguage('fa')");
    await sleep(300);
    await page.screenshot('11-settings-dari');
    await page.do("setLanguage('en'); closeSettings()");

    /* --- the app survives going to the background and coming back --- */
    await page.do("switchMode('commands'); activateTerminal(terminals[0])");
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
    await sleep(4000);
    const services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE);
    check('The keep-alive service runs while a shell is open', /KeepAliveService/.test(services) && /isForeground=true/.test(services), services.split('\n').slice(0, 12).join('\n'), false);
    adb('shell', 'am', 'start', '-n', ACTIVITY);
    await sleep(2000);
    if (linuxRuns) await expectCommand(page, 'The shell is still alive after the app was in the background', 'echo still-here', /^still-here$/m);
    await page.screenshot('12-back-from-background');
    page.close();

    /* --- crashes --- */
    const crashes = adb('logcat', '-d', '-s', 'AndroidRuntime:E');
    check('No crash was logged', !/FATAL EXCEPTION/.test(crashes), crashes.split('\n').slice(0, 40).join('\n'));
}

let failed = false;
try {
    await main();
} catch (error) {
    failed = true;
    log('\nTEST ABORTED: ' + (error && error.stack ? error.stack : error));
    try { writeFileSync(join(outDir, 'abort-screen.png'), adbBinary('exec-out', 'screencap', '-p')); } catch (e) { /* nothing to capture */ }
}

writeFileSync(join(outDir, 'logcat.txt'), adb('logcat', '-d', '-v', 'time', '*:W'));
writeFileSync(join(outDir, 'results.json'), JSON.stringify(results, null, 2));
const requiredFailures = results.filter(r => r.required && !r.ok);
const notes = results.filter(r => !r.required && !r.ok);
log('\n' + results.filter(r => r.ok).length + ' passed, ' + requiredFailures.length + ' failed, ' + notes.length + ' optional checks did not pass');
process.exit(failed || requiredFailures.length ? 1 : 0);
