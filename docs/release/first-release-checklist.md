# 首版发布验收清单

构建产物：`build/app/outputs/flutter-apk/app-release.apk`。

## 已自动验证

- [x] `flutter analyze` 无问题。
- [x] `flutter test` 通过。
- [x] `flutter build apk --debug` 成功。
- [x] `flutter build apk --release` 成功。
- [x] 本地 MediaStore 映射、BASS 方法通道、DLNA Range server、设备发现、投送/恢复状态和核心语义控件有自动测试。

## 发布前必须在真机完成

- [ ] 配置独立 release keystore；当前 release APK 使用 debug signing key，不能分发。
- [ ] 在 Android 真机安装 APK，授权音乐读取权限并验证歌曲列表。
- [ ] 验证 BASS 播放、暂停、进度拖动、TalkBack 快进/快退和回到本地播放。
- [ ] 用同一 Wi-Fi 的小米电视验证发现、选择不自动投送、明确开始投送、停止并恢复本地。
- [ ] 验证 MP3 320 kbps、CD 质量 FLAC，以及电视支持时 24-bit/96 kHz FLAC；不支持时确认有可恢复错误而非转码。
- [ ] 验证投送结束后 token URL 不再访问媒体。
- [ ] 开启 TalkBack，验证列表、进度、投送弹窗、开始投送、返回手机播放、错误与焦点返还。
- [ ] 验证大字体和 D-pad/键盘导航。
- [ ] 验证根目录、嵌套文件夹、返回上级文件夹和目录内点歌；进入目录不得播放或投送。
- [ ] 验证完整播放器的上一首、下一首、播放/暂停、快进/快退、进度条、时间和本机音量。
- [ ] 连接 DLNA 设备后，从不同文件夹点歌应默认替换设备端媒体，手机不得自动发声。
- [ ] 验证设备端播放/暂停、定位、快进/快退、上一首/下一首和支持时的音量；不支持音量时控件应禁用并说明原因。
- [ ] 在设备断开或控制失败后，确认显示可恢复错误，且不会自动返回本机播放。

## 当前已知缺口

- 通知、锁屏、耳机和音频焦点的 `audio_service` 集成尚未完成。
- 队列、上一首/下一首、循环、随机和 BASS 关于页面尚未完成。
