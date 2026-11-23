// SPDX-License-Identifier: GPL-3.0-only
#ifndef HONGGUO_SDK_DIGEST_H
#define HONGGUO_SDK_DIGEST_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Bounded native adapter for one pinned SDK image (v04.10.03-ml-android, arm64). The caller verifies
// the image's SHA-256 before loading it; nothing about the SDK is embedded in this library.
#define SDK_IMAGE_SIZE 4501136u
#define SDK_PAYLOAD_SIZE 52u
#define SDK_DIGEST_SIZE 20u

enum {
    SDK_OK = 0,
    SDK_E_ARGUMENT = 1,    // payload, selector or output pointer is not usable
    SDK_E_IMAGE = 2,       // bytes are not the pinned image layout this adapter was built for
    SDK_E_MEMORY = 3,      // mapping or thread allocation failed
    SDK_E_UNSUPPORTED = 4, // this build does not support the running CPU/platform
    SDK_E_STATE = 5,       // not loaded, or the adapter disabled itself after a hung call
    SDK_E_SHAPE = 6,       // the image returned without producing a 20-byte digest
    SDK_E_IMPORT = 7,      // the image reached an import that is deliberately not modelled
    SDK_E_FAULT = 8,       // the image faulted inside the call
    SDK_E_BUDGET = 9       // the image exceeded the per-call time budget
};

// Maps the image once per process. `carrier` picks the executable-memory carrier: 1 asks for a
// memfd, 0 for ashmem; either falls back to the other and finally to plain anonymous memory.
// Returns SDK_OK or one of the SDK_E_* codes above.
int sdk_core_load(const uint8_t *image, size_t size, int carrier);

// Computes the digest for `payload` (52 bytes), `selector` (0..15) and `seconds`. Writes 20 bytes to
// `out`. The image never runs outside this call and at most one call runs at a time.
int sdk_core_digest(const uint8_t *payload, uint32_t selector, uint32_t seconds, uint8_t *out);

// Human-readable detail of the last load or digest failure; empty when the last call succeeded.
const char *sdk_core_detail(void);

// Non-zero once sdk_core_load succeeded and no hung call has disabled the adapter.
int sdk_core_ready(void);

#ifdef __cplusplus
}
#endif

#endif
