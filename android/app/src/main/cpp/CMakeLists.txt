cmake_minimum_required(VERSION 3.22.1)
project(hongguo_sdk C ASM)

# libhongguo_sdk.so: the digest adapter for the pinned SDK image. Plain C with the platform libc
# only; nothing besides the JNI entry points is exported, and no unwind tables or stack protector
# are wanted between the adapter and the image it calls.
# The image is AArch64 code, so the adapter exists for arm64 only. Deciding that here rather than
# with ndk.abiFilters keeps every other native library of the other ABIs in place: a 32-bit TV
# build still carries its libVLC, it just has no SDK route.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(hongguo_sdk SHARED sdk_jni.c sdk_digest.c sdk_tramp.S)
    target_compile_options(hongguo_sdk PRIVATE -Wall -Wextra -O2 -fvisibility=hidden -fno-stack-protector -mbranch-protection=none)
    target_link_options(hongguo_sdk PRIVATE -mbranch-protection=none)
endif()
