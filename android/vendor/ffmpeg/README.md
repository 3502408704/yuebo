# FFmpeg 9.0.1 Android 解码库

Media3 1.11.0 是音频和视频主内核，HLS/m3u8、DASH、RTSP 由相应 Media3 模块处理。
FFmpeg 用于本地文件、content URI 和普通 HTTP 媒体的长尾格式兜底，不承担自适应流协议。

## 源码与重建

本次构建使用官方 FFmpeg 9.0.1 发布源码包，未修改上游源码：

```text
https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.xz
SHA-256: cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635
Size: 12036420 bytes
```

构建环境：Windows、Git Bash、NDK 27.0.12077973、VS 2022 的 C++ 工具链。
在仓库根目录运行（VS/Gradle 的短路径对应本机安装）：

```powershell
rtk cmd /c "call C:\PROGRA~1\MICROS~4\2022\COMMUN~1\VC\AUXILI~1\Build\vcvars64.bat && C:\PROGRA~1\Git\bin\bash.exe android/vendor/ffmpeg/build.sh /c/Users/35024/AppData/Local/Temp/ffmpeg-9.0.1"
```

`build.sh` 将五个静态库与头文件写到 `app/src/main/cpp/ffmpeg/<abi>/`，随后由 CMake
链接成 `libffmpeg_player.so`。两个 ABI 的 `include/libavutil/ffversion.h` 均应为 9.0.1。
脚本固定源码版本；NDK 路径可以用 `ANDROID_NDK` 覆盖。主机工具用 NDK Clang 的 MSVC target
与 `-fuse-ld=link`，因此需要先初始化 VS 环境。

ARM64 启用汇编和运行时 CPU 能力检测（包括 NEON）；x86_64 关闭独立 x86 汇编，供模拟器验证。
链接时隐藏静态库内部符号，仅导出 JNI，保证 ARM64 汇编内部表的本地重定位。

## 格式与边界

- 原有 APE、WavPack、DSF/DFF、ALAC、WMV、RM/RMVB、MPEG 等兜底保留；补充 TTA、WMA、TAK、
  Musepack、AC-3/E-AC-3、DTS、TrueHD/MLP、CAF 的解码或容器组件。
- 完整 decoder/demuxer/parser 白名单以 `build.sh` 为准。启用组件不等于每种编码配置都已经过设备验收。
- 协议为 `file,http,https,tcp,tls`（2026-09-18 起）：https 经 Mbed TLS 3.6.2 静态库后端
  （`android/vendor/mbedtls/build.sh` 产物，Apache-2.0），RMVB/PS 等长尾格式落在 https 直链上
  也可直接兜底拉流；客户端 `FfmpegRouting.canOpenWithFfmpeg` 同步放行 `https` scheme。
  https HLS/DASH 仍由 Media3 处理，不经本兜底。
- HLS 清单、密钥和 DASH 清单直接读取；可缓存的媒体分片仍使用现有 LRU 缓存。直播窗口过期每次播放最多自动恢复一次。
- FFmpeg 输出为立体声 16-bit PCM，DSD 等超过 192kHz 的解码结果重采样至 48kHz；不承诺原码/无损直通。
- FFmpeg 路径暂不支持倍速、EQ 或静音跳过。主流格式优先走系统解码，避免无谓的软件视频解码。

## 许可与体积

两次 configure 均输出 `License: LGPL version 3 or later`——mbed TLS 后端按 FFmpeg 许可
分组属 version3，故 configure 需 `--enable-version3`（对外仍是 LGPL，与随包
`LGPL-2.1-or-later` 许可文本一致）。未启用 GPL/nonfree 组件，未引入 x264/x265、OpenSSL、
BASS 或 libVLC 到应用。Mbed TLS 以静态库链接、Apache-2.0，全文位于
`app/src/main/assets/licenses/Apache-2.0.txt`；FFmpeg 的 LGPL 全文位于
`app/src/main/assets/licenses/LGPL-2.1-or-later.txt`，均随 APK 打包；JNI 和重建脚本在本仓库中。

共享库实际体积、APK 校验和和设备证据见
`docs/handoff/2026-09-09-player-engine-enhancement.md`（仓库根目录）。静态库包含调试符号，
其文件大小不能当作 APK 增量；应比较最终 strip/R8 后的产物。

## 设备测试

先用主机 FFmpeg 生成合成夹具（编码器只用于测试，不会进入 APK）：

```powershell
rtk python scripts/generate-player-fixtures.py --ffmpeg <host-ffmpeg.exe>
```

再构建 `:app:assembleDebug :app:assembleDebugAndroidTest`，安装两个 APK，运行：

```powershell
rtk adb shell am instrument -w com.example.local_music_player.beta.test/com.example.local_music_player.PlaybackEngineInstrumentation
```

结果写到应用私有目录 `files/playback-qa.txt`；必须检查输出 `failures=0`，不能只看 adb 的退出码。
测试仅在当前进程让回环地址绕过设备代理，结束后恢复原 ProxySelector，不修改系统代理设置。
