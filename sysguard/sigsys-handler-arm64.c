/*
 * sigsys-handler-arm64.c — LD_PRELOAD shim that lets Bun-based apps (the
 * opencode CLI single-file binary) run inside the SemCode proot Linux env on
 * Android.
 *
 * Why this exists:
 *   Every Android app process carries a seccomp-bpf allowlist installed by
 *   Zygote. Any syscall outside that allowlist — close_range (436),
 *   epoll_pwait2 (441), openat2 (437), fchmodat2 (452), pidfd_open, … — is
 *   trapped with SECCOMP_RET_TRAP and delivered as a FATAL SIGSYS *before* the
 *   syscall can return (exit status 159, "Bad system call"). Normal ENOSYS
 *   fallbacks therefore never run: Bun is killed before main (close_range) or
 *   mid-loop (epoll_pwait2). The filter cannot be removed by userspace and it
 *   applies to every process the app forks — including the proot guest.
 *
 * The fix:
 *   Preload this library into every guest process. Its constructor installs a
 *   SIGSYS handler. When a seccomp trap fires (si_code == SYS_SECCOMP) the
 *   handler decrypts which syscall was attempted from siginfo.si_syscall and
 *   rewrites the arm64 return register (X0 in ucontext) to -ENOSYS. The trapped
 *   thread resumes after its `svc` as if the kernel answered "function not
 *   implemented", so Bun's existing ENOSYS fallbacks take over.
 *
 * The handler is async-signal-safe (raw syscalls only, no libc). Constructors
 * run before main, so the handler exists before Bun's own startup syscalls.
 *
 * Optional diagnostics: set SIGSYS_LOG=<guest path> to append one line per
 * trapped syscall to that file.
 *
 * Build (CI, cross-compile aarch64 linux glibc):
 *   aarch64-linux-gnu-gcc -shared -fPIC -O2 -o libsigsys-arm64.so \
 *       sysguard/sigsys-handler-arm64.c
 */

#define _GNU_SOURCE 1

#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

#if !defined(__aarch64__)
#error "this shim is arm64-only (Android app seccomp, x0 return register)"
#endif

/* ---- raw syscalls so the handler never touches libc ---- */

static inline long raw_syscall3(long n, long a, long b, long c) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x8 __asm__("x8") = n;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8)
                     : "memory", "cc");
    return x0;
}

#define __NR_read_arm64 63
#define __NR_write_arm64 64
#define __NR_openat_arm64 56

/* ---- optional trap log (fd opened by constructor, libc allowed there) ---- */

static int g_log_fd = -1;

static void log_blocked(int sysno) {
    if (g_log_fd < 0) return;
    static const char pre[] = "sigsys-trapped syscall=";
    char num[12];
    int n = 0;
    int v = sysno;
    if (v < 0) return;
    do {
        num[n++] = (char)('0' + v % 10);
        v /= 10;
    } while (v > 0 && n < (int)sizeof(num));

    char msg[64];
    int m = 0;
    for (int i = 0; i < (int)sizeof(pre) - 1 && m < (int)sizeof(msg); i++)
        msg[m++] = pre[i];
    for (int i = n - 1; i >= 0 && m < (int)sizeof(msg) - 1; i--)
        msg[m++] = num[i];
    msg[m++] = '\n';
    raw_syscall3(__NR_write_arm64, (long)g_log_fd, (long)(intptr_t)msg, (long)m);
}

/* ---- the handler ---- */

static void on_sigsys(int sig, siginfo_t *info, void *ucontext_v) {
    (void)sig;

    /* Only seccomp traps (si_code == SYS_SECCOMP) are ours to fix. A SIGSYS
     * sent by kill(2) leaves the thread interrupted at an arbitrary point —
     * nothing to repair — so leave the regs untouched and return. */
    if (info == NULL || info->si_code != SYS_SECCOMP) return;

    int sysno = (int)info->si_syscall;
    if (sysno > 0) log_blocked(sysno);

    ucontext_t *uc = (ucontext_t *)ucontext_v;
    /* arm64 mcontext_t.regs[] == X0..X30; syscall return lives in X0. */
    uc->uc_mcontext.regs[0] = (unsigned long)(intptr_t)(-38L /* -ENOSYS */);
}

/* ---- constructor: install handler (runs before main) ---- */

__attribute__((constructor))
static void install_sigsys_handler(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = on_sigsys;
    /* SA_SIGINFO => 3-arg handler with siginfo_t (si_syscall) + ucontext.
     * SA_RESTART keeps semantics the same as Bun's own forwarding handlers.
     * SA_NODEFER lets re-entrant traps (shouldn't happen) still be handled. */
    sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, NULL) != 0) return;

    const char *logp = getenv("SIGSYS_LOG");
    if (logp != NULL && *logp != '\0') {
        g_log_fd = open(logp, O_WRONLY | O_CREAT | O_APPEND, 0640);
    }
}