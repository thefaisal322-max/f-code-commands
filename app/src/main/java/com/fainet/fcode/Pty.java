package com.fainet.fcode;

/** Native pseudo-terminal helpers, implemented in src/main/jni/fcode_pty.c. */
final class Pty {

    static {
        System.loadLibrary("fcodepty");
    }

    private Pty() {
    }

    /**
     * Starts {@code cmd} attached to a new pseudo-terminal.
     *
     * @param args      the program's arguments, starting with its own name (argv[0])
     * @param envVars   environment as "NAME=value" strings
     * @param processId receives the process id of the started program at index 0
     * @return the file descriptor of the terminal's master side: read the program's output from
     * it, write the user's input to it
     */
    static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars,
                                       int[] processId, int rows, int columns);

    /** Tells the program how many rows and columns the terminal now has. */
    static native void setPtyWindowSize(int fd, int rows, int columns);

    /** Blocks until the process ends. Returns its exit code, or minus the signal that killed it. */
    static native int waitFor(int processId);
}
