'use strict';
// Fcode for Windows. The window shows the same page as the Android app (app/src/main/assets/www);
// this file gives it what the Android app gives it: real files, real terminals, and a way to
// open links and files. The page talks to it through window.FcodeNative (see preload.js).

const { app, BrowserWindow, protocol, ipcMain, shell, net, Menu } = require('electron');
const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const { pathToFileURL } = require('url');
const { createLinux } = require('./lib/linux');

const APP_URL = 'fcode://app/index.html';
const OUTPUT_FLUSH_MS = 12;
const LINUX_START_FAILURE_MS = 8000;
const SESSION_TOKEN = crypto.randomBytes(16).toString('hex');

protocol.registerSchemesAsPrivileged([{
    scheme: 'fcode',
    privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true }
}]);

// One window: starting Fcode again brings the open one to the front
const isFirstInstance = app.requestSingleInstanceLock();
if (!isFirstInstance) app.quit();

/* ---------- where things are ---------- */

// The page: next to the program once installed, or in the Android project while developing
const resources = app.isPackaged ? process.resourcesPath : path.join(__dirname, 'dev-resources');
const wwwDir = app.isPackaged ? path.join(process.resourcesPath, 'www') : path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'www');

// Documents\Faisal\codes and \commands, as on the phone
const faisalDir = path.join(app.getPath('documents'), 'Faisal');
const codesDir = path.join(faisalDir, 'codes');
const commandsDir = path.join(faisalDir, 'commands');

// Fcode's own data (the Linux system, the terminal's home folder): not roaming, not in Documents
const base = path.join(process.env.LOCALAPPDATA || app.getPath('userData'), 'Fcode');
const homeDir = path.join(base, 'home');
const binDir = path.join(base, 'bin');
const openDir = path.join(base, 'open-requests');

const linux = createLinux({ base, resources });

function prepareFolders() {
    for (const dir of [codesDir, commandsDir, homeDir, binDir, openDir]) fs.mkdirSync(dir, { recursive: true });
    // ~/codes and ~/commands in the terminal: links (junctions, which need no special rights)
    for (const [name, target] of [['codes', codesDir], ['commands', commandsDir]]) {
        const link = path.join(homeDir, name);
        let current = null;
        try { current = fs.readlinkSync(link); } catch (e) { /* missing, or a real folder */ }
        if (current) current = current.replace(/^\\\\\?\\/, '');   // a junction may read back as \\?\C:\...
        if (current && path.resolve(current).toLowerCase() === path.resolve(target).toLowerCase()) continue;
        try {
            if (fs.existsSync(link) || current !== null) fs.rmSync(link, { recursive: false, force: true });
            fs.symlinkSync(target, link, 'junction');
        } catch (e) {
            console.error('cannot link ' + link + ': ' + e.message);
        }
    }
    // termux-open for PowerShell and cmd
    fs.writeFileSync(path.join(binDir, 'termux-open.cmd'), '@start "" "%~1"\r\n');
    fs.writeFileSync(path.join(binDir, 'xdg-open.cmd'), '@start "" "%~1"\r\n');
}

/* ---------- the window ---------- */

let win = null;
const terminals = new Map();   // id -> TerminalHost

function createWindow() {
    win = new BrowserWindow({
        width: 1180,
        height: 820,
        minWidth: 360,
        minHeight: 480,
        title: 'Fcode',
        backgroundColor: '#0F1117',
        icon: path.join(__dirname, 'build', 'icon.ico'),
        autoHideMenuBar: true,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,          // the preload reads and writes the editor's files itself
            spellcheck: false
        }
    });
    Menu.setApplicationMenu(null);
    win.loadURL(APP_URL);

    // Links the page opens go to the real browser; the window itself never leaves the app
    win.webContents.setWindowOpenHandler(({ url }) => {
        if (/^(https?|mailto):/i.test(url)) shell.openExternal(url);
        return { action: 'deny' };
    });
    win.webContents.on('will-navigate', (event, url) => {
        if (!url.startsWith('fcode://')) {
            event.preventDefault();
            if (/^(https?|mailto):/i.test(url)) shell.openExternal(url);
        }
    });
    // F12 / Ctrl+Shift+I: developer tools, F5 / Ctrl+R: reload the page
    win.webContents.on('before-input-event', (event, input) => {
        if (input.type !== 'keyDown') return;
        if (input.key === 'F12' || (input.control && input.shift && input.key.toLowerCase() === 'i')) {
            win.webContents.toggleDevTools();
            event.preventDefault();
        }
    });
    win.on('closed', () => {
        win = null;
        for (const host of terminals.values()) host.close();
        terminals.clear();
    });
}

function runJs(script) {
    if (win && !win.isDestroyed()) win.webContents.executeJavaScript(script).catch(() => { /* page reloading */ });
}

/* ---------- serving the page and the editor's files ---------- */

function fileResponse(file) {
    return net.fetch(pathToFileURL(file).toString());
}

function insideOf(root, relativeUrlPath) {
    let decoded;
    try { decoded = decodeURIComponent(relativeUrlPath); } catch (e) { return null; }
    const parts = [];
    for (const part of decoded.split('/')) {
        if (!part || part === '.') continue;
        if (part === '..') { if (!parts.length) return null; parts.pop(); continue; }
        parts.push(part);
    }
    return path.join(root, ...parts);
}

function registerProtocol() {
    protocol.handle('fcode', request => {
        const url = new URL(request.url);
        let file;
        if (url.pathname.startsWith('/codes/')) {
            file = insideOf(codesDir, url.pathname.slice('/codes/'.length));
        } else {
            file = insideOf(wwwDir, url.pathname);
        }
        if (file && fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
        if (!file || !fs.existsSync(file)) return new Response('Not found', { status: 404 });
        return fileResponse(file);
    });
}

/* ---------- "Open in Browser": a small web server for this computer only ---------- */

let server = null;
let serverPort = 0;

function startServer() {
    if (server) return Promise.resolve(serverPort);
    return new Promise((resolve, reject) => {
        server = http.createServer((req, res) => {
            const target = insideOf(codesDir, new URL(req.url, 'http://x').pathname);
            let file = target;
            if (file && fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
            if (!file || !fs.existsSync(file)) { res.writeHead(404); res.end('Not found'); return; }
            res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' });
            fs.createReadStream(file).pipe(res);
        });
        server.on('error', reject);
        server.listen(0, '127.0.0.1', () => { serverPort = server.address().port; resolve(serverPort); });
    });
}

function contentType(file) {
    const types = {
        html: 'text/html; charset=utf-8', htm: 'text/html; charset=utf-8', css: 'text/css; charset=utf-8',
        js: 'text/javascript; charset=utf-8', mjs: 'text/javascript; charset=utf-8', json: 'application/json; charset=utf-8',
        txt: 'text/plain; charset=utf-8', md: 'text/plain; charset=utf-8', svg: 'image/svg+xml', png: 'image/png',
        jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', ico: 'image/x-icon',
        woff: 'font/woff', woff2: 'font/woff2', ttf: 'font/ttf', mp3: 'audio/mpeg', mp4: 'video/mp4',
        wasm: 'application/wasm', pdf: 'application/pdf'
    };
    return types[path.extname(file).slice(1).toLowerCase()] || 'application/octet-stream';
}

/* ---------- opening links and files from a terminal ---------- */

// The automatic test asks for the targets to be written down instead of opened
const testOpenLog = process.env.FCODE_TEST_OPEN_LOG || '';

async function openTarget(terminalId, target) {
    target = String(target || '').trim();
    if (!target) return;
    if (testOpenLog) {
        fs.appendFileSync(testOpenLog, target + '\n');
        return;
    }
    let problem = null;
    if (/^[a-z][a-z0-9+.-]*:/i.test(target) && !/^[a-z]:[\\/]/i.test(target)) {
        if (/^(https?|mailto|tel):/i.test(target)) await shell.openExternal(target);
        else problem = 'cannot open links that start with ' + target.split(':')[0] + ':';
    } else {
        problem = await shell.openPath(target) || null;   // an empty string means it worked
    }
    const host = terminals.get(terminalId);
    if (problem && host) host.show('fcode: ' + problem + '\r\n');
}

function watchOpenRequests() {
    const handle = name => {
        if (!name || !name.endsWith('.req')) return;
        const file = path.join(openDir, name);
        let text;
        try { text = fs.readFileSync(file, 'utf8'); fs.unlinkSync(file); } catch (e) { return; }
        const [id, ...rest] = text.split(/\r?\n/);
        openTarget(Number(id) || 0, rest.join('\n').trim());
    };
    try {
        fs.watch(openDir, (event, name) => handle(name));
    } catch (e) {
        console.error('cannot watch ' + openDir + ': ' + e.message);
    }
    // requests left from before the app started are dropped
    for (const name of fs.readdirSync(openDir)) {
        try { fs.unlinkSync(path.join(openDir, name)); } catch (e) { /* in use */ }
    }
}

/* ---------- terminals ---------- */

let pty = null;
function loadPty() {
    if (!pty) pty = require('@lydell/node-pty');
    return pty;
}

const SHELLS = [
    ['linux', 'Linux (bash, git, pkg install)'],
    ['powershell', 'PowerShell'],
    ['cmd', 'Command Prompt (cmd)']
];

function baseEnvironment(id) {
    const env = Object.assign({}, process.env);
    env.PATH = binDir + ';' + (env.PATH || env.Path || '');
    delete env.Path;
    env.FCODE_TOKEN = SESSION_TOKEN;
    env.FCODE_OPEN_DIR = openDir;
    env.FCODE_TERMINAL_ID = String(id);
    return env;
}

function windowsShell(mode, id) {
    const env = baseEnvironment(id);
    const system = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32');
    if (mode === 'cmd') {
        return {
            file: path.join(system, 'cmd.exe'),
            args: ['/K', 'chcp 65001>nul & prompt $E[32m$P$E[0m$G '],
            cwd: commandsDir,
            env
        };
    }
    // PowerShell: UTF-8, and a short green prompt that names the Faisal folder
    const faisal = faisalDir.replace(/'/g, "''");
    const startup = [
        '[Console]::OutputEncoding = [System.Text.Encoding]::UTF8',
        '$OutputEncoding = [System.Text.Encoding]::UTF8',
        `function global:prompt { $p = $PWD.Path; $f = '${faisal}'; if ($p.StartsWith($f, [StringComparison]::OrdinalIgnoreCase)) { $p = 'Faisal' + $p.Substring($f.Length) }; "$([char]27)[32m$p$([char]27)[0m > " }`
    ].join('; ');
    return {
        file: path.join(system, 'WindowsPowerShell', 'v1.0', 'powershell.exe'),
        // Encoded, so no quote in it can be misread on the way to PowerShell
        args: ['-NoLogo', '-NoExit', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', Buffer.from(startup, 'utf16le').toString('base64')],
        cwd: commandsDir,
        env
    };
}

class TerminalHost {
    constructor(id) {
        this.id = id;
        this.columns = 80;
        this.rows = 24;
        this.process = null;
        this.pending = '';
        this.flushTimer = null;
        this.closed = false;
        this.mode = 'linux';
        this.startedAt = 0;
        this.notice = null;
    }

    isRunning() {
        return !!this.process && !this.closed;
    }

    async start(mode) {
        let useMode = SHELLS.some(s => s[0] === mode) ? mode : 'linux';
        if (useMode === 'linux') {
            if (!linux.isSupported()) {
                this.notice = 'fcode: the Linux system is not part of this installation. Using PowerShell.\r\n\r\n';
                useMode = 'powershell';
            } else if (!linux.isInstalled()) {
                this.show('Setting up Linux (first time only, a minute or two)...\r\n');
                try {
                    await linux.install({ home: homeDir, env: baseEnvironment(this.id) });
                } catch (e) {
                    console.error('Linux setup failed: ' + (e.stack || e.message));
                    this.notice = 'fcode: could not set up Linux: ' + e.message + '\r\nUsing PowerShell.\r\n\r\n';
                    useMode = 'powershell';
                }
            }
        }
        this.launch(useMode);
    }

    // Windows clears the screen when a new console program starts, so a message about why the
    // shell changed is shown once the new shell has drawn its first output
    showNoticeSoon() {
        if (!this.notice) return;
        const text = this.notice;
        this.notice = null;
        setTimeout(() => this.show('\r\n' + text), 400);
    }

    launch(mode) {
        if (this.closed) return;
        this.mode = mode;
        if (this.notice) console.error('terminal ' + this.id + ': ' + this.notice.trim());
        let spec;
        try {
            spec = mode === 'linux'
                ? Object.assign({ cwd: path.join(homeDir, 'commands') }, linux.command({ home: homeDir, env: baseEnvironment(this.id) }))
                : windowsShell(mode, this.id);
            this.startedAt = Date.now();
            const child = loadPty().spawn(spec.file, spec.args, {
                name: 'xterm-256color',
                cols: Math.max(10, this.columns),
                rows: Math.max(4, this.rows),
                cwd: spec.cwd,
                env: spec.env,
                useConpty: true
            });
            this.process = child;
            let first = true;
            child.onData(data => {
                this.queue(data);
                if (first) { first = false; this.showNoticeSoon(); }
            });
            child.onExit(({ exitCode }) => this.exited(child, exitCode));
        } catch (e) {
            this.process = null;
            if (mode === 'linux') {
                this.notice = 'fcode: Linux could not start: ' + e.message + '\r\nUsing PowerShell.\r\n\r\n';
                this.launch('powershell');
            } else {
                this.show('\r\nfcode: could not start the shell: ' + e.message + '\r\n');
                this.notifyExit(-1);
            }
        }
    }

    exited(child, code) {
        if (child !== this.process || this.closed) return;
        this.process = null;
        if (this.mode === 'linux' && code !== 0 && Date.now() - this.startedAt < LINUX_START_FAILURE_MS) {
            this.flush();
            console.error('terminal ' + this.id + ': Linux ended with ' + code + ' after ' + (Date.now() - this.startedAt) + ' ms; it printed:\n' + (this.recent || '(nothing)'));
            this.notice = 'fcode: Linux could not start (code ' + code + '). Using PowerShell.\r\nYou can pick the shell in Settings.\r\n\r\n';
            this.launch('powershell');
            return;
        }
        this.notifyExit(code);
    }

    notifyExit(code) {
        this.flush();
        runJs('window.FcodeTerm && FcodeTerm.onExit(' + this.id + ',' + Number(code) + ')');
    }

    write(data) {
        if (this.process) this.process.write(data);
    }

    resize(columns, rows) {
        this.columns = columns;
        this.rows = rows;
        if (this.process) {
            try { this.process.resize(Math.max(10, columns), Math.max(4, rows)); } catch (e) { /* closing */ }
        }
    }

    close() {
        this.closed = true;
        if (this.process) {
            try { this.process.kill(); } catch (e) { /* already gone */ }
            this.process = null;
        }
    }

    show(text) {
        this.queue(text);
    }

    // Output reaches the page in batches, Base64-encoded UTF-8 as on Android
    queue(text) {
        this.pending += text;
        this.recent = ((this.recent || '') + text).slice(-3000);   // for the log when a shell fails
        if (!this.flushTimer) this.flushTimer = setTimeout(() => this.flush(), OUTPUT_FLUSH_MS);
    }

    flush() {
        if (this.flushTimer) { clearTimeout(this.flushTimer); this.flushTimer = null; }
        if (!this.pending) return;
        const data = Buffer.from(this.pending, 'utf8').toString('base64');
        this.pending = '';
        runJs('window.FcodeTerm && FcodeTerm.onData(' + this.id + ',"' + data + '")');
    }
}

/* ---------- what the page asks for ---------- */

function appInfo() {
    return {
        versionName: app.getVersion(),
        platform: 'windows',
        home: homeDir,
        workspacePath: faisalDir,
        linux: linux.isSupported(),
        storageAccess: true,
        sharedWorkspace: true,
        workspaceName: 'Faisal'
    };
}

ipcMain.on('fcode:setup', event => {
    event.returnValue = { info: appInfo(), codes: codesDir, shells: SHELLS };
});

ipcMain.on('term:start', (event, id, columns, rows, mode) => {
    let host = terminals.get(id);
    if (host && host.isRunning()) { host.resize(columns, rows); return; }
    host = new TerminalHost(id);
    terminals.set(id, host);
    host.resize(columns, rows);
    host.start(mode);
});
ipcMain.on('term:write', (event, id, data) => { const host = terminals.get(id); if (host) host.write(data); });
ipcMain.on('term:resize', (event, id, columns, rows) => { const host = terminals.get(id); if (host) host.resize(columns, rows); });
ipcMain.on('term:close', (event, id) => { const host = terminals.get(id); if (host) host.close(); terminals.delete(id); });
ipcMain.on('term:storage', (event, id) => {
    const host = terminals.get(id);
    if (host) host.show('\r\nfcode: on Windows your files are already in Documents\\Faisal (~/codes and ~/commands).\r\n');
});
ipcMain.on('term:open', (event, id, token, target64) => {
    if (token !== SESSION_TOKEN) return;
    let target;
    try { target = Buffer.from(String(target64), 'base64').toString('utf8'); } catch (e) { return; }
    openTarget(id, target);
});
ipcMain.on('open-in-browser', async (event, relative) => {
    try {
        const port = await startServer();
        const url = 'http://127.0.0.1:' + port + '/' + String(relative || '').split('/').filter(Boolean).map(encodeURIComponent).join('/');
        if (testOpenLog) fs.appendFileSync(testOpenLog, url + '\n');
        else shell.openExternal(url);
        event.returnValue = true;
    } catch (e) {
        event.returnValue = false;
    }
});

/* ---------- start ---------- */

app.on('second-instance', () => {
    if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
});

app.whenReady().then(() => {
    if (!isFirstInstance) return;
    prepareFolders();
    linux.removeArchive();
    registerProtocol();
    watchOpenRequests();
    createWindow();
});

app.on('window-all-closed', () => {
    app.quit();
});
