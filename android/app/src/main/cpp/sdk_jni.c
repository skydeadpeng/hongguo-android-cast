// SPDX-License-Identifier: GPL-3.0-only
// JNI entry points of libhongguo_sdk.so. They only marshal byte arrays to the digest adapter.
#include <jni.h>
#include <string.h>
#include "sdk_digest.h"

#define HONGGUO_METHOD(name) Java_io_github_jackotom_hongguo_android_SdkDigestBridge_##name

JNIEXPORT jint JNICALL HONGGUO_METHOD(nativeLoad)(JNIEnv *env, jclass type, jbyteArray image, jint carrier)
{
    (void)type;
    if (!image) return SDK_E_ARGUMENT;
    jsize size = (*env)->GetArrayLength(env, image);
    if (size != (jsize)SDK_IMAGE_SIZE) return SDK_E_ARGUMENT;
    jbyte *bytes = (*env)->GetByteArrayElements(env, image, NULL);
    if (!bytes) return SDK_E_MEMORY;
    jint status = sdk_core_load((const uint8_t *)bytes, (size_t)size, carrier);
    (*env)->ReleaseByteArrayElements(env, image, bytes, JNI_ABORT);
    return status;
}

JNIEXPORT jint JNICALL HONGGUO_METHOD(nativeDigest)(JNIEnv *env, jclass type, jbyteArray payload, jint selector, jint seconds, jbyteArray out)
{
    (void)type;
    if (!payload || !out || (*env)->GetArrayLength(env, payload) != SDK_PAYLOAD_SIZE || (*env)->GetArrayLength(env, out) != SDK_DIGEST_SIZE)
        return SDK_E_ARGUMENT;
    jbyte *in = (*env)->GetByteArrayElements(env, payload, NULL);
    jbyte *result = (*env)->GetByteArrayElements(env, out, NULL);
    if (!in || !result) {
        if (in) (*env)->ReleaseByteArrayElements(env, payload, in, JNI_ABORT);
        if (result) (*env)->ReleaseByteArrayElements(env, out, result, JNI_ABORT);
        return SDK_E_MEMORY;
    }
    jint status = sdk_core_digest((const uint8_t *)in, (uint32_t)selector, (uint32_t)seconds, (uint8_t *)result);
    (*env)->ReleaseByteArrayElements(env, payload, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, result, status == SDK_OK ? 0 : JNI_ABORT);
    return status;
}

JNIEXPORT jint JNICALL HONGGUO_METHOD(nativeReady)(JNIEnv *env, jclass type)
{
    (void)env;
    (void)type;
    return sdk_core_ready();
}

JNIEXPORT jstring JNICALL HONGGUO_METHOD(nativeDetail)(JNIEnv *env, jclass type)
{
    (void)type;
    const char *text = sdk_core_detail();
    return (*env)->NewStringUTF(env, text ? text : "");
}
