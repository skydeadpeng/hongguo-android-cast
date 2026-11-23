// SPDX-License-Identifier: GPL-3.0-only
/*
 * Fault handling of the SDK adapter, exercised with real signals on an arm64 host and without the SDK
 * image: the image entry is replaced by a stand-in that keeps the call window open while other threads
 * fault or are signalled.
 *
 * A runtime handles its null-pointer and stack-overflow checks as faults that it fixes up and returns
 * from. Such a fault on another thread while a digest call is in progress has to reach the runtime's own
 * handler unchanged; it must never be re-raised on the runtime's behalf, which would turn an ordinary
 * exception into a dead process. A fault on the digest thread itself stays contained in the call.
 */
#include <signal.h>
// The adapter targets bionic, which names this code; macOS has the same value without the name.
#ifndef SI_TKILL
#define SI_TKILL (-1)
#endif
#include "sdk_digest.c"

#include <stdatomic.h>

static volatile int window_open, stop;
static atomic_int raised_seen, raised_handled, sent_seen, sent_handled, in_window, after_digest_fault;

// The runtime's own handler: a fault raised by an instruction is fixed up and returned from. A signal
// that was sent rather than raised is merely absorbed here, so the test can count that it arrived once.
static void runtime_handler(int number, siginfo_t *info, void *context)
{
    if (number != SIGSEGV || info->si_code <= 0 || info->si_code >= 0x80) { atomic_fetch_add(&sent_handled, 1); return; }
    atomic_fetch_add(&raised_handled, 1);
#if defined(__APPLE__)
    ((ucontext_t *)context)->uc_mcontext->__ss.__pc += 4;
#else
    ((ucontext_t *)context)->uc_mcontext.pc += 4;
#endif
}

static void install_runtime_handler(void)
{
    struct sigaction action;
    memset(&action, 0, sizeof action);
    action.sa_sigaction = runtime_handler;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSEGV, &action, NULL);
    sigaction(SIGBUS, &action, NULL);
    sigaction(SIGILL, &action, NULL);
}

// One load from an unmapped address: a 4-byte instruction, which is why the handler steps over 4 bytes.
static inline void faulting_load(void)
{
    __asm__ volatile("ldr x0, [%0]" : : "r"((uintptr_t)8) : "x0", "memory");
}

static void *raiser(void *unused)
{
    (void)unused;
    while (!stop) {
        if (!window_open) continue;
        atomic_fetch_add(&raised_seen, 1);
        faulting_load();
    }
    return NULL;
}

static void *sender(void *unused)
{
    (void)unused;
    while (!stop) {
        if (window_open) {
            atomic_fetch_add(&sent_seen, 1);
            pthread_kill(pthread_self(), SIGSEGV);
        }
        usleep(200);
    }
    return NULL;
}

// The stand-in for the image entry: lets the other thread fault or get signalled only while the call,
// and with it the adapter's handler, is in progress; records what the thread did meanwhile. It is
// entered with the argument registers the image would see.
static void stand_in(uintptr_t argument, uintptr_t selector, uintptr_t seconds)
{
    (void)argument;
    (void)selector;
    (void)seconds;
    int raised = atomic_load(&raised_seen), sent = atomic_load(&sent_seen);
    window_open = 1;
    for (int tick = 0; tick < 300; tick++) usleep(1000);
    window_open = 0;
    atomic_store(&in_window, (atomic_load(&raised_seen) - raised) + (atomic_load(&sent_seen) - sent));
}

// An image entry that faults: the fault is on the digest thread and must end the call, nothing else.
static void faulting_entry(uintptr_t argument, uintptr_t selector, uintptr_t seconds)
{
    (void)argument;
    (void)selector;
    (void)seconds;
    faulting_load();
    atomic_store(&after_digest_fault, 1);
}

// The adapter as it is after a successful load, with the entry replaced by a stand-in.
static void arm(void *entry)
{
    pthread_mutex_init(&g.lock, NULL);
    pthread_cond_init(&g.done, NULL);
    g.entry = entry;
    g.loaded = true;
}

static int runtime_handler_installed(void)
{
    struct sigaction current;
    sigaction(SIGSEGV, NULL, &current);
    return (current.sa_flags & SA_SIGINFO) && current.sa_sigaction == runtime_handler;
}

static volatile int probe_code = -999;
static void probe_handler(int number, siginfo_t *info, void *context) { (void)number; (void)context; probe_code = info->si_code; }

// True where the platform says whether a signal was sent (kill, raise) or raised by an instruction.
// Linux and Android do; macOS reports the same code for both, so that case cannot be told apart there.
static int sent_signals_distinguishable(void)
{
    struct sigaction action, previous;
    memset(&action, 0, sizeof action);
    action.sa_sigaction = probe_handler;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSEGV, &action, &previous);
    probe_code = -999;
    pthread_kill(pthread_self(), SIGSEGV);
    sigaction(SIGSEGV, &previous, NULL);
    return probe_code == SI_USER || probe_code == SI_QUEUE || probe_code == SI_TKILL;
}

// Runs one call with `thread_main` faulting or being signalled throughout it; `during` receives how
// often the thread did so. The call opens and closes its own window.
static int call_with(void *(*thread_main)(void *), int *during)
{
    pthread_t thread;
    int seen = atomic_load(&raised_seen) + atomic_load(&sent_seen);
    stop = 0;
    window_open = 0;
    pthread_create(&thread, NULL, thread_main, NULL);
    usleep(50000);
    static const uint8_t payload[SDK_PAYLOAD_SIZE] = {0};
    uint8_t out[SDK_DIGEST_SIZE];
    int status = sdk_core_digest(payload, 0, 1, out);
    stop = 1;
    pthread_join(thread, NULL);
    *during = atomic_load(&raised_seen) + atomic_load(&sent_seen) - seen;
    return status;
}

static int fail(const char *what)
{
    fprintf(stderr, "FAIL %s (raised seen %d handled %d, sent seen %d handled %d)\n", what, atomic_load(&raised_seen),
        atomic_load(&raised_handled), atomic_load(&sent_seen), atomic_load(&sent_handled));
    return 1;
}

int main(int argc, char **argv)
{
    int checks = 0, during = 0, status;
    bool child = argc > 1 && !strcmp(argv[1], "child");

    // In a process that has no handler of its own, a fault on another thread during a call is fatal
    // exactly as it would be without the adapter: the process dies of SIGSEGV. The runner checks that.
    if (child) {
        signal(SIGSEGV, SIG_DFL);
        arm(stand_in);
        pthread_t thread;
        window_open = 0;
        pthread_create(&thread, NULL, raiser, NULL);
        sdk_core_digest((const uint8_t *)"0000000000000000000000000000000000000000000000000000", 0, 1, (uint8_t[SDK_DIGEST_SIZE]){0});
        stop = 1;
        pthread_join(thread, NULL);
        fputs("survived a fault with no handler of its own\n", stderr);
        return 3;
    }

    install_runtime_handler();
    arm(stand_in);

    // 1. Faults raised on another thread throughout a call are each fixed up once by the runtime's
    //    handler, the call itself completes, and the disposition is the runtime's again afterwards.
    status = call_with(raiser, &during);
    if (status != SDK_E_SHAPE) { fprintf(stderr, "FAIL call status %d, expected %d\n", status, SDK_E_SHAPE); return 1; }
    checks++;
    if (during <= 0 || atomic_load(&in_window) <= 0) return fail("no fault reached the adapter during the call");
    checks++;
    if (atomic_load(&raised_handled) != atomic_load(&raised_seen) || atomic_load(&raised_handled) == 0) return fail("a raised fault was lost or handled twice");
    checks++;
    if (!runtime_handler_installed()) return fail("SIGSEGV disposition not restored after the call");
    checks++;

    // 2. Signals sent to another thread throughout a call each reach the runtime's handler once.
    int raised_before = atomic_load(&raised_handled);
    if (sent_signals_distinguishable()) {
        status = call_with(sender, &during);
        if (status != SDK_E_SHAPE) { fprintf(stderr, "FAIL call status %d with signals sent\n", status); return 1; }
        checks++;
        if (during <= 0 || atomic_load(&in_window) <= 0) return fail("no signal was sent during the call");
        checks++;
        if (atomic_load(&sent_handled) != atomic_load(&sent_seen) || atomic_load(&sent_handled) == 0) return fail("a sent signal was lost or delivered twice");
        checks++;
        if (atomic_load(&raised_handled) != raised_before) return fail("a sent signal was taken for a raised fault");
        checks++;
        if (!runtime_handler_installed()) return fail("SIGSEGV disposition not restored after sent signals");
        checks++;
    } else {
        // Nothing was sent, so this still has to hold exactly as it did.
        if (atomic_load(&raised_handled) != raised_before) return fail("the runtime's handler ran without a fault");
        puts("sent-signal case skipped: this platform does not tell a sent signal from a raised fault (run on Android or Linux)");
    }

    // 3. A fault on the digest thread ends that call and leaves the adapter and the runtime's
    //    handler as they were.
    raised_before = atomic_load(&raised_handled);
    g.entry = faulting_entry;
    status = sdk_core_digest((const uint8_t *)"0000000000000000000000000000000000000000000000000000", 0, 1, (uint8_t[SDK_DIGEST_SIZE]){0});
    if (status != SDK_E_FAULT || atomic_load(&after_digest_fault)) { fprintf(stderr, "FAIL digest thread fault: status %d, continued=%d\n", status, atomic_load(&after_digest_fault)); return 1; }
    checks++;
    if (atomic_load(&raised_handled) != raised_before) return fail("the runtime's handler was invoked for the digest thread");
    checks++;
    if (strncmp(sdk_core_detail(), "signal 11 at image+", 19) != 0) { fprintf(stderr, "FAIL detail: %s\n", sdk_core_detail()); return 1; }
    checks++;
    g.entry = stand_in;
    status = sdk_core_digest((const uint8_t *)"0000000000000000000000000000000000000000000000000000", 0, 1, (uint8_t[SDK_DIGEST_SIZE]){0});
    if (status != SDK_E_SHAPE || !sdk_core_ready()) return fail("adapter unusable after a contained fault");
    checks++;
    if (!runtime_handler_installed()) return fail("SIGSEGV disposition not restored after a contained fault");
    checks++;

    printf("SDK_FAULT_FORWARD_OK checks=%d raised=%d sent=%d\n", checks, atomic_load(&raised_seen), atomic_load(&sent_seen));
    return 0;
}
