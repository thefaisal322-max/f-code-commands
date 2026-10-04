package com.fainet.fcode;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One running shell attached to a pseudo-terminal: Android's own sh, or a shell in the bundled Linux. */
final class ShellSession {

    interface Listener {
        /** Called on a background thread. {@code buffer} is reused, so copy what you keep. */
        void onOutput(byte[] buffer, int length);

        /** Called on a background thread once the shell has ended. */
        void onExit(int exitCode);
    }

    private static final String SHELL = "/system/bin/sh";

    private final int pid;
    private final ParcelFileDescriptor pty;
    private final FileOutputStream toShell;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    private ShellSession(int fd, int pid, Listener listener) {
        this.pid = pid;
        this.pty = ParcelFileDescriptor.adoptFd(fd);
        this.toShell = new FileOutputStream(pty.getFileDescriptor());

        Thread reader = new Thread(() -> {
            FileInputStream fromShell = new FileInputStream(pty.getFileDescriptor());
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = fromShell.read(buffer)) > 0) {
                    listener.onOutput(buffer, count);
                }
            } catch (IOException ignored) {
                // The read fails once the shell has exited and the terminal closes.
            }
        }, "fcode-pty-reader");
        reader.setDaemon(true);
        reader.start();

        Thread waiter = new Thread(() -> {
            int exitCode = Pty.waitFor(pid);
            running = false;
            try {
                reader.join(500);   // let the last output through
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writer.shutdownNow();
            try {
                pty.close();
            } catch (IOException ignored) {
                // already closed
            }
            listener.onExit(exitCode);
        }, "fcode-pty-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Starts a shell on a new pseudo-terminal.
     *
     * @param linux when not null the shell runs inside the bundled Linux system (bash, git,
     *              packages); when null it is Android's own /system/bin/sh
     */
    static ShellSession start(Context context, int columns, int rows, Listener listener, LinuxEnv linux) throws IOException {
        File home = homeDir(context);
        File tmp = new File(context.getCacheDir(), "tmp");
        if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("Cannot create " + tmp);
        tmp = tmp.getCanonicalFile();

        String program;
        String[] args;
        Map<String, String> env;

        if (linux != null) {
            args = linux.command(home);
            program = args[0];
            env = linux.environment(home, tmp);
        } else {
            File rc = new File(home, ".fcode_shrc");
            writeText(rc, ""
                    + "# Written by Fcode on every start. Put your own settings in ~/.shrc\n"
                    // Shows the folder as ~ or ~/sub. (mksh's ${PWD/#$HOME/~} does not work when
                    // $HOME contains slashes, so the prefix is cut off with a small function.)
                    + "fcode_prompt_dir() { case \"$PWD\" in \"$HOME\") echo '~' ;; \"$HOME\"/*) echo \"~${PWD#\"$HOME\"}\" ;; *) echo \"$PWD\" ;; esac; }\n"
                    + "PS1='$(fcode_prompt_dir) $ '\n"
                    + "alias ls='ls --color=auto'\n"
                    + "alias ll='ls -l'\n"
                    // Asks the app (through a terminal escape code the page listens for) to open
                    // the phone's shared storage; the app then creates the ~/storage links.
                    + "fcode_setup_storage() { printf '\\033]777;fcode;setup-storage\\007'; }\n"
                    + "alias setup-storage=fcode_setup_storage 2>/dev/null\n"
                    + "alias termux-setup-storage=fcode_setup_storage 2>/dev/null\n"
                    + "[ -f \"$HOME/.shrc\" ] && . \"$HOME/.shrc\"\n");

            program = SHELL;
            args = new String[]{"sh"};
            // Start from the app's own environment: Android's tools need variables like ANDROID_ROOT.
            env = new HashMap<>(System.getenv());
            String systemPath = env.get("PATH");
            env.put("PATH", systemPath == null || systemPath.isEmpty() ? "/system/bin:/system/xbin" : systemPath);
            env.put("HOME", home.getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("TERM", "xterm-256color");
            env.put("COLORTERM", "truecolor");
            env.put("LANG", "en_US.UTF-8");
            env.put("SHELL", SHELL);
            env.put("ENV", rc.getAbsolutePath());   // the file an interactive sh reads when it starts
        }

        List<String> envList = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            envList.add(entry.getKey() + "=" + entry.getValue());
        }

        int[] pidOut = new int[1];
        int fd = Pty.createSubprocess(program, home.getAbsolutePath(), args,
                envList.toArray(new String[0]), pidOut, clampRows(rows), clampColumns(columns));
        return new ShellSession(fd, pidOut[0], listener);
    }

    /**
     * The shell's home folder, which is also where the editor keeps its files. Android hands out
     * /data/user/0/... but the shell sees the real path /data/data/...; the real one is used
     * everywhere, otherwise $HOME never matches $PWD and the prompt can't show "~".
     */
    static File homeDir(Context context) throws IOException {
        File home = new File(context.getFilesDir(), "home");
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("Cannot create " + home);
        return home.getCanonicalFile();
    }

    boolean isRunning() {
        return running;
    }

    /** Sends what the user typed to the shell. */
    void write(String text) {
        if (!running || text == null || text.isEmpty()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            writer.execute(() -> {
                try {
                    toShell.write(bytes);
                } catch (IOException ignored) {
                    // The shell is gone; onExit will report it.
                }
            });
        } catch (RuntimeException ignored) {
            // The writer was shut down because the shell just exited.
        }
    }

    void resize(int columns, int rows) {
        if (!running) return;
        try {
            Pty.setPtyWindowSize(pty.getFd(), clampRows(rows), clampColumns(columns));
        } catch (RuntimeException ignored) {
            // The terminal was closed at the same moment.
        }
    }

    void destroy() {
        if (running) android.os.Process.killProcess(pid);
    }

    private static int clampRows(int rows) {
        return Math.max(4, Math.min(rows, 500));
    }

    private static int clampColumns(int columns) {
        return Math.max(10, Math.min(columns, 1000));
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
