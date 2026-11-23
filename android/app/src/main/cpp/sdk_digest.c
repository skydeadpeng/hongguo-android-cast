// SPDX-License-Identifier: GPL-3.0-only
/*
 * Native adapter for the pinned Hongguo SDK digest core (v04.10.03-ml-android, arm64).
 *
 * On-device counterpart of the bounded Unicorn adapter the upstream desktop project uses, with the
 * same boundaries:
 *  - the ELF image is mapped by hand; its initialisers and JNI_OnLoad never run;
 *  - every import resolves to an explicit stub: about twenty are modelled, the rest fail the call;
 *  - there is no Java environment, no Android property, no file and no real syscall;
 *  - execution leaves the image the moment its dispatcher is about to enter the digest core.
 * Nothing of the SDK is embedded here; the caller verifies the pinned image hash before loading it.
 */
#include "sdk_digest.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

// Minimal ELF64 layout, kept private: the adapter only reads these records from the supplied image.
struct sdk_ehdr { unsigned char e_ident[16]; uint16_t e_type, e_machine; uint32_t e_version; uint64_t e_entry, e_phoff, e_shoff; uint32_t e_flags; uint16_t e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx; };
struct sdk_phdr { uint32_t p_type, p_flags; uint64_t p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align; };
struct sdk_shdr { uint32_t sh_name, sh_type; uint64_t sh_flags, sh_addr, sh_offset, sh_size; uint32_t sh_link, sh_info; uint64_t sh_addralign, sh_entsize; };
struct sdk_sym { uint32_t st_name; unsigned char st_info, st_other; uint16_t st_shndx; uint64_t st_value, st_size; };
struct sdk_rela { uint64_t r_offset, r_info; int64_t r_addend; };
#define SDK_ELF_MAGIC "\x7f" "ELF"
#define SDK_EI_CLASS 4
#define SDK_ELFCLASS64 2
#define SDK_EI_DATA 5
#define SDK_ELFDATA2LSB 1
#define SDK_ET_DYN 3
#define SDK_EM_AARCH64 183
#define SDK_PT_LOAD 1
#define SDK_STT_OBJECT 1
#define SDK_R_TYPE(info) ((uint32_t)(info))
#define SDK_R_SYM(info) ((uint64_t)(info) >> 32)
#define SDK_ST_TYPE(info) ((info) & 0xf)

#define SDK_NR_MEMFD_CREATE 279
#define SDK_MFD_CLOEXEC 1
#define SDK_ASHMEM_SET_NAME 0x41007701

// Offsets below belong to the single pinned image (see SDK_IMAGE_SIZE). Each patched instruction is
// checked against its expected word before anything is written, so a different build is rejected.
#define SDK_SPAN 0x480000u
#define SDK_TEXT_END 0x3c4000u
#define SDK_TRAMP_AT 0x470000u
#define SDK_TRAMP_ROOM 0x1000u
#define SDK_BASE_HINT 0x10000000u
#define SDK_OFF_ENTRY 0x2aac34u
#define SDK_OFF_CORE 0xecc40u
#define SDK_OFF_VTABLE 0x3c8288u
#define SDK_OFF_VM_GLOBAL 0x3e8368u
#define SDK_OFF_DISPATCH 0x17dc48u
#define SDK_WORD_DISPATCH 0xf85f03b1u /* ldur x17, [x29, #-0x10] */
#define SDK_OFF_LOAD 0x1851d4u
#define SDK_WORD_LOAD 0xf8716a0fu /* ldr x15, [x16, x17] */
#define SDK_OFF_SVC 0x29b22cu
#define SDK_WORD_SVC 0xd4000001u /* svc #0 */
#define SDK_STACK_SIZE (8u << 20)
#define SDK_CALL_SECONDS 5
#define SDK_ONCE_MAX 32
#define SDK_KEY_MAX 64
#define SDK_UNAVAILABLE_MAX 256
#define SDK_NAME_POOL 8192

extern const uint8_t sdk_tramp_start[], sdk_tramp_end[];
extern const uint8_t sdk_tramp_dispatch[], sdk_tramp_load[], sdk_tramp_svc[];
extern const uint64_t sdk_lit_core[], sdk_lit_dispatch_resume[], sdk_lit_core_handler[];
extern const uint64_t sdk_lit_load_resume[], sdk_lit_svc_resume[];

// AArch64 relocation kinds, spelled out because not every platform's <elf.h> carries them.
#define SDK_RELOC_ABS64 257u
#define SDK_RELOC_GLOB_DAT 1025u
#define SDK_RELOC_JUMP_SLOT 1026u
#define SDK_RELOC_RELATIVE 1027u

// The image's own byte-string class: vtable, capacity, length, data pointer.
struct sdk_string {
    const void *vtable;
    uint32_t capacity;
    uint32_t length;
    const char *data;
};

static struct {
    bool loaded, broken;
    uintptr_t base;
    void *entry;
    int errno_zero;
    uintptr_t vm_object, vm_table[8];
    uint32_t once_controls[SDK_ONCE_MAX];
    size_t once_count;
    unsigned keys[SDK_KEY_MAX];
    uintptr_t key_values[SDK_KEY_MAX];
    size_t key_count;
    size_t object_count, unavailable_count, pool_used;
    pthread_mutex_t lock;
    pthread_cond_t done;
    bool finished;
    uint8_t argument[SDK_PAYLOAD_SIZE + 1];
    uintptr_t selector, seconds;
    struct sdk_string *result_slot;
    volatile int status;
    char detail[96];
    const char *unavailable_name[SDK_UNAVAILABLE_MAX];
    char name_pool[SDK_NAME_POOL];
} g;

static sigjmp_buf g_resume;
static sig_atomic_t g_in_call;
static pthread_t g_call_thread;
static uint8_t g_output[SDK_DIGEST_SIZE];
static uint8_t g_object_scratch[SDK_UNAVAILABLE_MAX * 128];
static struct sigaction g_previous[3];
static const int g_fault_signals[] = {SIGSEGV, SIGBUS, SIGILL};

// ---- failure exits: only ever reached from the digest thread while a call is in progress ----

__attribute__((noreturn, format(printf, 2, 3)))
static void sdk_abort_call(int status, const char *format, ...)
{
    va_list arguments;
    va_start(arguments, format);
    vsnprintf(g.detail, sizeof g.detail, format, arguments);
    va_end(arguments);
    g.status = status;
    siglongjmp(g_resume, 1);
}

// ---- modelled imports: exactly what the emulator exposes, nothing from the host environment ----

static uintptr_t sdk_identity(void) { return 1; }               // gettid, getpid: one synthetic thread
static uintptr_t sdk_not_found(void) { return 0; }              // __system_property_find, getenv
static uintptr_t sdk_noop(void) { return 0; }                   // mutex, condvar, thread_atexit
static uintptr_t sdk_errno(void) { return (uintptr_t)&g.errno_zero; }

static uintptr_t sdk_vm_environment(uintptr_t vm, uintptr_t *environment, uintptr_t thread)
{
    (void)vm;
    (void)thread;
    if (environment) *environment = 0;
    return 0xffffffffu; // JNI_ERR: no JavaVM is attached, modelled as the emulator does
}

static uintptr_t sdk_clock_gettime(uintptr_t clock, struct timespec *out)
{
    if (!out || clock > 1) sdk_abort_call(SDK_E_IMPORT, "clock %llu", (unsigned long long)clock);
    out->tv_sec = clock == 0 ? 1700000000 : 1;
    out->tv_nsec = 0;
    return 0;
}

static uintptr_t sdk_syscall(uintptr_t number, uintptr_t a, uintptr_t b, uintptr_t c)
{
    (void)a;
    (void)b;
    (void)c;
    if (number == 172 || number == 178) return 1; // getpid, gettid
    sdk_abort_call(SDK_E_IMPORT, "syscall %llu", (unsigned long long)number);
}

static uintptr_t sdk_pthread_key_create(unsigned *key)
{
    if (!key || g.key_count >= SDK_KEY_MAX) sdk_abort_call(SDK_E_IMPORT, "thread key");
    g.keys[g.key_count] = (unsigned)g.key_count + 1;
    g.key_values[g.key_count] = 0;
    *key = g.keys[g.key_count++];
    return 0;
}

static uintptr_t sdk_pthread_setspecific(unsigned key, const void *value)
{
    for (size_t index = 0; index < g.key_count; index++)
        if (g.keys[index] == key) { g.key_values[index] = (uintptr_t)value; return 0; }
    sdk_abort_call(SDK_E_IMPORT, "unknown thread key %u", key);
}

static uintptr_t sdk_pthread_getspecific(unsigned key)
{
    for (size_t index = 0; index < g.key_count; index++)
        if (g.keys[index] == key) return g.key_values[index];
    sdk_abort_call(SDK_E_IMPORT, "unknown thread key %u", key);
}

static uintptr_t sdk_pthread_once(uint32_t *control, void (*initialise)(void))
{
    bool done = false;
    if (!control || !initialise) sdk_abort_call(SDK_E_IMPORT, "pthread_once");
    for (size_t index = 0; index < g.once_count; index++) done |= g.once_controls[index] == (uint32_t)(uintptr_t)control;
    if (!done) {
        if (g.once_count >= SDK_ONCE_MAX) sdk_abort_call(SDK_E_IMPORT, "pthread_once table");
        g.once_controls[g.once_count++] = (uint32_t)(uintptr_t)control;
        initialise();
        *control = 2;
    }
    return 0;
}

static void *sdk_memcpy_chk(void *to, const void *from, size_t count, size_t to_length)
{
    if (count > to_length) sdk_abort_call(SDK_E_IMPORT, "checked copy");
    return memcpy(to, from, count);
}

static void *sdk_memmove_chk(void *to, const void *from, size_t count, size_t to_length)
{
    if (count > to_length) sdk_abort_call(SDK_E_IMPORT, "checked move");
    return memmove(to, from, count);
}

static void *sdk_memset_chk(void *to, int value, size_t count, size_t to_length)
{
    if (count > to_length) sdk_abort_call(SDK_E_IMPORT, "checked set");
    return memset(to, value, count);
}

static uintptr_t sdk_strlen_chk(const char *text, size_t text_length)
{
    size_t length = strlen(text);
    if (length >= text_length) sdk_abort_call(SDK_E_IMPORT, "checked length");
    return length;
}

static uintptr_t sdk_thread_atexit(const void *a, const void *b, const void *c)
{
    (void)a;
    (void)b;
    (void)c;
    return 0;
}

struct sdk_named { const char *name; void *address; };

#define SDK_STUB(function) (void *)(uintptr_t)&function

static const struct sdk_named g_modelled[] = {
    {"malloc", SDK_STUB(malloc)}, {"calloc", SDK_STUB(calloc)}, {"realloc", SDK_STUB(realloc)}, {"free", SDK_STUB(free)},
    {"memcpy", SDK_STUB(memcpy)}, {"memmove", SDK_STUB(memmove)}, {"memset", SDK_STUB(memset)}, {"memchr", SDK_STUB(memchr)},
    {"strlen", SDK_STUB(strlen)}, {"memcmp", SDK_STUB(memcmp)},
    {"__memcpy_chk", SDK_STUB(sdk_memcpy_chk)}, {"__memmove_chk", SDK_STUB(sdk_memmove_chk)},
    {"__memset_chk", SDK_STUB(sdk_memset_chk)}, {"__strlen_chk", SDK_STUB(sdk_strlen_chk)},
    {"gettid", SDK_STUB(sdk_identity)}, {"getpid", SDK_STUB(sdk_identity)},
    {"__system_property_find", SDK_STUB(sdk_not_found)}, {"getenv", SDK_STUB(sdk_not_found)},
    {"__errno", SDK_STUB(sdk_errno)}, {"clock_gettime", SDK_STUB(sdk_clock_gettime)}, {"syscall", SDK_STUB(sdk_syscall)},
    {"pthread_mutex_lock", SDK_STUB(sdk_noop)}, {"pthread_mutex_unlock", SDK_STUB(sdk_noop)},
    {"pthread_cond_broadcast", SDK_STUB(sdk_noop)}, {"__cxa_thread_atexit_impl", SDK_STUB(sdk_thread_atexit)},
    {"pthread_once", SDK_STUB(sdk_pthread_once)}, {"pthread_key_create", SDK_STUB(sdk_pthread_key_create)},
    {"pthread_setspecific", SDK_STUB(sdk_pthread_setspecific)}, {"pthread_getspecific", SDK_STUB(sdk_pthread_getspecific)},
};

__attribute__((noreturn)) static void sdk_core_reached(void)
{
    const struct sdk_string *slot = g.result_slot;
    if (!slot || slot->length != SDK_DIGEST_SIZE || slot->capacity < SDK_DIGEST_SIZE || !slot->data
        || (uintptr_t)slot->data < 0x1000) {
        g.status = SDK_E_SHAPE;
    } else {
        memcpy(g_output, slot->data, SDK_DIGEST_SIZE);
        g.status = SDK_OK;
    }
    siglongjmp(g_resume, 1);
}

__attribute__((noreturn)) static void sdk_unavailable(uintptr_t index)
{
    const char *name = index < g.unavailable_count && g.unavailable_name[index] ? g.unavailable_name[index] : "?";
    sdk_abort_call(SDK_E_IMPORT, "import %s", name);
}

// ---- loading ----

static uint32_t sdk_word(const uint8_t *image, size_t offset)
{
    uint32_t word = 0;
    memcpy(&word, image + offset, sizeof word);
    return word;
}

static const char *sdk_pool(const char *name)
{
    size_t length = strlen(name) + 1;
    if (length > sizeof g.name_pool - g.pool_used) return NULL;
    char *copy = g.name_pool + g.pool_used;
    memcpy(copy, name, length);
    g.pool_used += length;
    return copy;
}

static uint32_t sdk_branch(uintptr_t from, uintptr_t to)
{
    int64_t delta = (int64_t)to - (int64_t)from;
    if (delta & 3 || delta < -(1 << 27) || delta >= (1 << 27)) return 0;
    return 0x14000000u | ((uint32_t)(delta >> 2) & 0x03ffffffu);
}

static void *sdk_import(const char *name, unsigned type, uint8_t *entry)
{
    for (size_t index = 0; index < sizeof g_modelled / sizeof g_modelled[0]; index++)
        if (!strcmp(g_modelled[index].name, name)) return g_modelled[index].address;
    if (type == SDK_STT_OBJECT) return g_object_scratch + 128 * (g.object_count++ % SDK_UNAVAILABLE_MAX);
    if (g.unavailable_count >= SDK_UNAVAILABLE_MAX || !entry) return SDK_STUB(sdk_unavailable);
    size_t index = g.unavailable_count++;
    g.unavailable_name[index] = sdk_pool(name);
    // movz x16, #index ; ldr x17, entry+16 ; br x17 ; nop ; literal -> sdk_unavailable
    uint32_t *code = (uint32_t *)entry;
    code[0] = 0xd2800010u | ((uint32_t)index << 5);
    code[1] = 0x58000071u;
    code[2] = 0xd61f0220u;
    code[3] = 0xd503201fu;
    *(uint64_t *)&code[4] = (uint64_t)(uintptr_t)&sdk_unavailable;
    return entry;
}

// Apps may not execute anonymous memory on current Android: a memfd (ashmem before it) is the
// supported carrier. Both give file-backed pages that stay W^X: text RX, data RW.
static int sdk_open_carrier(int memfd)
{
    if (memfd) return (int)syscall(SDK_NR_MEMFD_CREATE, "hongguo-sdk", SDK_MFD_CLOEXEC);
    int fd = open("/dev/ashmem", O_RDWR | O_CLOEXEC);
    if (fd >= 0) ioctl(fd, SDK_ASHMEM_SET_NAME, "hongguo-sdk");
    return fd;
}

static int sdk_exec_backing(int carrier)
{
    int fd = sdk_open_carrier(carrier ? 1 : 0);
    if (fd < 0) fd = sdk_open_carrier(carrier ? 0 : 1);
    return fd;
}

int sdk_core_load(const uint8_t *image, size_t size, int carrier)
{
    if (g.loaded) return SDK_OK;
    g.detail[0] = 0;
    g.object_count = g.unavailable_count = g.pool_used = 0;
#if !defined(__aarch64__)
    (void)image;
    (void)size;
    (void)carrier;
    snprintf(g.detail, sizeof g.detail, "arm64 only");
    return SDK_E_UNSUPPORTED;
#else
    if (!image || size != SDK_IMAGE_SIZE) { snprintf(g.detail, sizeof g.detail, "image size"); return SDK_E_ARGUMENT; }
    const struct sdk_ehdr *header = (const struct sdk_ehdr *)image;
    if (memcmp(header->e_ident, SDK_ELF_MAGIC, 4) || header->e_ident[SDK_EI_CLASS] != SDK_ELFCLASS64
        || header->e_ident[SDK_EI_DATA] != SDK_ELFDATA2LSB || header->e_type != SDK_ET_DYN || header->e_machine != SDK_EM_AARCH64
        || header->e_phentsize != sizeof(struct sdk_phdr) || header->e_shentsize != sizeof(struct sdk_shdr)
        || header->e_phoff + (uint64_t)header->e_phnum * sizeof(struct sdk_phdr) > size
        || header->e_shoff + (uint64_t)header->e_shnum * sizeof(struct sdk_shdr) > size
        || header->e_shstrndx >= header->e_shnum
        || sdk_word(image, SDK_OFF_DISPATCH) != SDK_WORD_DISPATCH || sdk_word(image, SDK_OFF_LOAD) != SDK_WORD_LOAD
        || sdk_word(image, SDK_OFF_SVC) != SDK_WORD_SVC) {
        snprintf(g.detail, sizeof g.detail, "not the pinned image");
        return SDK_E_IMAGE;
    }

    int fd = sdk_exec_backing(carrier);
    void *mapped = MAP_FAILED;
    if (fd >= 0) {
        if (ftruncate(fd, SDK_SPAN) != 0) { close(fd); snprintf(g.detail, sizeof g.detail, "backing size"); return SDK_E_MEMORY; }
        // Reserve first, then replace that exact range, so no existing mapping is ever clobbered.
        void *reserved = mmap((void *)SDK_BASE_HINT, SDK_SPAN, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (reserved != MAP_FAILED) {
            uintptr_t at = (uintptr_t)reserved;
            munmap(reserved, SDK_SPAN);
            mapped = mmap((void *)at, SDK_SPAN, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0);
        }
        if (mapped == MAP_FAILED) mapped = mmap(NULL, SDK_SPAN, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    } else {
        // No file-backed carrier: map writable, fill and patch, then switch text to read-only executable.
        mapped = mmap((void *)SDK_BASE_HINT, SDK_SPAN, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (mapped == MAP_FAILED) mapped = mmap(NULL, SDK_SPAN, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    }
    if (mapped == MAP_FAILED) {
        if (fd >= 0) close(fd);
        snprintf(g.detail, sizeof g.detail, "executable mapping: %s", strerror(errno));
        return SDK_E_MEMORY;
    }
    const uintptr_t base = (uintptr_t)mapped;

    const struct sdk_phdr *segments = (const struct sdk_phdr *)(image + header->e_phoff);
    const struct sdk_shdr *sections = (const struct sdk_shdr *)(image + header->e_shoff);
    const struct sdk_shdr *names = &sections[header->e_shstrndx];
    const struct sdk_shdr *found[4] = {0};
    static const char *const wanted[4] = {".dynsym", ".dynstr", ".rela.dyn", ".rela.plt"};
    int status = SDK_OK;
    for (unsigned index = 0; index < header->e_phnum && status == SDK_OK; index++) {
        const struct sdk_phdr *segment = &segments[index];
        if (segment->p_type != SDK_PT_LOAD) continue;
        if (segment->p_offset + segment->p_filesz > size || segment->p_vaddr + segment->p_memsz > SDK_TRAMP_AT) { status = SDK_E_IMAGE; break; }
        memcpy((void *)(base + segment->p_vaddr), image + segment->p_offset, segment->p_filesz);
    }
    for (unsigned index = 0; index < header->e_shnum && status == SDK_OK; index++) {
        const char *name = (const char *)(image + names->sh_offset + sections[index].sh_name);
        for (unsigned slot = 0; slot < 4; slot++)
            if (!strcmp(name, wanted[slot])) found[slot] = &sections[index];
    }
    for (unsigned slot = 0; slot < 4 && status == SDK_OK; slot++)
        if (!found[slot] || found[slot]->sh_offset + found[slot]->sh_size > size) status = SDK_E_IMAGE;
    if (status == SDK_OK && (found[0]->sh_entsize != sizeof(struct sdk_sym) || found[2]->sh_entsize != sizeof(struct sdk_rela)
        || found[3]->sh_entsize != sizeof(struct sdk_rela))) status = SDK_E_IMAGE;
    if (status == SDK_OK) {
        const struct sdk_sym *symbols = (const struct sdk_sym *)(image + found[0]->sh_offset);
        size_t symbol_count = found[0]->sh_size / sizeof(struct sdk_sym);
        const char *strings = (const char *)(image + found[1]->sh_offset);
        size_t strings_end = found[1]->sh_size;
        uint8_t *tramp = (uint8_t *)(base + SDK_TRAMP_AT);
        size_t blob = (size_t)(sdk_tramp_end - sdk_tramp_start);
        uint8_t *stubs = tramp + ((blob + 15) & ~(size_t)15);
        for (unsigned pass = 0; pass < 2 && status == SDK_OK; pass++) {
            const uint8_t *cursor = image + found[2 + pass]->sh_offset;
            const uint8_t *end = cursor + found[2 + pass]->sh_size;
            for (; cursor + sizeof(struct sdk_rela) <= end && status == SDK_OK; cursor += sizeof(struct sdk_rela)) {
                struct sdk_rela relocation;
                memcpy(&relocation, cursor, sizeof relocation);
                uint32_t type = SDK_R_TYPE(relocation.r_info);
                if (relocation.r_offset < SDK_TEXT_END || relocation.r_offset + 8 > SDK_TRAMP_AT) { status = SDK_E_IMAGE; break; }
                uint64_t *slot = (uint64_t *)(base + relocation.r_offset);
                if (type == SDK_RELOC_RELATIVE) { *slot = base + (uint64_t)relocation.r_addend; continue; }
                if (type != SDK_RELOC_GLOB_DAT && type != SDK_RELOC_JUMP_SLOT && type != SDK_RELOC_ABS64) { status = SDK_E_UNSUPPORTED; break; }
                size_t symbol = SDK_R_SYM(relocation.r_info);
                if (symbol >= symbol_count) { status = SDK_E_IMAGE; break; }
                if (symbols[symbol].st_shndx != 0) { *slot = base + symbols[symbol].st_value + (uint64_t)relocation.r_addend; continue; }
                if (symbols[symbol].st_name >= strings_end) { status = SDK_E_IMAGE; break; }
                size_t length = strnlen(strings + symbols[symbol].st_name, strings_end - symbols[symbol].st_name);
                if (length == strings_end - symbols[symbol].st_name) { status = SDK_E_IMAGE; break; }
                if (!length) { *slot = 0; continue; } // the null symbol
                if (stubs + 24 > tramp + SDK_TRAMP_ROOM) { status = SDK_E_UNSUPPORTED; break; }
                *slot = (uint64_t)(uintptr_t)sdk_import(strings + symbols[symbol].st_name, SDK_ST_TYPE(symbols[symbol].st_info), stubs);
                if (*slot == (uint64_t)(uintptr_t)stubs) stubs += 24;
            }
        }
        if (status == SDK_OK) {
            memcpy(tramp, sdk_tramp_start, blob);
            *(uint64_t *)(tramp + ((const uint8_t *)sdk_lit_core - sdk_tramp_start)) = base + SDK_OFF_CORE;
            *(uint64_t *)(tramp + ((const uint8_t *)sdk_lit_dispatch_resume - sdk_tramp_start)) = base + SDK_OFF_DISPATCH + 4;
            *(uint64_t *)(tramp + ((const uint8_t *)sdk_lit_core_handler - sdk_tramp_start)) = (uintptr_t)&sdk_core_reached;
            *(uint64_t *)(tramp + ((const uint8_t *)sdk_lit_load_resume - sdk_tramp_start)) = base + SDK_OFF_LOAD + 4;
            *(uint64_t *)(tramp + ((const uint8_t *)sdk_lit_svc_resume - sdk_tramp_start)) = base + SDK_OFF_SVC + 4;
            uint32_t dispatch = sdk_branch(base + SDK_OFF_DISPATCH, (uintptr_t)sdk_tramp_dispatch - (uintptr_t)sdk_tramp_start + (uintptr_t)tramp);
            uint32_t load = sdk_branch(base + SDK_OFF_LOAD, (uintptr_t)sdk_tramp_load - (uintptr_t)sdk_tramp_start + (uintptr_t)tramp);
            uint32_t service = sdk_branch(base + SDK_OFF_SVC, (uintptr_t)sdk_tramp_svc - (uintptr_t)sdk_tramp_start + (uintptr_t)tramp);
            if (!dispatch || !load || !service) status = SDK_E_UNSUPPORTED;
            else {
                memcpy((void *)(base + SDK_OFF_DISPATCH), &dispatch, sizeof dispatch);
                memcpy((void *)(base + SDK_OFF_LOAD), &load, sizeof load);
                memcpy((void *)(base + SDK_OFF_SVC), &service, sizeof service);
            }
        }
    }
    if (status != SDK_OK) {
        munmap(mapped, SDK_SPAN);
        if (fd >= 0) close(fd);
        if (!g.detail[0]) snprintf(g.detail, sizeof g.detail, "image structure %d", status);
        return status;
    }

    // No Java environment: the SDK sees a JavaVM whose GetEnv/AttachCurrentThread answer JNI_ERR.
    // Layout is the JNI one it dereferences: *global -> JavaVM, *JavaVM -> function table.
    uintptr_t vm_global = *(uintptr_t *)(base + SDK_OFF_VM_GLOBAL);
    if (vm_global < base + SDK_TEXT_END || vm_global + 8 > base + SDK_TRAMP_AT) status = SDK_E_IMAGE;
    g.vm_object = (uintptr_t)&g.vm_table[0];
    g.vm_table[4] = (uintptr_t)&sdk_vm_environment;
    g.vm_table[6] = (uintptr_t)&sdk_vm_environment;
    if (status == SDK_OK) *(uintptr_t *)vm_global = (uintptr_t)&g.vm_object;
    if (status != SDK_OK) {
        munmap(mapped, SDK_SPAN);
        if (fd >= 0) close(fd);
        snprintf(g.detail, sizeof g.detail, "JavaVM slot");
        return status;
    }
    g.base = base;
    g.entry = (void *)(base + SDK_OFF_ENTRY);
    __builtin___clear_cache((char *)base, (char *)(base + SDK_SPAN));
    if (fd >= 0) {
        // File-backed pages: re-map text and the trampoline page read-only executable from the carrier.
        if (mmap((void *)base, SDK_TEXT_END, PROT_READ | PROT_EXEC, MAP_SHARED | MAP_FIXED, fd, 0) == MAP_FAILED
            || mmap((void *)(base + SDK_TRAMP_AT), 0x1000, PROT_READ | PROT_EXEC, MAP_SHARED | MAP_FIXED, fd, SDK_TRAMP_AT) == MAP_FAILED)
            status = SDK_E_MEMORY;
    } else {
        if (mprotect((void *)base, SDK_TEXT_END, PROT_READ | PROT_EXEC) != 0
            || mprotect((void *)(base + SDK_TEXT_END), SDK_TRAMP_AT - SDK_TEXT_END, PROT_READ | PROT_WRITE) != 0
            || mprotect((void *)(base + SDK_TRAMP_AT), 0x1000, PROT_READ | PROT_EXEC) != 0
            || mprotect((void *)(base + SDK_TRAMP_AT + 0x1000), SDK_SPAN - SDK_TRAMP_AT - 0x1000, PROT_NONE) != 0) status = SDK_E_MEMORY;
    }
    if (fd >= 0) close(fd);
    if (status != SDK_OK) {
        munmap(mapped, SDK_SPAN);
        snprintf(g.detail, sizeof g.detail, "protection: %s", strerror(errno));
        return status;
    }
    pthread_mutex_init(&g.lock, NULL);
    pthread_cond_init(&g.done, NULL);
    g.loaded = true;
    return SDK_OK;
#endif
}

// ---- calling ----

static uintptr_t sdk_fault_pc(void *context)
{
#if defined(__ANDROID__)
    return (uintptr_t)((const ucontext_t *)context)->uc_mcontext.pc;
#else
    (void)context;
    return 0;
#endif
}

static void sdk_fault(int number, siginfo_t *info, void *context)
{
    // A fault on the digest thread is the image's doing, wherever it landed.
    if (g_in_call && pthread_equal(pthread_self(), g_call_thread)) {
        uintptr_t pc = sdk_fault_pc(context);
        snprintf(g.detail, sizeof g.detail, "signal %d at image+%#x", number, (unsigned)(pc >= g.base ? pc - g.base : pc));
        g.status = SDK_E_FAULT;
        siglongjmp(g_resume, 2);
    }
    struct sigaction *previous = NULL;
    for (size_t index = 0; index < 3; index++) if (g_fault_signals[index] == number) previous = &g_previous[index];
    if (!previous) { signal(number, SIG_DFL); raise(number); return; }
    // Not ours: the disposition the process had comes back first, so a fault never reaches this
    // handler twice. The runtime handles its own null-pointer and stack-overflow checks as faults
    // that are fixed up and returned from, so nothing may be re-raised on its behalf.
    sigaction(number, previous, NULL);
    bool sent = !info || info->si_code == SI_USER || info->si_code == SI_QUEUE || info->si_code == SI_TKILL;
    // Raised by the faulting instruction: returning re-runs it, and the same fault is delivered
    // again to the restored disposition, exactly as it would have been without this handler.
    if (!sent) return;
    // Sent with kill or raise instead: nothing re-runs it, so it is handed over here.
    if (previous->sa_flags & SA_SIGINFO) previous->sa_sigaction(number, info, context);
    else if (previous->sa_handler == SIG_DFL) { signal(number, SIG_DFL); raise(number); }
}

// The image takes (argument, selector, seconds) in x0-x2 and writes its byte-string through the
// address in x8. Registers are set explicitly so no compiler decision about a 24-byte return value
// can redirect that address anywhere else.
static __attribute__((noinline)) void sdk_invoke(struct sdk_string *argument, struct sdk_string *result, uintptr_t selector, uintptr_t seconds)
{
    register uintptr_t x0 __asm__("x0") = (uintptr_t)argument;
    register uintptr_t x1 __asm__("x1") = selector;
    register uintptr_t x2 __asm__("x2") = seconds;
    register uintptr_t x8 __asm__("x8") = (uintptr_t)result;
    register uintptr_t x9 __asm__("x9") = (uintptr_t)g.entry;
    __asm__ volatile("blr x9"
                     : "+r"(x0), "+r"(x1), "+r"(x2), "+r"(x8), "+r"(x9)
                     :
                     : "x3", "x4", "x5", "x6", "x7", "x10", "x11", "x12", "x13", "x14", "x15", "x16", "x17",
                       "lr", "cc", "memory");
}

static void *sdk_worker(void *unused)
{
    (void)unused;
    g_call_thread = pthread_self();
    struct sdk_string argument = { (const void *)(g.base + SDK_OFF_VTABLE), SDK_PAYLOAD_SIZE + 1, SDK_PAYLOAD_SIZE, (const char *)g.argument };
    struct sdk_string result = { 0, 0, 0, 0 };
    g.result_slot = &result;
    if (sigsetjmp(g_resume, 1) == 0) {
        sdk_invoke(&argument, &result, g.selector, g.seconds);
        g.status = SDK_E_SHAPE;
        snprintf(g.detail, sizeof g.detail, "returned without reaching the digest core");
    }
    pthread_mutex_lock(&g.lock);
    g.finished = true;
    pthread_cond_broadcast(&g.done);
    pthread_mutex_unlock(&g.lock);
    return NULL;
}

int sdk_core_digest(const uint8_t *payload, uint32_t selector, uint32_t seconds, uint8_t *out)
{
    if (!g.loaded || g.broken) return SDK_E_STATE;
    if (!payload || !out || selector > 15) return SDK_E_ARGUMENT;
    pthread_mutex_lock(&g.lock);
    memcpy(g.argument, payload, SDK_PAYLOAD_SIZE);
    g.argument[SDK_PAYLOAD_SIZE] = 0;
    g.selector = selector;
    g.seconds = seconds;
    g.status = SDK_E_FAULT;
    g.finished = false;
    g.detail[0] = 0;
    struct sigaction action;
    memset(&action, 0, sizeof action);
    action.sa_sigaction = sdk_fault;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    for (size_t index = 0; index < 3; index++) sigaction(g_fault_signals[index], &action, &g_previous[index]);
    g_in_call = 1;
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setstacksize(&attributes, SDK_STACK_SIZE);
    pthread_t thread;
    int created = pthread_create(&thread, &attributes, sdk_worker, NULL);
    pthread_attr_destroy(&attributes);
    int status;
    if (created != 0) {
        status = SDK_E_MEMORY;
        snprintf(g.detail, sizeof g.detail, "thread %d", created);
    } else {
        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += SDK_CALL_SECONDS;
        while (!g.finished && pthread_cond_timedwait(&g.done, &g.lock, &deadline) == 0) {}
        if (g.finished) {
            pthread_join(thread, NULL);
            status = g.status;
        } else {
            // Never let the image hold playback, or the process: abandon this call and switch the route off.
            pthread_detach(thread);
            g.broken = true;
            status = SDK_E_BUDGET;
            snprintf(g.detail, sizeof g.detail, "digest exceeded %d s", SDK_CALL_SECONDS);
        }
    }
    g_in_call = 0;
    for (size_t index = 0; index < 3; index++) sigaction(g_fault_signals[index], &g_previous[index], NULL);
    if (status == SDK_OK) memcpy(out, g_output, SDK_DIGEST_SIZE);
    pthread_mutex_unlock(&g.lock);
    return status;
}

const char *sdk_core_detail(void)
{
    return g.detail;
}

int sdk_core_ready(void)
{
    return g.loaded && !g.broken;
}
