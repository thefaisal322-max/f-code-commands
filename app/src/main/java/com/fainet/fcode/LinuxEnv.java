package com.fainet.fcode;

import android.content.Context;
import android.os.Process;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * A small Linux system (Alpine) that the terminal runs inside, so that bash, git and a package
 * manager are available. Android does not allow an app to run programs it downloads, so the
 * Linux programs are run through PRoot: PRoot itself ships inside the APK as native libraries
 * (the only place an app may run programs from) and loads the Linux programs on their behalf.
 *
 * Files:
 *   files/linux/alpine/          the Linux system (unpacked from assets/linux/*.tar.gz)
 *   files/linux/lib/             a link so PRoot finds its helper library under the name it wants
 *   files/linux/installed-...    written last, so a half-finished setup is redone
 */
final class LinuxEnv {

    /** Change this when the bundled system changes: the old one is replaced on the next start. */
    private static final String VERSION = "alpine-3.21.8-a";

    private static final String PROOT = "libproot-xed.so";
    private static final String LOADER = "libproot.so";
    private static final String LOADER_32 = "libproot32.so";
    private static final String TALLOC = "libtalloc.so";

    private final Context context;
    private final File base;
    private final File root;
    private final File marker;
    private final File nativeDir;

    LinuxEnv(Context context) {
        this.context = context.getApplicationContext();
        this.base = new File(context.getFilesDir(), "linux");
        this.root = new File(base, "alpine");
        this.marker = new File(base, "installed-" + VERSION);
        this.nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
    }

    /** The bundled system for this phone's processor, or null when there is none. */
    private static String rootfsAsset() {
        String[] abis = Process.is64Bit() ? android.os.Build.SUPPORTED_64_BIT_ABIS : android.os.Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) return "linux/alpine-aarch64.tar.gz";
            if ("armeabi-v7a".equals(abi)) return "linux/alpine-armhf.tar.gz";
        }
        return null;
    }

    boolean isSupported() {
        if (rootfsAsset() == null) return false;
        return new File(nativeDir, PROOT).isFile() && new File(nativeDir, LOADER).isFile() && new File(nativeDir, TALLOC).isFile();
    }

    boolean isInstalled() {
        return marker.isFile() && new File(root, "bin/busybox").exists();
    }

    /** Unpacks the system and writes Fcode's own scripts into it. Safe to call again. */
    synchronized void install() throws IOException {
        if (isInstalled()) return;
        String asset = rootfsAsset();
        if (asset == null) throw new IOException("no Linux system for this processor");

        deleteTree(base.toPath());
        if (!root.mkdirs()) throw new IOException("cannot create " + root);
        try (InputStream in = context.getAssets().open(asset)) {
            extractTarGz(in, root);
        }
        writeScripts();
        if (!marker.createNewFile() && !marker.isFile()) throw new IOException("cannot write " + marker);
    }

    /** The program and arguments that start a shell inside the Linux system. */
    String[] command(File home) throws IOException {
        File shm = new File(root, "tmp");
        if (!shm.isDirectory() && !shm.mkdirs()) throw new IOException("cannot create " + shm);
        writeScripts();   // keeps the scripts current after an app update

        List<String> args = new ArrayList<>();
        args.add(new File(nativeDir, PROOT).getAbsolutePath());
        args.add("--kill-on-exit");

        // Android's own system folders, so the phone's commands (am, getprop, ping...) still run
        String[] systemPaths = {"/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                "/linkerconfig/ld.config.txt", "/linkerconfig/com.android.art/ld.config.txt",
                "/plat_property_contexts", "/property_contexts"};
        for (String path : systemPaths) {
            File file = new File(path);
            if (!file.exists()) continue;
            try {
                bind(args, file.getCanonicalPath());
            } catch (IOException ignored) {
                // cannot be resolved: leave it out
            }
        }
        for (String path : new String[]{"/sdcard", "/storage", "/dev", "/data", "/proc", "/sys"}) {
            if (new File(path).exists()) bind(args, path);
        }
        bind(args, "/dev/urandom:/dev/random");
        bind(args, nativeDir.getAbsolutePath());
        bind(args, shm.getAbsolutePath() + ":/dev/shm");
        bind(args, "/proc/self/fd:/dev/fd");
        bind(args, "/proc/self/fd/0:/dev/stdin");
        bind(args, "/proc/self/fd/1:/dev/stdout");
        bind(args, "/proc/self/fd/2:/dev/stderr");

        args.add("-r");
        args.add(root.getAbsolutePath());
        args.add("-0");                 // look like root inside, which the package manager expects
        args.add("--link2symlink");     // Android forbids hard links in app storage
        args.add("--sysvipc");
        args.add("-L");
        args.add("-w");
        args.add(home.getAbsolutePath());
        args.add("/bin/sh");
        args.add("/etc/fcode/init.sh");
        return args.toArray(new String[0]);
    }

    /** Environment for {@link #command}. */
    Map<String, String> environment(File home, File tmp) throws IOException {
        // PRoot is linked against "libtalloc.so.2"; the APK can only carry it as libtalloc.so
        File libDir = new File(base, "lib");
        if (!libDir.isDirectory() && !libDir.mkdirs()) throw new IOException("cannot create " + libDir);
        Path link = new File(libDir, "libtalloc.so.2").toPath();
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, new File(nativeDir, TALLOC).toPath());   // recreated: the APK's folder moves on every update

        File prootTmp = new File(tmp, "proot");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) throw new IOException("cannot create " + prootTmp);

        Map<String, String> env = new HashMap<>(System.getenv());   // Android's tools need ANDROID_ROOT and friends
        env.remove("ENV");
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        env.put("PROOT_LOADER", new File(nativeDir, LOADER).getAbsolutePath());
        File loader32 = new File(nativeDir, LOADER_32);
        if (loader32.isFile()) {
            env.put("PROOT_LOADER_32", loader32.getAbsolutePath());
            env.put("PROOT_LOADER32", loader32.getAbsolutePath());
        }
        env.put("HOME", home.getAbsolutePath());
        env.put("USER", "root");
        env.put("LOGNAME", "root");
        env.put("TMPDIR", "/tmp");
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        env.put("SHELL", "/bin/sh");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin");
        return env;
    }

    private static void bind(List<String> args, String what) {
        args.add("-b");
        args.add(what);
    }

    /* ---------- Fcode's scripts inside the Linux system ---------- */

    private void writeScripts() throws IOException {
        write("etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n", false);
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost\n", false);

        write("etc/fcode/init.sh", ""
                + "#!/bin/sh\n"
                + "# Started by Fcode for every Linux terminal.\n"
                + "unset LD_LIBRARY_PATH LD_PRELOAD\n"
                + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin\n"
                + "if [ ! -e /linkerconfig/ld.config.txt ]; then\n"
                + "    mkdir -p /linkerconfig 2>/dev/null && : > /linkerconfig/ld.config.txt 2>/dev/null\n"
                + "fi\n"
                + "cd \"$HOME\" 2>/dev/null\n"
                + "if [ -x /bin/bash ]; then exec /bin/bash -l; fi\n"
                + "exec /bin/sh -l\n", true);

        write("etc/profile.d/fcode.sh", ""
                + "# Fcode's shell settings. This file is rewritten by the app:\n"
                + "# put your own settings in ~/.profile or ~/.bashrc instead.\n"
                + "export PS1='\\w $ '\n"
                + "export PATH=\"$PATH:/system/bin\"    # the phone's own commands, after Linux's\n"
                + "export PIP_BREAK_SYSTEM_PACKAGES=1\n"
                + "alias ll='ls -l'\n"
                + "if [ ! -e /etc/fcode/setup-done ] && [ -t 0 ]; then\n"
                + "    fcode-setup --ask\n"
                + "    # switch to bash straight away if it was just installed\n"
                + "    if [ -x /bin/bash ] && [ -z \"$BASH_VERSION\" ]; then exec /bin/bash -l; fi\n"
                + "fi\n", false);

        write("usr/local/bin/fcode-setup", ""
                + "#!/bin/sh\n"
                + "# Installs the tools most people expect in a terminal.\n"
                + "PACKAGES='bash git openssh curl unzip zip nano'\n"
                + "if [ \"$1\" = \"--ask\" ]; then\n"
                + "    mkdir -p /etc/fcode && : > /etc/fcode/setup-done    # ask only once\n"
                + "    echo 'Linux is ready.'\n"
                + "    echo\n"
                + "    echo 'Install bash, git, curl, unzip, zip and nano now?'\n"
                + "    printf 'This needs internet. [Y/n] '\n"
                + "    read answer\n"
                + "    case \"$answer\" in\n"
                + "        n|N|no|No|NO) echo 'Skipped. Run fcode-setup whenever you want them.'; exit 0 ;;\n"
                + "    esac\n"
                + "fi\n"
                + "echo 'Installing...'\n"
                + "if apk update && apk add $PACKAGES; then\n"
                + "    echo\n"
                + "    echo 'Done. More tools: pkg install NAME'\n"
                + "else\n"
                + "    echo\n"
                + "    echo 'That did not work. Check your internet, then run: fcode-setup'\n"
                + "    exit 1\n"
                + "fi\n", true);

        String pkg = ""
                + "#!/bin/sh\n"
                + "# Termux-style front end for Alpine's package manager (apk),\n"
                + "# so 'pkg install git' and 'apt install git' work as people expect.\n"
                + "usage() {\n"
                + "    echo 'Usage: pkg COMMAND [packages]'\n"
                + "    echo '  install NAME...    install packages'\n"
                + "    echo '  uninstall NAME...  remove packages'\n"
                + "    echo '  update             refresh the package list'\n"
                + "    echo '  upgrade            update everything installed'\n"
                + "    echo '  search TEXT        find packages'\n"
                + "    echo '  list-installed     show installed packages'\n"
                + "    echo '  show NAME          describe a package'\n"
                + "}\n"
                + "# Names that differ between Termux and Alpine\n"
                + "translate() {\n"
                + "    case \"$1\" in\n"
                + "        python|python3-dev) echo python3 ;;\n"
                + "        python-pip|pip|python3-pip) echo py3-pip ;;\n"
                + "        nodejs-lts|node) echo nodejs npm ;;\n"
                + "        nodejs) echo nodejs npm ;;\n"
                + "        gh) echo github-cli ;;\n"
                + "        build-essential) echo build-base ;;\n"
                + "        openssh-client|ssh) echo openssh ;;\n"
                + "        golang) echo go ;;\n"
                + "        *) echo \"$1\" ;;\n"
                + "    esac\n"
                + "}\n"
                + "command=\"$1\"\n"
                + "[ $# -gt 0 ] && shift\n"
                + "names=''\n"
                + "for arg in \"$@\"; do\n"
                + "    case \"$arg\" in\n"
                + "        -y|--yes|--assume-yes|-q) ;;      # apk never asks\n"
                + "        -*) names=\"$names $arg\" ;;\n"
                + "        *) names=\"$names $(translate \"$arg\")\" ;;\n"
                + "    esac\n"
                + "done\n"
                + "case \"$command\" in\n"
                + "    install|add|in|i) apk update -q 2>/dev/null; exec apk add $names ;;\n"
                + "    uninstall|remove|rm|del|purge) exec apk del $names ;;\n"
                + "    update|up) exec apk update ;;\n"
                + "    upgrade|full-upgrade|dist-upgrade) apk update && exec apk upgrade ;;\n"
                + "    search|se) exec apk search $names ;;\n"
                + "    list-installed|list) exec apk info ;;\n"
                + "    list-all) exec apk search -q ;;\n"
                + "    show|info) exec apk info -a $names ;;\n"
                + "    files) exec apk info -L $names ;;\n"
                + "    reinstall) exec apk fix --reinstall $names ;;\n"
                + "    clean|autoclean|autoremove) exec apk cache clean ;;\n"
                + "    ''|help|-h|--help) usage ;;\n"
                + "    *) echo \"pkg: unknown command '$command'\"; usage; exit 1 ;;\n"
                + "esac\n";
        write("usr/local/bin/pkg", pkg, true);
        write("usr/local/bin/apt", pkg, true);
        write("usr/local/bin/apt-get", pkg, true);

        String storage = ""
                + "#!/bin/sh\n"
                + "# Asks the app to allow access to the phone's shared storage and to create\n"
                + "# the ~/storage links. The app listens for this escape code.\n"
                + "printf '\\033]777;fcode;setup-storage\\007'\n";
        write("usr/local/bin/setup-storage", storage, true);
        write("usr/local/bin/termux-setup-storage", storage, true);
    }

    private void write(String path, String text, boolean executable) throws IOException {
        File file = new File(root, path);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        Files.deleteIfExists(file.toPath());   // may be a link from the package; never write through it
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        setMode(file.toPath(), executable ? 0755 : 0644);
    }

    /* ---------- unpacking ---------- */

    /**
     * Unpacks a .tar.gz into {@code dest}: folders, files, symbolic links and hard links (as copies).
     * Device files are skipped; entries that would land outside {@code dest} are skipped.
     */
    static void extractTarGz(InputStream in, File dest) throws IOException {
        Path destPath = dest.toPath().toAbsolutePath().normalize();
        try (InputStream tar = new GZIPInputStream(new BufferedInputStream(in, 64 * 1024), 64 * 1024)) {
            byte[] header = new byte[512];
            byte[] buffer = new byte[64 * 1024];
            String longName = null;
            String longLink = null;

            while (readFully(tar, header, 512)) {
                if (isBlank(header)) continue;   // end-of-archive padding

                String name = text(header, 0, 100);
                String prefix = text(header, 345, 155);
                String linkName = text(header, 157, 100);
                char type = (char) (header[156] & 0xff);
                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                if (text(header, 257, 5).equals("ustar") && !prefix.isEmpty()) name = prefix + "/" + name;

                long padded = (size + 511) / 512 * 512;

                // Extra records that carry a name too long for the header
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    byte[] data = new byte[(int) size];
                    if (!readFully(tar, data, data.length)) throw new IOException("archive ends too early");
                    skip(tar, padded - size, buffer);
                    if (type == 'L') longName = text(data, 0, data.length);
                    else if (type == 'K') longLink = text(data, 0, data.length);
                    else if (type == 'x') {
                        Map<String, String> pax = paxRecords(data);
                        if (pax.containsKey("path")) longName = pax.get("path");
                        if (pax.containsKey("linkpath")) longLink = pax.get("linkpath");
                    }
                    continue;
                }
                if (longName != null) { name = longName; longName = null; }
                if (longLink != null) { linkName = longLink; longLink = null; }

                Path target = destPath.resolve(name).normalize();
                if (!target.startsWith(destPath) || target.equals(destPath)) {
                    skip(tar, padded, buffer);
                    continue;
                }
                if (target.getParent() != null) Files.createDirectories(target.getParent());

                switch (type) {
                    case '5':   // folder
                        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(target);
                            Files.createDirectories(target);
                        }
                        setMode(target, mode | 0700);
                        skip(tar, padded, buffer);
                        break;
                    case '2':   // symbolic link
                        Files.deleteIfExists(target);
                        Files.createSymbolicLink(target, Paths.get(linkName));
                        skip(tar, padded, buffer);
                        break;
                    case '1': { // hard link: Android does not allow them here, so copy the file
                        Path source = destPath.resolve(linkName).normalize();
                        if (source.startsWith(destPath) && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                        skip(tar, padded, buffer);
                        break;
                    }
                    case '0':
                    case '\0':
                    case '7': { // ordinary file
                        Files.deleteIfExists(target);
                        try (OutputStream out = Files.newOutputStream(target)) {
                            long left = size;
                            while (left > 0) {
                                int count = tar.read(buffer, 0, (int) Math.min(buffer.length, left));
                                if (count < 0) throw new IOException("archive ends too early");
                                out.write(buffer, 0, count);
                                left -= count;
                            }
                        }
                        setMode(target, mode | 0600);
                        skip(tar, padded - size, buffer);
                        break;
                    }
                    default:    // device files and pipes: not needed, and not allowed
                        skip(tar, padded, buffer);
                }
            }
        }
    }

    private static Map<String, String> paxRecords(byte[] data) {
        Map<String, String> records = new HashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int space = pos;
            while (space < data.length && data[space] != ' ') space++;
            if (space >= data.length) break;
            int length;
            try {
                length = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || pos + length > data.length) break;
            String record = new String(data, space + 1, pos + length - space - 2, StandardCharsets.UTF_8);   // without the final newline
            int equals = record.indexOf('=');
            if (equals > 0) records.put(record.substring(0, equals), record.substring(equals + 1));
            pos += length;
        }
        return records;
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int count = in.read(buffer, done, length - done);
            if (count < 0) {
                if (done == 0) return false;
                throw new IOException("archive ends too early");
            }
            done += count;
        }
        return true;
    }

    private static void skip(InputStream in, long count, byte[] buffer) throws IOException {
        while (count > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, count));
            if (read < 0) throw new IOException("archive ends too early");
            count -= read;
        }
    }

    private static boolean isBlank(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static String text(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] data, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            int c = data[i] & 0xff;
            if (c >= '0' && c <= '7') value = value * 8 + (c - '0');
            else if (value != 0 || c == 0) break;   // stop at the first non-digit after the number
        }
        return value;
    }

    private static void setMode(Path path, int mode) throws IOException {
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(path, perms);
    }

    /** Deletes a folder and everything in it, without following links out of it. */
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
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
