/*
 * Starts a program attached to a pseudo-terminal (PTY), so the shell behaves the way it does
 * in a real terminal: it knows the screen size, Ctrl+C reaches the running program, and
 * full-screen programs can draw.
 *
 * The Java side is com.fainet.fcode.Pty.
 */
#define _GNU_SOURCE

#include <jni.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static jint throw_runtime_exception(JNIEnv* env, const char* message)
{
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
    return -1;
}

/* Copies a Java String[] into a NULL-terminated C array. Returns NULL for a null or empty array. */
static char** copy_string_array(JNIEnv* env, jobjectArray array)
{
    jsize size = array ? (*env)->GetArrayLength(env, array) : 0;
    if (size <= 0) return NULL;

    char** result = (char**) calloc((size_t) size + 1, sizeof(char*));
    if (result == NULL) return NULL;

    for (jsize i = 0; i < size; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char* utf8 = item ? (*env)->GetStringUTFChars(env, item, NULL) : NULL;
        result[i] = strdup(utf8 ? utf8 : "");
        if (utf8) (*env)->ReleaseStringUTFChars(env, item, utf8);
        if (item) (*env)->DeleteLocalRef(env, item);
    }
    return result;
}

static void free_string_array(char** array)
{
    if (array == NULL) return;
    for (char** p = array; *p; p++) free(*p);
    free(array);
}

JNIEXPORT jint JNICALL
Java_com_fainet_fcode_Pty_createSubprocess(JNIEnv* env, jclass clazz, jstring cmd, jstring cwd,
                                           jobjectArray args, jobjectArray envVars,
                                           jintArray processIdArray, jint rows, jint columns)
{
    (void) clazz;

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot set up the pseudo-terminal");
    }

    /* UTF-8 input, and no flow control so Ctrl+S / Ctrl+Q reach the program. */
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) columns;
    ioctl(ptm, TIOCSWINSZ, &size);

    /* Everything the child needs is copied before fork(): the child must not call into the JVM. */
    const char* cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char* cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
    char* cmd_copy = cmd_utf8 ? strdup(cmd_utf8) : NULL;
    char* cwd_copy = cwd_utf8 ? strdup(cwd_utf8) : NULL;
    if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    if (cwd_utf8) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);
    char** argv = copy_string_array(env, args);
    char** envp = copy_string_array(env, envVars);

    if (cmd_copy == NULL || cwd_copy == NULL || argv == NULL) {
        free(cmd_copy);
        free(cwd_copy);
        free_string_array(argv);
        free_string_array(envp);
        close(ptm);
        return throw_runtime_exception(env, "Missing command, working directory or arguments");
    }

    pid_t pid = fork();
    if (pid < 0) {
        free(cmd_copy);
        free(cwd_copy);
        free_string_array(argv);
        free_string_array(envp);
        close(ptm);
        return throw_runtime_exception(env, "fork() failed");
    }

    if (pid == 0) {
        /* Child: make the PTY its controlling terminal and its stdin/stdout/stderr. */
        sigset_t signals;
        sigfillset(&signals);
        sigprocmask(SIG_UNBLOCK, &signals, NULL);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(127);
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        /* Close every other file descriptor inherited from the app. */
        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        clearenv();
        if (envp != NULL) {
            for (char** p = envp; *p; p++) putenv(*p);
        }

        if (chdir(cwd_copy) != 0) perror("fcode: chdir");

        execvp(cmd_copy, argv);
        perror("fcode: exec");
        _exit(127);
    }

    /* Parent. */
    free(cmd_copy);
    free(cwd_copy);
    free_string_array(argv);
    free_string_array(envp);

    jint pid_value = (jint) pid;
    (*env)->SetIntArrayRegion(env, processIdArray, 0, 1, &pid_value);
    return ptm;
}

JNIEXPORT void JNICALL
Java_com_fainet_fcode_Pty_setPtyWindowSize(JNIEnv* env, jclass clazz, jint fd, jint rows, jint columns)
{
    (void) env;
    (void) clazz;
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) columns;
    ioctl(fd, TIOCSWINSZ, &size);
}

JNIEXPORT jint JNICALL
Java_com_fainet_fcode_Pty_waitFor(JNIEnv* env, jclass clazz, jint pid)
{
    (void) env;
    (void) clazz;
    int status = 0;
    while (waitpid((pid_t) pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}
