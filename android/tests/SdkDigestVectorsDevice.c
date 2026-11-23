// SPDX-License-Identifier: GPL-3.0-only
/*
 * Device harness for the repository's native adapter: loads the pinned SDK image through a memory
 * carrier and replays the digest golden vectors of android/tests/sdk-digest-vectors.json.
 * usage: SdkDigestVectorsDevice <image> <sdk-digest-vectors.json> [carrier 1=memfd 0=ashmem]
 * Run through android/tests/run-sdk-digest-vectors-device.sh; the exit status is 0 only when every
 * vector matches byte for byte.
 */
#include <sdk_digest.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double seconds_now(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (double)now.tv_sec + (double)now.tv_nsec / 1e9;
}

static int unhex(const char *text, uint8_t *out, size_t count)
{
    for (size_t index = 0; index < count; index++) {
        unsigned value;
        if (sscanf(text + index * 2, "%2x", &value) != 1) return 0;
        out[index] = (uint8_t)value;
    }
    return 1;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    int carrier = argc > 3 ? atoi(argv[3]) : 1;
    if (argc < 3) { fprintf(stderr, "usage: SdkDigestVectorsDevice <image> <sdk-digest-vectors.json> [carrier]\n"); return 2; }
    FILE *image_file = fopen(argv[1], "rb");
    if (!image_file) { perror(argv[1]); return 2; }
    uint8_t *image = malloc(SDK_IMAGE_SIZE);
    size_t size = image ? fread(image, 1, SDK_IMAGE_SIZE, image_file) : 0;
    fclose(image_file);
    if (!image || size != SDK_IMAGE_SIZE) { fprintf(stderr, "image read %zu\n", size); return 2; }
    double started = seconds_now();
    int status = sdk_core_load(image, size, carrier);
    printf("load: status=%d detail=%s (%.1f ms) ready=%d\n", status, sdk_core_detail(), (seconds_now() - started) * 1000, sdk_core_ready());
    if (status != SDK_OK) return 2;
    free(image);

    FILE *vectors_file = fopen(argv[2], "rb");
    if (!vectors_file) { perror(argv[2]); return 2; }
    fseek(vectors_file, 0, SEEK_END);
    long length = ftell(vectors_file);
    fseek(vectors_file, 0, SEEK_SET);
    char *text = malloc((size_t)length + 1);
    if (!text || fread(text, 1, (size_t)length, vectors_file) != (size_t)length) return 2;
    text[length] = 0;
    fclose(vectors_file);

    int total = 0, matched = 0;
    double slowest = 0;
    for (const char *cursor = strstr(text, "\"payload\""); cursor; cursor = strstr(cursor, "\"payload\"")) {
        const char *payload = strchr(cursor, ':') + 2;
        const char *selector = strstr(cursor, "\"selector\"");
        const char *seconds = strstr(cursor, "\"seconds\"");
        const char *digest = strstr(cursor, "\"digest\"");
        if (!payload || !selector || !seconds || !digest) break;
        uint8_t input[SDK_PAYLOAD_SIZE], expected[SDK_DIGEST_SIZE], actual[SDK_DIGEST_SIZE];
        if (!unhex(payload + 1, input, sizeof input) || !unhex(strchr(digest, ':') + 3, expected, sizeof expected)) break;
        uint32_t select = (uint32_t)strtoul(strchr(selector, ':') + 1, NULL, 10);
        uint32_t when = (uint32_t)strtoul(strchr(seconds, ':') + 1, NULL, 10);
        double call = seconds_now();
        status = sdk_core_digest(input, select, when, actual);
        double elapsed = seconds_now() - call;
        if (elapsed > slowest) slowest = elapsed;
        int same = status == SDK_OK && !memcmp(actual, expected, sizeof expected);
        total++;
        matched += same;
        if (!same) printf("vector %2d selector=%2u seconds=%u: status=%d detail=%s\n", total, select, when, status, sdk_core_detail());
        cursor = digest;
    }
    printf("vectors=%d matched=%d slowest=%.1f ms ready=%d\n", total, matched, slowest * 1000, sdk_core_ready());
    return total > 0 && matched == total ? 0 : 1;
}
