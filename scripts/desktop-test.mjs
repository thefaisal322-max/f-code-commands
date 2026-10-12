// Runs the installed Windows app and checks that it works: the Faisal folder in Documents, the
// editor's files, preview, Python, and the three terminals (Linux, PowerShell, Command Prompt).
//
// The app is started with Chrome's remote debugging switched on, so this script can call the
// page's own functions and read the terminal, as the Android emulator test does.
//
// Usage: node scripts/desktop-test.mjs <Fcode.exe> <results folder>
// Needs Windows and Node.js 22 or newer.

import { spawn, execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, appendFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import os from 'node:os';

const [exe, outDir = 'test-results'] = process.argv.slice(2);
if (!exe) {
    console.error('usage: node scripts/desktop-test.mjs <Fcode.exe> <results folder>');
    process.exit(2);
}

const DEVTOOLS_PORT = 9334;
const documents = join(os.homedir(), 'Documents');
const faisal = join(documents, 'Faisal');
const openLog = join(os.tmpdir(), 'fcode-open-log.txt');

mkdirSync(outDir, { recursive: true });
const logFile = join(outDir, 'log.txt');
writeFileSync(logFile, '');
const results = [];

function log(...parts) {
    const line = parts.join(' ');
    console.log(line);
    appendFileSync(logFile, line + '\n');
}

function check(name, ok, detail = '', required = true) {
    results.push({ name, ok: !!ok, required, detail: String(detail).slice(0, 2000) });
    log((ok ? 'PASS' : (required ? 'FAIL' : 'note')) + '  ' + name + (detail ? '\n      ' + String(detail).trim().split('\n').join('\n      ').slice(0, 2000) : ''));
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/* ---------- Chrome DevTools Protocol ---------- */

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
            setTimeout(() => { if (this.waiting.delete(id)) reject(new Error('no answer to ' + method)); }, 60000);
        });
    }

    async run(expression) {
        const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
        if (result.exceptionDetails) {
            const what = result.exceptionDetails.exception && result.exceptionDetails.exception.description;
            throw new Error('page error: ' + (what || result.exceptionDetails.text));
        }
        return result.result.value;
    }

    async do(expression) {
        await this.run('(() => { ' + expression + '; return true; })()');
    }

    async screenshot(name) {
        try {
            const shot = await this.send('Page.captureScreenshot', { format: 'png' });
            writeFileSync(join(outDir, name + '.png'), Buffer.from(shot.data, 'base64'));
        } catch (e) {
            log('  (screenshot ' + name + ' failed: ' + e.message + ')');
        }
    }
}

async function connect() {
    for (let attempt = 0; attempt < 90; attempt++) {
        try {
            const list = await (await fetch('http://127.0.0.1:' + DEVTOOLS_PORT + '/json/list')).json();
            const target = list.find(t => t.type === 'page' && /^fcode:\/\/app\//.test(t.url));
            if (target) {
                const socket = new WebSocket(target.webSocketDebuggerUrl);
                await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
                const page = new Page(socket);
                for (let i = 0; i < 60; i++) {
                    if (await page.run("document.readyState === 'complete' && typeof switchMode === 'function'").catch(() => false)) return page;
                    await sleep(500);
                }
                return page;
            }
        } catch (e) { /* not up yet */ }
        await sleep(1000);
    }
    throw new Error('the app did not open its window');
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
        if (line.isWrapped && lines.length) lines[lines.length - 1] += text;
        else lines.push(text);
    }
    while (lines.length && !lines[lines.length - 1]) lines.pop();
    return lines.join('\\n');
})()`;

const PROMPT_COLOUR = `(() => {
    const b = activeTerminal.xterm.buffer.active;
    const cell = b.getLine(b.baseY + b.cursorY).getCell(0);
    return cell.getChars() + ':' + (cell.isFgPalette() ? cell.getFgColor() : 'not-palette');
})()`;

const terminalText = page => page.run(TERMINAL_TEXT);
const type = (page, text) => page.do('FcodeNative.write(activeTerminal.id, ' + JSON.stringify(text) + ')');

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

let counter = 0;

// bash: runs a command and returns what it printed and its exit code
async function runCommand(page, command, timeoutMs = 60000) {
    const marker = 'FCODE_END_' + (++counter);
    await page.do('activeTerminal.xterm.clear()');
    await type(page, command + '; echo ' + marker + ':$?\r');
    const { found, text } = await waitForTerminal(page, new RegExp(marker + ':\\d+'), timeoutMs);
    if (!found) {
        await type(page, '\x03');
        await sleep(1000);
        return { output: text, code: -1, timedOut: true };
    }
    const at = text.search(new RegExp(marker + ':\\d+'));
    const code = Number(text.slice(at + marker.length + 1).match(/^\d+/)[0]);
    const output = text.slice(0, at).split('\n').filter(line => !line.includes(marker)).join('\n').trim();
    return { output, code, timedOut: false };
}

async function expectCommand(page, name, command, pattern, { timeoutMs = 60000, required = true } = {}) {
    const result = await runCommand(page, command, timeoutMs);
    const ok = !result.timedOut && pattern.test(result.output);
    check(name, ok, '$ ' + command + '\n' + result.output + (result.timedOut ? '\n(timed out)' : '\n(exit code ' + result.code + ')'), required);
    return ok;
}

// PowerShell and cmd: types a line and waits for a pattern
async function expectTyped(page, name, line, pattern, timeoutMs = 30000) {
    await page.do('activeTerminal.xterm.clear()');
    await type(page, line + '\r');
    const { found, text } = await waitForTerminal(page, pattern, timeoutMs);
    check(name, found, '> ' + line + '\n' + text.split('\n').slice(-12).join('\n'));
    return found;
}

async function newTerminal(page, mode) {
    await page.do("setShellMode('" + mode + "')");
    await page.do('activateTerminal(createTerminal())');
    await sleep(500);
}

/* ---------- the test ---------- */

async function main() {
    log('Windows: ' + os.release() + ', user folder ' + os.homedir());
    check('Fcode.exe is installed', existsSync(exe), exe);

    rmSync(openLog, { force: true });
    const env = Object.assign({}, process.env, { FCODE_TEST_OPEN_LOG: openLog });
    const child = spawn(exe, ['--remote-debugging-port=' + DEVTOOLS_PORT], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', d => appendFileSync(join(outDir, 'app-output.txt'), d));
    child.stderr.on('data', d => appendFileSync(join(outDir, 'app-output.txt'), d));

    const page = await connect();
    await sleep(2000);
    await page.screenshot('01-start');

    const info = JSON.parse(await page.run('FcodeNative.info()'));
    log('App info: ' + JSON.stringify(info));
    check('The app says it runs on Windows', info.platform === 'windows', JSON.stringify(info));
    check('The page is in Windows mode (no phone key rows)', await page.run("document.body.classList.contains('desktop') && getComputedStyle(document.querySelector('.term-keys')).display === 'none'"));
    check('Documents\\Faisal\\codes and \\commands exist', existsSync(join(faisal, 'codes')) && existsSync(join(faisal, 'commands')), faisal);
    check('The storage screen is not shown', !(await page.run("document.getElementById('storagePanel').classList.contains('active')")));
    check('The starter file is open', (await page.run("document.getElementById('codeEditor').value.length")) > 50, await page.run('activeFileName'));

    // files
    await page.run("nativeFs('fsWrite', 'site/page.html', '<h1>made in the editor</h1>').ok");
    const made = existsSync(join(faisal, 'codes', 'site', 'page.html')) ? readFileSync(join(faisal, 'codes', 'site', 'page.html'), 'utf8') : '(missing)';
    check('A file made in the Code tab is in Documents\\Faisal\\codes', /made in the editor/.test(made), made);
    writeFileSync(join(faisal, 'codes', 'from-windows.txt'), 'made by Windows\n');
    const seen = JSON.parse(await page.run("JSON.stringify(nativeFs('fsRead', 'from-windows.txt'))"));
    check('The editor reads a file made outside the app', seen.ok && /made by Windows/.test(seen.content), JSON.stringify(seen));

    // preview with a linked stylesheet
    await page.run("nativeFs('fsWrite', 'site/style.css', 'h1 { color: rgb(255, 0, 0); }').ok");
    await page.run("nativeFs('fsWrite', 'site/index.html', '<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1 id=\"t\">Hello</h1></body></html>').ok");
    await page.do("nativeOpenPath('site/index.html')");
    await page.do('runLivePreview()');
    await sleep(2500);
    const colour = await page.run("(() => { const d = document.getElementById('previewFrame').contentDocument; const h = d && d.getElementById('t'); return h ? getComputedStyle(h).color : 'no preview'; })()");
    check('The preview loads the stylesheet the page links to', colour === 'rgb(255, 0, 0)', colour);
    await page.screenshot('02-preview');
    await page.do('closeLivePreview()');

    // Open in Browser goes through the small local web server
    await page.run("FcodeNative.openInBrowser('site/index.html')");
    await sleep(500);
    const opened = existsSync(openLog) ? readFileSync(openLog, 'utf8') : '';
    const url = (opened.match(/http:\/\/127\.0\.0\.1:\d+\/site\/index\.html/) || [])[0];
    let served = '';
    if (url) served = await (await fetch(url)).text().catch(e => e.message);
    check('Open in Browser serves the page', /id="t"/.test(served), url + '\n' + served.slice(0, 200));

    // Python
    await page.run("nativeFs('fsWrite', 'main.py', 'print(\"python says\", 6 * 7)').ok");
    await page.do("nativeOpenPath('main.py')");
    await page.do('runLivePreview()');
    let pythonLog = '';
    for (let i = 0; i < 180; i++) {
        pythonLog = await page.run("document.getElementById('consoleLogs').innerText");
        if (/python says 42|Error|error/.test(pythonLog)) break;
        await sleep(500);
    }
    check('Run executes a Python file', /python says 42/.test(pythonLog), pythonLog);
    await page.do('closeConsole()');

    // the guide shows the Windows texts
    const guide = await page.run("[...document.querySelectorAll('[data-only]')].filter(e => getComputedStyle(e).display !== 'none').map(e => e.dataset.only).join(',')");
    check('The guide shows the Windows texts only', /desktop/.test(guide) && !/phone/.test(guide), guide);

    /* --- PowerShell --- */
    await page.do("switchMode('commands')");
    await newTerminal(page, 'powershell');
    let state = await waitForTerminal(page, /> $/m, 60000);
    await page.screenshot('03-powershell');
    check('PowerShell starts', state.found, state.text);
    await expectTyped(page, 'PowerShell runs commands', 'echo "ps-$(6*7)"', /^ps-42\s*$/m);
    await expectTyped(page, 'PowerShell starts in Documents\\Faisal\\commands', '(Get-Location).Path', /Faisal\\commands\s*$/m);
    await expectTyped(page, 'PowerShell sees the Code tab\'s files', 'Get-Content ..\\codes\\site\\page.html', /made in the editor/);
    await expectTyped(page, 'PowerShell prints Pashto', 'echo "سلام-نړۍ"', /^سلام-نړۍ\s*$/m);

    /* --- Command Prompt --- */
    await newTerminal(page, 'cmd');
    state = await waitForTerminal(page, />\s*$/m, 60000);
    await page.screenshot('04-cmd');
    check('Command Prompt starts', state.found, state.text);
    await expectTyped(page, 'Command Prompt runs commands', 'echo cmd-%OS%', /^cmd-Windows_NT\s*$/m);
    await expectTyped(page, 'Command Prompt starts in Documents\\Faisal\\commands', 'cd', /Faisal\\commands\s*$/m);

    /* --- Linux --- */
    await newTerminal(page, 'linux');
    state = await waitForTerminal(page, /\[Y\/n\]|\$ $|Using PowerShell/m, 900000);
    await page.screenshot('05-linux-first-start');
    log('--- Linux terminal after first start ---\n' + state.text + '\n---');
    const linuxStarted = /\[Y\/n\]|\$ $/m.test(state.text) && !/Using PowerShell/.test(state.text);
    check('The Linux terminal starts', linuxStarted, state.text);
    if (linuxStarted && /\[Y\/n\]/.test(state.text)) {
        await type(page, 'y\r');
        state = await waitForTerminal(page, /Done\. More tools|That did not work/, 900000);
        log('--- after installing the tools ---\n' + state.text.split('\n').slice(-30).join('\n') + '\n---');
        check('git, curl, unzip and the other tools install', /Done\. More tools/.test(state.text), state.text.split('\n').slice(-20).join('\n'));
        await waitForTerminal(page, /\$ $/m, 30000);
    }
    await page.screenshot('06-linux-ready');

    if (linuxStarted) {
        await expectCommand(page, 'Arithmetic and echo', 'echo hello-$((6*7))', /^hello-42$/m);
        const drawn = await page.run("activeTerminal.pane.querySelector('.xterm-rows').innerText");
        check('The terminal draws its text on the screen', /hello-42/.test(drawn), drawn.trim().slice(0, 300));
        await expectCommand(page, 'It is bash', 'echo $BASH_VERSION', /^\d+\.\d+/m);
        await expectCommand(page, 'The terminal starts in the commands folder', 'echo "$PWD" | sed "s|$HOME|~|"', /~\/commands$/m);
        check('The prompt folder is green', /:2$/.test(await page.run(PROMPT_COLOUR)), await page.run(PROMPT_COLOUR));
        await expectCommand(page, 'git is there', 'git --version', /git version \d/);
        await expectCommand(page, 'curl reaches the internet', 'curl -sI https://example.com | head -n 1', /HTTP\/\S+ 200/);
        await expectCommand(page, 'The Code tab\'s files are in ~/codes', 'cat ~/codes/site/page.html', /made in the editor/);
        await expectCommand(page, 'A file written by the terminal reaches the editor', 'echo from-terminal > ~/codes/from-terminal.txt; cat ~/codes/from-terminal.txt', /^from-terminal$/m);
        const fromTerminal = JSON.parse(await page.run("JSON.stringify(nativeFs('fsRead', 'from-terminal.txt'))"));
        check('The editor reads the terminal\'s file', fromTerminal.ok && /from-terminal/.test(fromTerminal.content), JSON.stringify(fromTerminal));
        await expectCommand(page, './script.sh runs in the commands folder', 'cd ~/commands; printf \'#!/bin/bash\\necho script-ran-$1\\n\' > s.sh; chmod +x s.sh; ./s.sh ok', /^script-ran-ok$/m);
        await expectCommand(page, 'git clone', 'cd ~/commands; rm -rf hw; git clone -q --depth 1 https://github.com/octocat/Hello-World.git hw 2>&1; ls hw', /README/, { timeoutMs: 180000 });
        await expectCommand(page, 'git commit', 'cd ~/commands/hw; echo x > x.txt; git add x.txt; git -c user.name=T -c user.email=t@example.com commit -qm test 2>&1; git log --oneline | head -n 1; cd ..', /test/);
        await expectCommand(page, 'rm -rf removes a folder', 'cd ~/commands; rm -rf hw; [ -e hw ] || echo gone', /^gone$/m);
        await expectCommand(page, 'zip and unzip', 'cd ~/commands; rm -rf z z.zip; mkdir z; echo inside > z/a.txt; zip -qr z.zip z; rm -rf z; unzip -oq z.zip; cat z/a.txt', /^inside$/m);
        await expectCommand(page, 'pkg install python', 'pkg install -y python >/dev/null 2>&1; python -c "print(6*7)"', /^42$/m, { timeoutMs: 600000 });
        await expectCommand(page, 'python3 works too', 'python3 -c "import sys; print(sys.platform)"', /^win32$/m);
        await expectCommand(page, 'pkg install nodejs', 'pkg install -y nodejs >/dev/null 2>&1; node -e "console.log(6*7)"', /^42$/m, { timeoutMs: 600000, required: false });
        await expectCommand(page, 'An unknown command names pkg', 'htop-nothing', /pkg install htop-nothing/);
        await expectCommand(page, 'Pashto text', 'echo "سلام نړۍ"', /سلام نړۍ/);
        rmSync(openLog, { force: true });
        await runCommand(page, 'termux-open https://example.com/from-linux; cd ~/codes; termux-open site/page.html; sleep 2');
        await sleep(1500);
        const openedFromLinux = existsSync(openLog) ? readFileSync(openLog, 'utf8') : '';
        check('termux-open opens a link', /https:\/\/example\.com\/from-linux/.test(openedFromLinux), openedFromLinux || '(nothing was opened)');
        check('termux-open opens a file (as a Windows path)', /[A-Za-z]:\\.*Faisal\\codes\\site\\page\.html/i.test(openedFromLinux), openedFromLinux || '(nothing was opened)');
        await page.screenshot('07-linux-done');
    }

    try { child.kill(); } catch (e) { /* gone */ }
    try { execFileSync('taskkill', ['/F', '/IM', 'Fcode.exe', '/T'], { stdio: 'ignore' }); } catch (e) { /* gone */ }
}

let failed = false;
try {
    await main();
} catch (e) {
    failed = true;
    log('The test stopped: ' + (e.stack || e.message));
    try { execFileSync('taskkill', ['/F', '/IM', 'Fcode.exe', '/T'], { stdio: 'ignore' }); } catch (e2) { /* gone */ }
}
const requiredFailures = results.filter(r => r.required && !r.ok);
const notes = results.filter(r => !r.required && !r.ok);
log('\n' + results.filter(r => r.ok).length + ' passed, ' + requiredFailures.length + ' failed, ' + notes.length + ' optional checks did not pass');
process.exit(failed || requiredFailures.length ? 1 : 0);
