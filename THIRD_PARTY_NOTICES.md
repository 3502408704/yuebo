# Third-party notices

月播将以下第三方软件随源码或构建产物使用。各组件仍受其原始许可证约束；本文件不改变这些许可证。

| Component | Use | License / notice |
| --- | --- | --- |
| AndroidX Media3 / ExoPlayer | 音频、视频与投送 | Apache-2.0 |
| Jetpack Compose / AndroidX | 用户界面与 Android 集成 | Apache-2.0 |
| Kotlin / Kotlin Coroutines | 应用运行时 | Apache-2.0 |
| FFmpeg 9.0.1（精简构建） | 音视频格式与解码兜底 | LGPL-2.1-or-later；源码与构建配置位于 `android/app/src/main/cpp/ffmpeg/` |
| mbedTLS | TLS 支持 | Apache-2.0（随源码目录保留原始版权声明） |
| UnRAR | RAR 解包 | UnRAR license；完整文本见 `android/app/src/main/cpp/unrar_license.txt` |
| QuickJS Android wrapper | MusicFree 插件运行时 | 依赖自身许可证；版本与坐标见 `android/app/build.gradle.kts` |
| OkHttp | HTTP 网络访问 | Apache-2.0 |
| jaudiotagger | 音频标签读取 | LGPL-2.1-or-later |
| ZXing Core | 二维码处理 | Apache-2.0 |
| MusicFree 插件协议 | 用户导入插件的兼容协议 | 参见 <https://github.com/maotoumao/MusicFree> |

发布二进制包时，请同时提供本文件及各组件要求附带的许可证文本。依赖版本以 Gradle 配置为准；如许可证文本与上表不一致，应以上游发布内容为准并修订本文件。
