#!/bin/sh
# build-dfroot.sh — rebuild libdfroot.so / libbootstrap.so manually with the
# NDK (same outputs as the mcx CMakeLists, no gradle/AGP needed).
#   $1 = jni dir (exp.c, libcxx.S, elf_parser.c, ko/, splicehelper.c, bootstrap.c)
#   $2 = output dir for libdfroot.so / libbootstrap.so / libsplicehelper.so
set -e
# 用法: NDK=/path/to/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin ./build-dfroot.sh <jni_dir> <out_dir>
# 或直接传第 3 个参数指定 NDK bin 目录。
NDK=${3:-${NDK:-$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin}}
if [ ! -x "$NDK/aarch64-linux-android33-clang" ]; then
    echo "error: clang not found under NDK='$NDK'" >&2
    echo "       set NDK=/path/to/ndk/toolchains/llvm/prebuilt/<host>/bin" >&2
    exit 1
fi
JNI=${1:?usage: $0 <jni_dir> <out_dir> [ndk_bin_dir]}
OUT=${2:?usage: $0 <jni_dir> <out_dir> [ndk_bin_dir]}
API=33
CC="$NDK/aarch64-linux-android$API-clang"
mkdir -p "$OUT"
cd "$JNI"

# 1. splicehelper: freestanding static blob — written into the jni dir because
#    exp.c's inline asm does `.incbin "splicehelper"` relative to -I.
#    NOTE: when $JNI/splicehelper already exists (e.g. the proven release
#    blob), keep it — our own -Os rebuild was implicated in read failures.
if [ ! -f "$JNI/splicehelper" ]; then
    "$CC" splicehelper.c -o "$JNI/splicehelper" \
        -nodefaultlibs -nostartfiles -ffreestanding -static -O2 \
        -I"$JNI"
    "$NDK/llvm-strip" "$JNI/splicehelper"
fi

# 2. libdfroot.so (PIE executable named lib*.so; embeds the ko + splicehelper)
"$CC" -O2 -fPIE -pie -std=gnu11 -Wall \
    -I"$JNI" -DAPI="$API" \
    exp.c libcxx.S elf_parser.c \
    -o "$OUT/libdfroot.so" \
    -llog -lm \
    -Wl,-z,max-page-size=16384

# 3. libbootstrap.so
"$CC" -O2 -fPIE -pie -std=gnu11 -Wall \
    -I"$JNI" \
    bootstrap.c \
    -o "$OUT/libbootstrap.so" \
    -Wl,-z,max-page-size=16384

ls -la "$OUT"
