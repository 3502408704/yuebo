# Third-party notices

## FFmpeg 9.0.1

The playback fallback links a subset of FFmpeg 9.0.1 into `libffmpeg_player.so`.
Both Android ABI builds report `LGPL version 3 or later` (the mbed TLS backend
is grouped as version3 by FFmpeg's license rules); GPL and nonfree components
are disabled. The license text is packaged in
`app/src/main/assets/licenses/LGPL-2.1-or-later.txt`.
Source archive identity, checksums, build configuration and rebuilding instructions
are documented in `vendor/ffmpeg/README.md` and `vendor/ffmpeg/build.sh`.
JNI integration source is in `app/src/main/cpp/ffmpeg_player/`.

## Mbed TLS 3.6.2

FFmpeg's `https`/`tls` protocol support links Mbed TLS 3.6.2 static libraries
into `libffmpeg_player.so`. Mbed TLS is distributed under the Apache License
2.0; the license text is packaged in
`app/src/main/assets/licenses/Apache-2.0.txt`.
Source archive identity and rebuilding instructions are documented in
`vendor/mbedtls/build.sh`.

## AndroidX Media3 1.11.0

The primary playback engine uses the ExoPlayer, HLS, DASH and RTSP modules of
AndroidX Media3, distributed under Apache-2.0. No native codec dependency is added
by these protocol modules.

## Historical components (removed from the current app)

The following older notices describe the removed BASS/RAOP playback architecture;
these libraries are no longer included in the current build.

### PulseAudio RAOP implementation

The files under `app/src/main/java/app/shengdu/audio/raop/` and their required
format support classes are adapted from WaveFerry commit
`010dd1b6dc1ddfee167cb234822a61e00a055bf4`. They are derived from the
PulseAudio RAOP client implementation and are licensed under LGPL-2.1-or-later.

The full LGPL-2.1-or-later text is included in
`app/src/main/assets/licenses/LGPL-2.1-or-later.txt`. Corresponding source is
distributed in this repository under the paths above.

WaveFerry: https://github.com/sqliu07/WaveFerry
PulseAudio: https://gitlab.freedesktop.org/pulseaudio/pulseaudio/

### OpenSSL (Android 预编译库)

`app/src/main/jniLibs/{arm64-v8a,x86_64}/libssl.so` 与 `libcrypto.so` 为 OpenSSL 3.4.6 的 Android 预编译动态库（用于 BASS 播放 HTTPS 在线流，见 `NativeBassPlayer` 的 `BASS_CONFIG_LIBSSL` 配置），来源于 https://github.com/kibitzerCZ/Prebuilt-OpenSSL-for-Android 。

OpenSSL 采用 Apache-2.0 兼容的 OpenSSL 许可证（`Apache License 2.0` 与 `OpenSSL License` 双许可）。完整许可证文本见 https://www.openssl.org/source/license.html 。
