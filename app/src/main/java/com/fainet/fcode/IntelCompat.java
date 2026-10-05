package com.fainet.fcode;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes Alpine Linux work on 64-bit Intel/AMD Android devices (Chromebooks, and the emulator
 * the automatic tests run on). ARM phones never need this and it is never run there.
 *
 * Android only lets an app use the system calls its own C library uses. On x86_64, Alpine's C
 * library (musl) still uses the old calls from the 1990s -- fork, unlink, rename, link... --
 * which Android blocks. PRoot catches most of them and repeats them the modern way, but not
 * fork (so no program could be started at all), and repeating link/unlink/rename confuses its
 * own bookkeeping of hard links (files that could then not be deleted).
 *
 * So the C library's file is changed once, in place: every "old call" instruction is replaced
 * by a jump to a short routine that makes the modern call ARM uses anyway. The routines go
 * into the unused space at the end of the library's code. Nothing is changed unless the
 * instructions found are exactly the expected ones.
 */
final class IntelCompat {

    private IntelCompat() {
    }

    private static final int PAGE = 0x1000;

    /* What stands in the library for "make system call NR": mov $NR,%eax ; syscall */
    private static byte[] oldCall(int number) {
        return new byte[]{(byte) 0xb8, (byte) number, (byte) (number >> 8), 0, 0, 0x0f, 0x05};
    }

    /**
     * One replacement routine per old call. Each is entered by "call" with the old call's
     * arguments in the registers and, like a system call, changes only rax, rcx and r11.
     * They are assembled from this source (GNU assembler, AT&amp;T syntax):
     *
     * <pre>
     * fork:    push %rdi; push %rsi; mov $17,%edi; xor %esi,%esi; mov $56,%eax; syscall     # clone(SIGCHLD, 0)
     *          pop %rsi; pop %rdi; ret
     * unlink:  push %rdi; push %rsi; push %rdx; mov %rdi,%rsi; mov $-100,%edi; xor %edx,%edx
     *          mov $263,%eax; syscall; pop %rdx; pop %rsi; pop %rdi; ret                    # unlinkat(AT_FDCWD, path, 0)
     * rmdir:   the same with mov $0x200,%edx                                                # ... AT_REMOVEDIR
     * link:    push rdi,rsi,rdx,r10,r8; mov %rsi,%r10; mov %rdi,%rsi; mov $-100,%edi
     *          mov $-100,%edx; xor %r8d,%r8d; mov $265,%eax; syscall; pop ...; ret          # linkat
     * rename:  push rdi,rsi,rdx,r10; mov %rsi,%r10; mov %rdi,%rsi; mov $-100,%edi
     *          mov $-100,%edx; mov $264,%eax; syscall; pop ...; ret                         # renameat
     * symlink: push %rsi; push %rdx; mov %rsi,%rdx; mov $-100,%esi; mov $266,%eax; syscall  # symlinkat
     *          pop %rdx; pop %rsi; ret
     * mkdir, access, chmod:
     *          push rdi,rsi,rdx; mov %rsi,%rdx; mov %rdi,%rsi; mov $-100,%edi
     *          mov $258 / $269 / $268,%eax; syscall; pop ...; ret                           # mkdirat, faccessat, fchmodat
     * chown:   push rdi,rsi,rdx,r10,r8; mov %rdx,%r10; mov %rsi,%rdx; mov %rdi,%rsi
     *          mov $-100,%edi; xor %r8d,%r8d; mov $260,%eax; syscall; pop ...; ret          # fchownat
     * lchown:  the same with mov $0x100,%r8d                                                # ... AT_SYMLINK_NOFOLLOW
     * mknod:   push rdi,rsi,rdx,r10; mov %rdx,%r10; mov %rsi,%rdx; mov %rdi,%rsi
     *          mov $-100,%edi; mov $259,%eax; syscall; pop ...; ret                         # mknodat
     * pipe:    push %rsi; xor %esi,%esi; mov $293,%eax; syscall; pop %rsi; ret              # pipe2(fds, 0)
     * </pre>
     */
    private static final Object[][] ROUTINES = {
            // { old call number, machine code of the routine }
            {57, "5756bf1100000031f6b8380000000f055e5fc3"},
            {87, "5756524889febf9cffffff31d2b8070100000f055a5e5fc3"},
            {84, "5756524889febf9cffffffba00020000b8070100000f055a5e5fc3"},
            {86, "575652415241504989f24889febf9cffffffba9cffffff4531c0b8090100000f054158415a5a5e5fc3"},
            {82, "57565241524989f24889febf9cffffffba9cffffffb8080100000f05415a5a5e5fc3"},
            {88, "56524889f2be9cffffffb80a0100000f055a5ec3"},
            {83, "5756524889f24889febf9cffffffb8020100000f055a5e5fc3"},
            {21, "5756524889f24889febf9cffffffb80d0100000f055a5e5fc3"},
            {90, "5756524889f24889febf9cffffffb80c0100000f055a5e5fc3"},
            {92, "575652415241504989d24889f24889febf9cffffff4531c0b8040100000f054158415a5a5e5fc3"},
            {94, "575652415241504989d24889f24889febf9cffffff41b800010000b8040100000f054158415a5a5e5fc3"},
            {133, "57565241524989d24889f24889febf9cffffffb8030100000f05415a5a5e5fc3"},
            {22, "5631f6b8250100000f055ec3"},
    };

    private static final int FORK = 57;

    /*
     * Inside fstatat(): the branch that makes the old "lstat" call, and the branch right next
     * to it that makes the modern call with the same registers. The first is redirected to
     * the second.
     *   old:    mov $6,%eax ; mov %r9,%rdi ; mov %rdx,%rsi ; syscall
     *   modern: movslq %r8d,%rdi ; movslq %ecx,%r10 ; mov $262,%eax ; mov %r9,%rsi ; syscall
     */
    private static final byte[] LSTAT_OLD = hex("b8060000004c89cf4889d60f05");
    private static final byte[] LSTAT_MODERN = hex("4963f84c63d1b8060100004c89ce0f05");

    /*
     * pause(): calls the library's "system call with six zero arguments" helper with number 34.
     * Number 271 (ppoll) with the same zero arguments also waits for a signal, and is allowed.
     *   xor %r9d,%r9d ; xor %r8d,%r8d ; xor %ecx,%ecx ; push $0 ; xor %edx,%edx ; xor %esi,%esi ; mov $34,%edi ; call ...
     */
    private static final byte[] PAUSE_OLD = hex("4531c94531c031c96a0031d231f6bf22000000e8");
    private static final int PAUSE_NUMBER_AT = 15;

    /**
     * Changes the library file if it still makes the old calls. Safe to call on every start:
     * a library that is already changed, or that does not look as expected, is left alone.
     *
     * @return true when the file was changed just now
     */
    static boolean apply(File library) throws IOException {
        Path path = library.toPath();
        if (!Files.isRegularFile(path)) return false;
        byte[] data = Files.readAllBytes(path);
        ByteBuffer elf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // A 64-bit little-endian x86_64 ELF file?
        if (data.length < 64 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F'
                || data[4] != 2 || data[5] != 1 || elf.getShort(18) != 62) return false;

        long headersAt = elf.getLong(32);
        int headerSize = elf.getShort(54) & 0xffff;
        int headerCount = elf.getShort(56) & 0xffff;
        if (headerSize < 56 || headersAt <= 0 || headersAt + (long) headerSize * headerCount > data.length) return false;

        // The one piece of the file that holds code, and where the next piece starts
        int codeHeader = -1;
        long nextPieceAt = data.length;
        for (int i = 0; i < headerCount; i++) {
            int at = (int) headersAt + i * headerSize;
            if (elf.getInt(at) != 1) continue;                      // PT_LOAD
            if ((elf.getInt(at + 4) & 1) != 0) {                    // executable
                if (codeHeader >= 0) return false;                  // more than one: not the layout we know
                codeHeader = at;
            }
        }
        if (codeHeader < 0) return false;
        long codeAt = elf.getLong(codeHeader + 8);
        long codeSize = elf.getLong(codeHeader + 32);
        if (codeSize != elf.getLong(codeHeader + 40)) return false;   // size in the file must equal size in memory
        long codeEnd = codeAt + codeSize;
        if (codeAt < 0 || codeEnd > data.length) return false;
        for (int i = 0; i < headerCount; i++) {
            int at = (int) headersAt + i * headerSize;
            if (elf.getInt(at) != 1 || at == codeHeader) continue;
            long offset = elf.getLong(at + 8);
            if (offset >= codeEnd && offset < nextPieceAt) nextPieceAt = offset;
        }

        // Nothing to do unless the library still makes the old fork call, in exactly one place
        List<Integer> forkCalls = find(data, oldCall(FORK), (int) codeAt, (int) codeEnd);
        if (forkCalls.size() != 1) return false;

        // The unused space after the code, up to the end of its last memory page
        long spaceEnd = Math.min((codeEnd + PAGE - 1) / PAGE * PAGE, nextPieceAt);
        for (long i = codeEnd; i < spaceEnd; i++) {
            if (data[(int) i] != 0) return false;                   // something is there after all
        }

        int writeAt = (int) codeEnd;
        for (Object[] routine : ROUTINES) {
            int number = (Integer) routine[0];
            byte[] code = hex((String) routine[1]);
            List<Integer> calls = find(data, oldCall(number), (int) codeAt, (int) codeEnd);
            if (calls.isEmpty()) continue;
            if (writeAt + code.length > spaceEnd) {
                if (number == FORK) return false;                   // without fork there is no point
                continue;
            }
            System.arraycopy(code, 0, data, writeAt, code.length);
            for (int call : calls) {
                data[call] = (byte) 0xe8;                           // call routine
                elf.putInt(call + 1, writeAt - (call + 5));
                data[call + 5] = 0x66;                              // 2-byte no-op in place of "syscall"
                data[call + 6] = (byte) 0x90;
            }
            writeAt += code.length;
        }

        List<Integer> lstatOld = find(data, LSTAT_OLD, (int) codeAt, (int) codeEnd);
        List<Integer> lstatModern = find(data, LSTAT_MODERN, (int) codeAt, (int) codeEnd);
        if (lstatOld.size() == 1 && lstatModern.size() == 1) {
            int from = lstatOld.get(0);
            data[from] = (byte) 0xe9;                               // jmp to the modern branch
            elf.putInt(from + 1, lstatModern.get(0) - (from + 5));
        }

        List<Integer> pause = find(data, PAUSE_OLD, (int) codeAt, (int) codeEnd);
        if (pause.size() == 1) elf.putInt(pause.get(0) + PAUSE_NUMBER_AT, 271);

        // The code piece now includes the routines
        elf.putLong(codeHeader + 32, writeAt - codeAt);
        elf.putLong(codeHeader + 40, writeAt - codeAt);

        // Swapped in whole, so a program that is running keeps the file it loaded
        Path fresh = path.resolveSibling(path.getFileName() + ".fcode-new");
        Files.write(fresh, data);
        Files.setPosixFilePermissions(fresh, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.move(fresh, path, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** Every position in data[from, to) where {@code what} stands. */
    private static List<Integer> find(byte[] data, byte[] what, int from, int to) {
        List<Integer> found = new ArrayList<>();
        outer:
        for (int i = from; i + what.length <= to; i++) {
            for (int j = 0; j < what.length; j++) {
                if (data[i + j] != what[j]) continue outer;
            }
            found.add(i);
        }
        return found;
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    /** For trying it out on a computer: java IntelCompat FILE */
    public static void main(String[] args) throws IOException {
        System.out.println(apply(new File(args[0])) ? "changed" : "left alone");
    }
}
