'use strict';
// window.FcodeNative for the page: the same functions the Android app offers, so the page does
// not need to know which one it runs in. File calls answer at once (as on Android); terminal
// output comes back through window.FcodeTerm, called from main.js.

const { contextBridge, ipcRenderer } = require('electron');
const { createProjectFiles } = require('./lib/files');

const setup = ipcRenderer.sendSync('fcode:setup');
const files = createProjectFiles(setup.codes);

contextBridge.exposeInMainWorld('FcodeNative', {
    info: () => JSON.stringify(setup.info),
    shells: () => JSON.stringify(setup.shells),

    // terminals
    startShell: (id, columns, rows, mode) => ipcRenderer.send('term:start', id, columns, rows, String(mode || 'linux')),
    write: (id, data) => ipcRenderer.send('term:write', id, String(data)),
    resize: (id, columns, rows) => ipcRenderer.send('term:resize', id, columns, rows),
    closeShell: id => ipcRenderer.send('term:close', id),
    setupStorage: id => ipcRenderer.send('term:storage', id),
    openFromTerminal: (id, token, target64) => ipcRenderer.send('term:open', id, String(token), String(target64)),

    // nothing to ask on a computer: the Faisal folder is in Documents from the start
    requestStorage: () => {},
    setSystemBarColor: () => {},

    openInBrowser: relative => ipcRenderer.sendSync('open-in-browser', String(relative || '')),

    // the editor's files, relative to Documents\Faisal\codes
    fsList: dir => files.list(dir),
    fsStat: relative => files.stat(relative),
    fsRead: relative => files.read(relative),
    fsWrite: (relative, content) => files.write(relative, content),
    fsWriteBase64: (relative, base64) => files.writeBase64(relative, base64),
    fsDelete: relative => files.delete(relative),
    fsRename: (from, to) => files.rename(from, to),
    fsMkdir: relative => files.mkdir(relative)
});
