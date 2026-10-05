#!/bin/bash
# mbed TLS 3.6.x 精简静态交叉编译（arm64-v8a + x86_64），供 FFmpeg 的 tls/https 协议使用。
# Apache-2.0 许可（与 LGPL 精简姿态相容）；只编库、不编测试与示例程序。
# 前置：Git Bash（sh/make/coreutils）、Android NDK、mbedtls 源码（发布包自带生成文件，
# 无需 framework 子模块）。用法：bash build.sh <mbedtls源码目录>
set -e
SRC="${1:?usage: build.sh <mbedtls-source-dir>}"
# mbedtls 3.x 标志文件（发布包自带生成文件，无需 framework 子模块）
[ -f "$SRC/include/mbedtls/build_info.h" ] && [ -f "$SRC/library/version_features.c" ] \
  || { echo "Not an mbedtls 3.x source tree" >&2; exit 1; }
NDK="${ANDROID_NDK:-C:/Users/35024/AppData/Local/Android/Sdk/ndk/27.0.12077973}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
API=24
# 输出到本模块 app/src/main/cpp/mbedtls/<abi>/，供 CMake 链接
# （脚本位于 android/vendor/mbedtls/，相对脚本自身定位，避免受调用目录影响）
OUT="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/cpp/mbedtls"
export PATH="/c/Program Files/Git/usr/bin:/c/Program Files/Git/bin:$PATH"
export SHELL=/bin/sh
MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"
JOBS=4

build_abi() {
  local target=$1 abi=$2
  cd "$SRC"
  echo "===== distclean $target ====="
  "$MAKE" clean >/dev/null 2>&1 || true
  echo "===== make lib $target ====="
  # 关闭 PSA 驱动之外的 3rdparty 依赖（everest 等），默认配置即可；-fPIC 供静态链接进共享库。
  "$MAKE" -j$JOBS lib \
    CC="$TOOLCHAIN/bin/clang.exe" \
    AR="$TOOLCHAIN/bin/llvm-ar.exe" \
    CFLAGS="--target=${target}${API} --sysroot=$SYSROOT -fPIC -O2" \
    WARNING_CFLAGS=""
  local libdir="$SRC/library"
  mkdir -p "$OUT/$abi/lib"
  cp "$libdir"/libmbedcrypto.a "$libdir"/libmbedx509.a "$libdir"/libmbedtls.a "$OUT/$abi/lib/"
  mkdir -p "$OUT/$abi/include"
  cp -r "$SRC/include/." "$OUT/$abi/include/"
  echo "===== DONE $target ===== 输出: $OUT/$abi"
}

mkdir -p "$OUT"
build_abi aarch64-linux-android arm64-v8a
build_abi x86_64-linux-android x86_64
