// SPDX-License-Identifier: GPL-3.0-only
// AArch64 trampolines copied next to the loaded SDK image. They are position independent: branches
// inside the blob are PC-relative, and every external target is read from an 8-byte literal that the
// loader fills in. Each trampoline replaces exactly one displaced instruction of the pinned image and
// must leave every other register untouched (x16/x17 are the only scratch registers it may clobber).

    .text
    .p2align 4
    .globl sdk_tramp_start
sdk_tramp_start:

// image+0x17dc48, displaced `ldur x17, [x29, #-0x10]`: the flattened dispatcher fetches its next
// handler through x28. When that handler is the digest core, the result is already in place.
    .globl sdk_tramp_dispatch
sdk_tramp_dispatch:
    ldr     x16, [x28]
    ldr     x17, 2f
    cmp     x16, x17
    b.eq    1f
    ldur    x17, [x29, #-0x10]
    ldr     x16, 3f
    br      x16
1:  ldr     x16, 4f
    br      x16
    .balign 8
2:  .globl sdk_lit_core
sdk_lit_core:
    .quad   0                       // image + 0xecc40
3:  .globl sdk_lit_dispatch_resume
sdk_lit_dispatch_resume:
    .quad   0                       // image + 0x17dc4c
4:  .globl sdk_lit_core_handler
sdk_lit_core_handler:
    .quad   0                       // sdk_core_reached

// image+0x1851d4, displaced `ldr x15, [x16, x17]`: the dispatcher resolves handler tables whose base
// is NULL for unavailable environment; the emulator serves those reads from a zero page. The next
// instruction redefines x17, so only x16 is preserved here.
    .balign 16
    .globl sdk_tramp_load
sdk_tramp_load:
    str     x16, [sp, #-16]!
    add     x17, x16, x17
    mov     x16, #0x1000
    cmp     x17, x16
    b.hs    5f
    mov     x15, #0
    b       6f
5:  ldr     x15, [x17]
6:  ldr     x16, [sp], #16
    ldr     x17, 7f
    br      x17
    .balign 8
7:  .globl sdk_lit_load_resume
sdk_lit_load_resume:
    .quad   0                       // image + 0x1851d8

// image+0x29b22c, displaced `svc #0` inside the image's own libc syscall() wrapper. No instruction of
// the image reaches the kernel: openat/close answer what the emulator observed on first use, the
// thread identity calls answer the emulator's synthetic 1, everything else is ENOSYS.
    .balign 16
    .globl sdk_tramp_svc
sdk_tramp_svc:
    mov     x0, #-38                // -ENOSYS
    cmp     x8, #56                 // __NR_openat
    b.ne    8f
    mov     x0, #-2                 // -ENOENT
    b       9f
8:  cmp     x8, #57                 // __NR_close
    b.ne    10f
    mov     x0, #-7                 // -EBADF
    b       9f
10: cmp     x8, #172                // __NR_getpid
    b.eq    11f
    cmp     x8, #178                // __NR_gettid
    b.ne    9f
11: mov     x0, #1
9:  ldr     x16, 12f
    br      x16
    .balign 8
12: .globl sdk_lit_svc_resume
sdk_lit_svc_resume:
    .quad   0                       // image + 0x29b230

    .globl sdk_tramp_end
sdk_tramp_end:
