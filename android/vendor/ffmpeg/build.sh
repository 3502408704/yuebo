#!/bin/bash
# FFmpeg 9.0.1 精简 LGPL(v3) 交叉编译（arm64-v8a + x86_64），供本地音视频长尾格式兜底使用。
# mbedtls 后端（https/tls 协议）按 FFmpeg 许可分组属 version3，故须 --enable-version3
# （对外仍是 LGPL，与随包 LGPL-2.1-or-later 许可文本一致）。
# 前置：Git Bash（sh/make/coreutils）、Android NDK（含 llvm clang 与 make）、FFmpeg 源码。
# 用法：bash build.sh <ffmpeg源码目录>
set -e
SRC="${1:?usage: build.sh <ffmpeg-source-dir>}"
if [ "$(cat "$SRC/RELEASE")" != "9.0.1" ]; then
  echo "Expected FFmpeg 9.0.1 source" >&2
  exit 1
fi
NDK="${ANDROID_NDK:-C:/Users/35024/AppData/Local/Android/Sdk/ndk/27.0.12077973}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
API=24
# 输出到本模块 app/src/main/cpp/ffmpeg/<abi>/，供 CMake 链接
# （脚本位于 android/vendor/ffmpeg/，相对脚本自身定位，避免受调用目录影响）
OUT="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/cpp/ffmpeg"
export PATH="/c/Program Files/Git/usr/bin:/c/Program Files/Git/bin:$PATH"
export SHELL=/bin/sh
# 宿主编译器（--host-cc，MSVC target）需要 VC + Windows SDK 的头/库：
# 普通 shell（非 VS 开发者命令行）里 clang 的 MSVC 自动探测不生效，这里显式给出。
VC_TOOLS="${VC_TOOLS:-C:/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC}"
WIN_SDK="${WIN_SDK:-C:/Program Files (x86)/Windows Kits/10/Include}"
WIN_SDK_LIB="${WIN_SDK_LIB:-C:/Program Files (x86)/Windows Kits/10/Lib}"
MSVC_DIR="$(ls -1d "$VC_TOOLS"/* 2>/dev/null | sort -V | tail -1)"
SDK_DIR="$(ls -1d "$WIN_SDK"/* 2>/dev/null | sort -V | tail -1)"
SDK_LIB_DIR="$(ls -1d "$WIN_SDK_LIB"/* 2>/dev/null | sort -V | tail -1)"
if [ -d "$MSVC_DIR/include" ] && [ -d "$SDK_DIR/ucrt" ] && [ -d "$SDK_LIB_DIR/um/x64" ]; then
  export INCLUDE="$(cygpath -w "$MSVC_DIR/include");$(cygpath -w "$SDK_DIR/ucrt");$(cygpath -w "$SDK_DIR/um");$(cygpath -w "$SDK_DIR/shared")${INCLUDE:+;$INCLUDE}"
  # SDK 的库在 Lib\ 树（与头文件的 Include\ 树不同根）
  export LIB="$(cygpath -w "$MSVC_DIR/lib/x64");$(cygpath -w "$SDK_LIB_DIR/ucrt/x64");$(cygpath -w "$SDK_LIB_DIR/um/x64")${LIB:+;$LIB}"
  export PATH="$(cygpath -u "$MSVC_DIR/bin/Hostx64/x64"):$PATH"
fi

MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"
JOBS=4
# mbed TLS 静态库（android/vendor/mbedtls/build.sh 产物），供 https/tls 协议使用
MBEDTLS_OUT="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/cpp/mbedtls"

build_abi() {
  local arch=$1 target=$2 abi=$3
  cd "$SRC"
  echo "===== distclean $target ====="
  "$MAKE" distclean >/dev/null 2>&1 || true
  echo "===== configure $target ====="
  ./configure \
    --prefix="$PWD/install-$target" \
    --enable-cross-compile --target-os=android \
    --arch=$arch \
    --host-cc="$TOOLCHAIN/bin/clang.exe --target=x86_64-pc-windows-msvc" \
    --host-ldflags="-fuse-ld=link" \
    --cc="$TOOLCHAIN/bin/clang.exe" \
    --cxx="$TOOLCHAIN/bin/clang++.exe" \
    --ar="$TOOLCHAIN/bin/llvm-ar.exe" \
    --nm="$TOOLCHAIN/bin/llvm-nm.exe" \
    --ranlib="$TOOLCHAIN/bin/llvm-ranlib.exe" \
    --strip="$TOOLCHAIN/bin/llvm-strip.exe" \
    --sysroot="$SYSROOT" \
    --enable-static --disable-shared --enable-pic \
    --disable-programs --disable-doc --disable-avdevice --disable-avfilter \
    --disable-encoders --disable-muxers --disable-bsfs --disable-filters --disable-hwaccels \
    --disable-everything --disable-x86asm --enable-small \
    --enable-protocol=file,http,https,tcp,tls \
    --enable-mbedtls --enable-version3 \
    --enable-demuxer=asf,rm,mpegps,mpegvideo,mpegts,mov,avi,matroska,flv,m4v,ogg,ape,wv,dsf,iff,wav,aiff,caf,flac,mp3,aac,tta,ac3,eac3,dts,truehd,mlp,tak,mpc,mpc8 \
    --enable-decoder=h264,hevc,vp8,vp9,theora,mjpeg,mpeg1video,mpeg2video,wmv1,wmv2,wmv3,vc1,rv10,rv20,rv30,rv40,mpeg4,msmpeg4v1,msmpeg4v2,msmpeg4v3,mp1,mp2,mp3,aac,aac_latm,ac3,eac3,dca,cook,sipr,wmav1,wmav2,wmapro,wmalossless,flac,vorbis,opus,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_u8,pcm_alaw,pcm_mulaw,alac,ape,wavpack,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar,dst,tta,truehd,mlp,tak,mpc7,mpc8 \
    --enable-parser=h264,hevc,vp8,vp9,vc1,rv34,mpeg4video,mpegvideo,mpegaudio,aac,aac_latm,ac3,dca,cook,sipr,flac,vorbis,opus,mlp,tak \
    --enable-swscale --enable-swresample --enable-avformat --enable-avcodec --enable-avutil --enable-network \
    --extra-cflags="--target=${target}${API} -fPIC -O2 -I$MBEDTLS_OUT/$abi/include" \
    --extra-cxxflags="--target=${target}${API} -fPIC -O2" \
    --extra-ldflags="--target=${target}${API} -L$MBEDTLS_OUT/$abi/lib"
  echo "===== make $target ====="
  "$MAKE" -j$JOBS
  "$MAKE" install
  local libdir="$PWD/install-$target/lib"
  mkdir -p "$OUT/$abi/lib"
  cp "$libdir"/*.a "$OUT/$abi/lib/"
  mkdir -p "$OUT/$abi/include"
  cp -r "$PWD/install-$target/include/." "$OUT/$abi/include/"
  echo "===== DONE $target ===== 输出: $OUT/$abi"
}

mkdir -p "$OUT"
build_abi aarch64 aarch64-linux-android arm64-v8a
build_abi x86_64 x86_64-linux-android x86_64
