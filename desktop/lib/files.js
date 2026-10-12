'use strict';
// File access for the editor, the same as ProjectFiles.java in the Android app. Every path is
// relative to the codes folder, uses "/" and may not climb above it. Each function returns a
// JSON string: {"ok":true, ...} or {"ok":false, "error":"..."}.
//
// Runs in the preload script, so the page's calls get their answer straight away, as on Android.

const fs = require('fs');
const path = require('path');

const MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024;

function ok(extra) {
    return JSON.stringify(Object.assign({ ok: true }, extra || {}));
}

function error(message) {
    return JSON.stringify({ ok: false, error: String(message || 'unknown error') });
}

function describe(e) {
    switch (e && e.code) {
        case 'ENOENT': return 'not found';
        case 'EACCES':
        case 'EPERM': return 'not allowed';
        case 'ENOTEMPTY': return 'folder is not empty';
        case 'EEXIST': return 'already exists';
        case 'EISDIR': return 'is a folder';
        default: return e && e.message ? e.message : String(e);
    }
}

function createProjectFiles(root) {
    // "src/../app.js" -> <root>/app.js, worked out from the text alone
    function resolve(relative) {
        const parts = [];
        for (const part of String(relative || '').split(/[\\/]+/)) {
            if (!part || part === '.') continue;
            if (part === '..') {
                if (!parts.length) throw new Error('outside the home folder');
                parts.pop();
            } else {
                parts.push(part);
            }
        }
        return path.join(root, ...parts);
    }

    function isDir(file) {
        try { return fs.statSync(file).isDirectory(); } catch (e) { return false; }
    }

    function writeBytes(relative, bytes) {
        try {
            const file = resolve(relative);
            if (isDir(file)) return error('is a folder');
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, bytes);
            return ok();
        } catch (e) {
            return error(describe(e));
        }
    }

    return {
        list(dir) {
            try {
                const folder = resolve(dir);
                let names;
                try {
                    names = fs.readdirSync(folder);
                } catch (e) {
                    return error(fs.existsSync(folder) ? 'cannot read folder' : 'not found');
                }
                const entries = [];
                for (const name of names) {
                    let stat = null;
                    try { stat = fs.statSync(path.join(folder, name)); } catch (e) { /* a broken link: shown as a file */ }
                    entries.push({ name, dir: !!(stat && stat.isDirectory()), size: stat ? stat.size : 0 });
                }
                // folders first, then by name
                entries.sort((a, b) => (a.dir !== b.dir ? (a.dir ? -1 : 1) : a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })));
                return ok({ entries });
            } catch (e) {
                return error(describe(e));
            }
        },

        stat(relative) {
            try {
                const file = resolve(relative);
                let stat = null;
                try { stat = fs.statSync(file); } catch (e) { /* missing */ }
                return ok({
                    exists: !!stat,
                    dir: !!(stat && stat.isDirectory()),
                    size: stat ? stat.size : 0,
                    modified: stat ? Math.round(stat.mtimeMs) : 0
                });
            } catch (e) {
                return error(describe(e));
            }
        },

        read(relative) {
            try {
                const file = resolve(relative);
                let stat;
                try { stat = fs.statSync(file); } catch (e) { return error('not found'); }
                if (stat.isDirectory()) return error('is a folder');
                if (stat.size > MAX_TEXT_FILE_BYTES) return error('too large');
                const bytes = fs.readFileSync(file);
                if (bytes.includes(0)) return error('not a text file');
                return ok({ content: bytes.toString('utf8') });
            } catch (e) {
                return error(describe(e));
            }
        },

        write(relative, content) {
            return writeBytes(relative, Buffer.from(content == null ? '' : String(content), 'utf8'));
        },

        writeBase64(relative, base64) {
            return writeBytes(relative, Buffer.from(base64 == null ? '' : String(base64), 'base64'));
        },

        // a file, or a folder that is already empty
        delete(relative) {
            try {
                const file = resolve(relative);
                if (path.resolve(file) === path.resolve(root)) return error('cannot delete the home folder');
                let stat;
                try { stat = fs.lstatSync(file); } catch (e) { return error('not found'); }
                if (stat.isDirectory()) fs.rmdirSync(file);
                else fs.unlinkSync(file);
                return ok();
            } catch (e) {
                return error(describe(e));
            }
        },

        rename(from, to) {
            try {
                const source = resolve(from);
                const target = resolve(to);
                if (!fs.existsSync(source)) return error('not found');
                if (fs.existsSync(target)) return error('already exists');
                fs.mkdirSync(path.dirname(target), { recursive: true });
                fs.renameSync(source, target);
                return ok();
            } catch (e) {
                return error(describe(e));
            }
        },

        mkdir(relative) {
            try {
                const folder = resolve(relative);
                if (isDir(folder)) return ok();
                if (fs.existsSync(folder)) return error('already exists');
                fs.mkdirSync(folder, { recursive: true });
                return ok();
            } catch (e) {
                return error(describe(e));
            }
        },

        resolve
    };
}

module.exports = { createProjectFiles };
