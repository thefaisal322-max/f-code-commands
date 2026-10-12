package com.fainet.fcode;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Open with Fcode": works out which file another app (a file manager, mostly) handed over.
 *
 * <ul>
 *   <li>A file in Faisal/codes opens as itself, like any file of the Code tab.</li>
 *   <li>A file elsewhere on the phone's storage opens in place by its full path, so saving
 *       changes the original (needs the storage access the app already asks for).</li>
 *   <li>Anything else (a file from an e-mail, a cloud drive, or with no storage access) is
 *       copied into the codes folder, and the user is told so.</li>
 * </ul>
 */
final class OpenedFiles {

    /** The result: a path for the editor, and a message to show (or null). */
    static final class Result {
        final String path;
        final String note;

        Result(String path, String note) {
            this.path = path;
            this.note = note;
        }
    }

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String PREFS = "opened_files";
    private static final String KEY_OUTSIDE = "outside";
    private static final int MAX_REMEMBERED = 30;

    private final Context context;
    private final ProjectFiles projectFiles;
    private final File codes;

    OpenedFiles(Context context, ProjectFiles projectFiles, File codes) {
        this.context = context;
        this.projectFiles = projectFiles;
        this.codes = codes;
        // Files opened earlier stay open as tabs after a restart
        for (String path : remembered()) projectFiles.allowOutside(path);
    }

    static boolean isOpenIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        String action = intent.getAction();
        return Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action);
    }

    /** Runs on a background thread: may read the file. Returns null when nothing could be opened. */
    Result handle(Intent intent) {
        Uri uri = intent.getData();
        File real = findRealFile(uri);
        if (real != null && real.isFile() && real.canRead()) {
            String inside = relativeToCodes(real);
            if (inside != null) return new Result(inside, null);
            String full = real.getAbsolutePath();
            remember(full);
            projectFiles.allowOutside(full);
            return new Result(full, null);
        }
        return copyIntoCodes(uri);
    }

    /* ---------- finding the real file behind a link ---------- */

    private File findRealFile(Uri uri) {
        String scheme = uri.getScheme();
        if (ContentResolver.SCHEME_FILE.equals(scheme) || scheme == null) {
            return uri.getPath() == null ? null : new File(uri.getPath());
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme)) return null;

        long size = -1;
        // Many file managers still answer with the path itself
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int data = cursor.getColumnIndex("_data");
                if (data >= 0 && !cursor.isNull(data)) {
                    File file = new File(cursor.getString(data));
                    if (file.isFile()) return file;
                }
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) {
            // Some apps refuse queries; try the link's text below
        }

        // Otherwise the link's path usually ends with the file's place on the storage:
        // .../external_files/Faisal/codes/a.html, .../document/primary:Faisal/codes/a.html
        String path = uri.getPath();
        if (path == null) return null;
        List<String> parts = new ArrayList<>();
        for (String piece : path.split("[/:]")) if (!piece.isEmpty()) parts.add(piece);
        File storage = Environment.getExternalStorageDirectory();
        for (int start = 0; start < parts.size(); start++) {
            // a full path inside the link: /storage/emulated/0/...
            if (parts.get(start).equals("storage") || parts.get(start).equals("sdcard")) {
                File file = new File("/" + String.join("/", parts.subList(start, parts.size())));
                if (matches(file, size)) return file;
            }
            File file = new File(storage, String.join("/", parts.subList(start, parts.size())));
            if (matches(file, size)) return file;
        }
        return null;
    }

    private static boolean matches(File file, long size) {
        return file.isFile() && (size < 0 || file.length() == size);
    }

    private String relativeToCodes(File file) {
        try {
            String root = codes.getCanonicalPath() + File.separator;
            String path = file.getCanonicalPath();
            if (path.startsWith(root)) return path.substring(root.length());
        } catch (IOException ignored) {
            // treat it as outside
        }
        return null;
    }

    /* ---------- the copy, when the file itself cannot be reached ---------- */

    private Result copyIntoCodes(Uri uri) {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                if (out.size() > MAX_BYTES) return null;
            }
            bytes = out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }

        String name = safeName(displayName(uri));
        File target = new File(codes, name);
        // The same file opened again: reuse the copy instead of making another
        for (int n = 2; target.exists(); n++) {
            if (sameContent(target, bytes)) return new Result(target.getName(), "A copy was saved in Faisal/codes");
            target = new File(codes, numbered(name, n));
        }
        try {
            codes.mkdirs();
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
            }
        } catch (IOException e) {
            return null;
        }
        return new Result(target.getName(), "A copy was saved in Faisal/codes");
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the link
        }
        String last = uri.getLastPathSegment();
        if (last != null && last.contains("/")) last = last.substring(last.lastIndexOf('/') + 1);
        if (last != null && last.contains(":")) last = last.substring(last.lastIndexOf(':') + 1);
        return last;
    }

    private static String safeName(String name) {
        if (name == null) name = "";
        name = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "opened.txt";
        return name;
    }

    private static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot) : name + " (" + n + ")";
    }

    private static boolean sameContent(File file, byte[] bytes) {
        if (file.length() != bytes.length) return false;
        try {
            return Arrays.equals(java.nio.file.Files.readAllBytes(file.toPath()), bytes);
        } catch (IOException e) {
            return false;
        }
    }

    /* ---------- files outside the folder, remembered across restarts ---------- */

    private Set<String> remembered() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_OUTSIDE, "");
        Set<String> paths = new LinkedHashSet<>();
        for (String line : saved.split("\n")) if (line.startsWith("/")) paths.add(line);
        return paths;
    }

    private void remember(String path) {
        Set<String> paths = remembered();
        paths.remove(path);
        paths.add(path);
        List<String> list = new ArrayList<>(paths);
        if (list.size() > MAX_REMEMBERED) list = list.subList(list.size() - MAX_REMEMBERED, list.size());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_OUTSIDE, String.join("\n", list)).apply();
    }
}
