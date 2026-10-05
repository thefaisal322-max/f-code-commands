package com.fainet.fcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Where the user's work lives. On the phone's shared storage there is one folder, "Faisal",
 * with two folders inside it:
 *
 *   Faisal/codes      the files of the Code tab
 *   Faisal/commands   where the terminal starts
 *
 * Inside the app's home folder, ~/codes and ~/commands are links to those two, so the terminal
 * shows short paths (~/commands) and the editor always opens ~/codes.
 *
 * Until the user allows access to the phone's storage, ~/codes and ~/commands are ordinary
 * folders inside the app. Once access is allowed their contents are copied out to Faisal/ and
 * the folders become links.
 */
final class Workspace {

    static final String FOLDER = "Faisal";
    private static final String[] PARTS = {"codes", "commands"};

    /** ~/codes: a link to the shared folder, or a real folder inside the app. */
    final File codes;
    /** ~/commands, likewise. */
    final File commands;
    /** True when both folders are on the phone's shared storage. */
    final boolean shared;

    private Workspace(File codes, File commands, boolean shared) {
        this.codes = codes;
        this.commands = commands;
        this.shared = shared;
    }

    /**
     * Creates or repairs the folders and links. Never throws: when something cannot be done on
     * shared storage, the folder simply stays inside the app.
     */
    static Workspace prepare(Context context, File home, boolean hasStorageAccess) {
        File sharedRoot = hasStorageAccess ? new File(Environment.getExternalStorageDirectory(), FOLDER) : null;
        boolean allShared = sharedRoot != null;

        for (String name : PARTS) {
            Path local = new File(home, name).toPath();
            boolean nowShared = false;
            if (sharedRoot != null) {
                try {
                    nowShared = linkToShared(local, new File(sharedRoot, name));
                } catch (IOException | RuntimeException e) {
                    nowShared = false;
                }
            }
            if (!nowShared) {
                allShared = false;
                try {
                    // A link left from a time when storage access was allowed leads nowhere now
                    if (Files.isSymbolicLink(local) && !Files.isDirectory(local)) Files.delete(local);
                    if (!Files.exists(local)) Files.createDirectories(local);
                } catch (IOException ignored) {
                    // The editor reports an unreadable folder itself.
                }
            }
        }

        Workspace workspace = new Workspace(new File(home, "codes"), new File(home, "commands"), allShared);
        workspace.adoptOldHomeFiles(context, home);
        return workspace;
    }

    /** Makes {@code local} a link to {@code target}, moving any real folder's contents there first. */
    private static boolean linkToShared(Path local, File target) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) return false;
        Path targetPath = target.toPath();

        if (Files.isSymbolicLink(local)) {
            if (Files.readSymbolicLink(local).equals(targetPath)) return true;
            Files.delete(local);
        } else if (Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS)) {
            copyTree(local, targetPath);   // throws before anything is deleted if a file cannot be copied
            deleteTree(local);
        } else {
            Files.deleteIfExists(local);
        }
        Files.createSymbolicLink(local, targetPath);
        return true;
    }

    /**
     * Earlier versions kept the editor's files directly in the home folder. Once only, copy
     * those files into ~/codes so they are still there in the editor. Folders and hidden files
     * are left alone: they belong to the terminal.
     */
    private void adoptOldHomeFiles(Context context, File home) {
        SharedPreferences prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        if (prefs.getBoolean("old_home_files_copied", false)) return;
        File[] children = home.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.getName().startsWith(".") || !Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
                File copy = new File(codes, child.getName());
                if (copy.exists()) continue;
                try {
                    Files.copy(child.toPath(), copy.toPath());
                } catch (IOException ignored) {
                    // The original is still in the home folder.
                }
            }
        }
        prefs.edit().putBoolean("old_home_files_copied", true).apply();
    }

    /** Copies a folder's contents into another folder, keeping files that already exist there. */
    private static void copyTree(final Path from, final Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;   // shared storage cannot hold links
                if (!Files.exists(target)) Files.copy(file, target);   // plain copy: shared storage rejects permissions and dates
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
