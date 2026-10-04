package com.fainet.fcode;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * File access for the editor. Every path is relative to the shell's home folder, so the Code tab
 * and the Commands tab work on the same files.
 *
 * Each method returns a JSON string: {"ok":true, ...} or {"ok":false, "error":"..."}.
 */
final class ProjectFiles {

    /** Files larger than this are not opened in the editor. */
    private static final long MAX_TEXT_FILE_BYTES = 2L * 1024 * 1024;

    private final File home;

    ProjectFiles(File home) {
        this.home = home;
    }

    String list(String dir) {
        try {
            File folder = resolve(dir);
            File[] children = folder.listFiles();
            if (children == null) return error(folder.exists() ? "cannot read folder" : "not found");

            // Folders first, then by name
            Arrays.sort(children, (a, b) -> {
                boolean aDir = a.isDirectory(), bDir = b.isDirectory();
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            JSONArray entries = new JSONArray();
            for (File child : children) {
                JSONObject entry = new JSONObject();
                entry.put("name", child.getName());
                entry.put("dir", child.isDirectory());
                entry.put("size", child.length());
                entries.put(entry);
            }
            return ok().put("entries", entries).toString();
        } catch (IOException | JSONException e) {
            return error(e.getMessage());
        }
    }

    String stat(String path) {
        try {
            File file = resolve(path);
            return ok()
                    .put("exists", file.exists())
                    .put("dir", file.isDirectory())
                    .put("size", file.length())
                    .put("modified", file.lastModified())
                    .toString();
        } catch (IOException | JSONException e) {
            return error(e.getMessage());
        }
    }

    /** Reads a text file. Refuses folders, big files and files that are not text. */
    String read(String path) {
        try {
            File file = resolve(path);
            if (file.isDirectory()) return error("is a folder");
            if (!file.isFile()) return error("not found");
            if (file.length() > MAX_TEXT_FILE_BYTES) return error("too large");

            byte[] bytes = readAll(file);
            for (byte b : bytes) {
                if (b == 0) return error("not a text file");
            }
            return ok().put("content", new String(bytes, StandardCharsets.UTF_8)).toString();
        } catch (IOException | JSONException e) {
            return error(e.getMessage());
        }
    }

    String write(String path, String content) {
        return writeBytes(path, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    /** Writes any kind of file (zip, image, ...) from Base64 text. */
    String writeBase64(String path, String base64) {
        try {
            return writeBytes(path, Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT));
        } catch (IllegalArgumentException e) {
            return error("bad data");
        }
    }

    /** Deletes a file, or a folder that is already empty. */
    String delete(String path) {
        try {
            File file = resolve(path);
            if (file.equals(home)) return error("cannot delete the home folder");
            if (!file.exists()) return error("not found");
            if (!file.delete()) return error(file.isDirectory() ? "folder is not empty" : "cannot delete");
            return ok().toString();
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    String rename(String from, String to) {
        try {
            File source = resolve(from);
            File target = resolve(to);
            if (!source.exists()) return error("not found");
            if (target.exists()) return error("already exists");
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return error("cannot create folder");
            if (!source.renameTo(target)) return error("cannot rename");
            return ok().toString();
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    String mkdir(String path) {
        try {
            File folder = resolve(path);
            if (folder.isDirectory()) return ok().toString();
            if (folder.exists()) return error("already exists");
            if (!folder.mkdirs()) return error("cannot create folder");
            return ok().toString();
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    private String writeBytes(String path, byte[] bytes) {
        try {
            File file = resolve(path);
            if (file.isDirectory()) return error("is a folder");
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return error("cannot create folder");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            return ok().toString();
        } catch (IOException e) {
            return error(e.getMessage());
        }
    }

    /**
     * Turns "src/../app.js" into a file under home. ".." is worked out from the text alone,
     * without following links, so links inside home (such as ~/storage/downloads) keep working
     * while a path can never climb above home.
     */
    private File resolve(String path) throws IOException {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : (path == null ? "" : path).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IOException("outside the home folder");
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        File file = home;
        for (String part : parts) file = new File(file, part);
        return file;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.max(32, file.length()));
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static JSONObject ok() {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
        } catch (JSONException ignored) {
            // cannot happen for a boolean
        }
        return result;
    }

    private static String error(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("error", message == null ? "unknown error" : message);
        } catch (JSONException ignored) {
            // cannot happen for these values
        }
        return result.toString();
    }
}
