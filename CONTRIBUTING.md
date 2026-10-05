# Contributing

月播（Yuebo）是已冻结的最终版本（`20261005final`）。仓库接受面向构建修复、兼容性修复和文档的改动；新功能需要先说明对最终版边界的影响。

## 开始之前

1. 阅读 [`AGENTS.md`](AGENTS.md) 的工程约束和关键不变量。
2. 使用 JDK 17、Android SDK 36 和 Gradle 9.x。
3. 不要提交 `local.properties`、`key.properties`、密钥、个人媒体、构建输出或临时探针。

## 验证

```powershell
gradle.bat -p android testDebugUnitTest
gradle.bat -p android :app:assembleDebug
```

涉及 UI、播放、导入或投送时，再按 `scripts/smoke-checklist.md` 做真机验证。

## 提交变更

- 保持现有 Kotlin/Compose 风格和无障碍语义。
- 修改行为时补充一个能失败的 JVM 单测。
- 在 `CHANGELOG.md` 记录用户可见变化。
- Pull request 请说明动机、验证命令和已知限制；不要附带账号、令牌或媒体文件。
