cmake_minimum_required(VERSION 3.22.1)
project(unrar_wrapper CXX)
enable_language(C)

# Android 15+ 的 16 KB 页设备要求 ELF LOAD 段 16 KB 对齐；NDK r27 默认仍按 4 KB 链接，
# 对齐由最终链接决定，静态 .a 无需重编。
add_link_options(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Explicit list mirroring the upstream UNIX makefile "lib" target
# (OBJECTS + LIB_OBJ). unrar 7.x compiles some sources as #include
# fragments from other .cpp files, so they must not be compiled as
# standalone translation units; Windows-only sources are excluded too.
set(UNRAR_SRC
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/archive.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/arcread.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/blake2s.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/cmddata.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/consio.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/crc.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/crypt.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/dll.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/encname.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/errhnd.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/extinfo.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/extract.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/file.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/filefn.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/filcreat.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/filestr.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/find.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/getbits.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/global.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/hash.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/headers.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/largepage.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/list.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/match.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/options.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/pathfn.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/qopen.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/rar.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/rarvm.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/rawread.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/rdwrfn.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/resource.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/rijndael.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/rs16.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/scantree.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/secpassword.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/sha1.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/sha256.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/smallfn.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/strfn.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/strlist.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/system.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/threadpool.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/timefn.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/ui.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/unicode.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/unpack.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/unrar/volume.cpp)

add_library(unrar_wrapper SHARED unrar_wrapper.cpp ${UNRAR_SRC})
target_include_directories(unrar_wrapper PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/unrar)
target_compile_definitions(unrar_wrapper PRIVATE _UNIX RARDLL SILENT UNIX_TIME_NS)
target_compile_options(unrar_wrapper PRIVATE -D_FILE_OFFSET_BITS=64)

# FFmpeg 静态库（android/vendor/ffmpeg/build.sh 产物；LGPL 精简构建）
set(FFMPEG_ABI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/${ANDROID_ABI}")

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION "${FFMPEG_ABI_DIR}/lib/libavformat.a")
add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_ABI_DIR}/lib/libavcodec.a")
add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_ABI_DIR}/lib/libavutil.a")
add_library(swscale STATIC IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION "${FFMPEG_ABI_DIR}/lib/libswscale.a")
add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION "${FFMPEG_ABI_DIR}/lib/libswresample.a")

# mbed TLS 静态库（android/vendor/mbedtls/build.sh 产物；FFmpeg https/tls 协议的后端）
set(MBEDTLS_ABI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/mbedtls/${ANDROID_ABI}")
add_library(mbedtls STATIC IMPORTED)
set_target_properties(mbedtls PROPERTIES IMPORTED_LOCATION "${MBEDTLS_ABI_DIR}/lib/libmbedtls.a")
add_library(mbedx509 STATIC IMPORTED)
set_target_properties(mbedx509 PROPERTIES IMPORTED_LOCATION "${MBEDTLS_ABI_DIR}/lib/libmbedx509.a")
add_library(mbedcrypto STATIC IMPORTED)
set_target_properties(mbedcrypto PROPERTIES IMPORTED_LOCATION "${MBEDTLS_ABI_DIR}/lib/libmbedcrypto.a")

# FFmpeg 兜底播放器（JNI，WMV/RMVB/MPG 等旧格式）
add_library(ffmpeg_player SHARED ffmpeg_player/ffmpeg_player.c)
set_target_properties(ffmpeg_player PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
# FFmpeg 的静态 ARM64 汇编内部表按本地符号寻址；仅导出 JNI，避免符号抢占与无效重定位。
target_link_options(ffmpeg_player PRIVATE "-Wl,--exclude-libs,ALL")
target_include_directories(ffmpeg_player PRIVATE ${FFMPEG_ABI_DIR}/include)
# 静态链接顺序：依赖方在左、被依赖在右（avformat 的 tls → mbedtls → x509 → crypto）。
target_link_libraries(ffmpeg_player
    avformat avcodec swscale swresample avutil
    mbedtls mbedx509 mbedcrypto
    android log z m)
