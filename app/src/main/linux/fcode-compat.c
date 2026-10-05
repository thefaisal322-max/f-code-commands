/*
 * fcode-compat: lets Alpine Linux start programs on 64-bit Intel/AMD Android devices
 * (Chromebooks, and the emulator the automatic tests run on).
 *
 * Android only lets an app use the system calls its own C library uses. On x86_64, Alpine's C
 * library (musl) uses three older calls Android does not allow -- fork, pause and mknod -- and
 * PRoot answers "Function not implemented" for them, so no command could be started at all.
 * This small library is loaded into every Linux program (LD_PRELOAD) and provides the three
 * functions on top of calls Android does allow.
 *
 * ARM phones do not need it: the ARM system call tables never had these old calls.
 *
 * It is built without any C library headers (see scripts/fetch-linux-env.sh), so the few
 * things it needs are declared here. They are all provided by musl when the library is loaded.
 */

typedef int pid_t;

int clone(int (*fn)(void *), void *stack, int flags, void *arg, ...);
int setjmp(void *env) __attribute__((returns_twice));
void longjmp(void *env, int value) __attribute__((noreturn));
int poll(void *fds, unsigned long count, int timeout);
int mknodat(int dirfd, const char *path, unsigned int mode, unsigned long dev);

#define SIGCHLD 17
#define AT_FDCWD (-100)

/* musl's jmp_buf is 200 bytes on x86_64; this leaves room to spare. */
typedef unsigned long jump_buffer[64];

static int back_to_fork(void *env)
{
    longjmp(env, 1);
}

/*
 * fork() through clone(). musl's clone() sets up the child's thread state exactly as its own
 * fork() does and then calls a function on a new stack; that function jumps back to where
 * fork() was called, in the child's own copy of the caller's stack.
 */
pid_t fork(void)
{
    jump_buffer env;
    char stack[16 * 1024] __attribute__((aligned(16)));

    if (setjmp(env)) return 0;   /* the child arrives here */
    return clone(back_to_fork, stack + sizeof stack, SIGCHLD, env);
}

int pause(void)
{
    return poll(0, 0, -1);   /* waits until a signal arrives, then fails with EINTR like pause() */
}

int mknod(const char *path, unsigned int mode, unsigned long dev)
{
    return mknodat(AT_FDCWD, path, mode, dev);
}
