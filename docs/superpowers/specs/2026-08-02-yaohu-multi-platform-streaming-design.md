# 曜狐多平台在线媒体设计

日期：2026-08-02

## 目标与范围

在现有曜狐网易云直连流媒体模块上增加 QQ、酷我和酷狗三个站点。在线媒体页继续使用同一搜索、播放和下载流程，用户通过平台下拉菜单选择目标站点。

本次包含：

- QQ、酷我、酷狗的曜狐 API 搜索、播放解析和下载解析。
- 在线媒体页通用搜索框和平台下拉菜单。
- 四个平台独立的播放音质、下载音质和进程内解析缓存。
- 三个平台的中文错误、进度和无障碍语义。
- API 映射、质量映射、缓存隔离和 UI 交互测试。

本次不包含：

- Cookie、账号、会员登录或用户凭据输入。
- Apple Music、Tidal、Qobuz、喜马拉雅等其他站点。
- QuickJS、MusicFree/LX 插件、第三方音源脚本或新的本地代理。
- 本地曲库、导入、保存的本地播放列表、DLNA、Chromecast、蓝牙和更新流程的行为迁移。
- NativeBassPlayer 的网络流控制、缓冲、定位和倍速算法重写。

曜狐接口密钥继续从未提交的 `android/local.properties` 注入 `BuildConfig.YAOHU_API_KEY`。源码、测试固件、日志、截图和界面不得包含真实密钥。

## 已确认的交互

在线媒体页的搜索框标签改为“搜索歌曲”，不再显示“搜索网易云音乐”。搜索框与平台下拉菜单同时可见，平台菜单显示以下四个选项：

1. `QQ`
2. `酷我`
3. `酷狗`
4. `网易云`

默认平台为网易云。平台选择器使用 Material 下拉菜单，显示当前选中平台，并提供简短中文语义名和当前状态。

搜索规则：

- 点击搜索按钮或键盘搜索时，使用当前选中平台和当前关键词搜索。
- 当前关键词非空时，用户在平台菜单中选中另一平台后立即发起该平台搜索。
- 当前关键词为空时，平台选择只更新选择状态，不发起空请求；用户随后显式提交搜索。
- 搜索、解析和下载期间禁用会重复发起同一操作的控件。
- 平台切换、焦点移动、TalkBack 浏览和结果聚焦不发起网络请求。
- 搜索开始时清除旧结果；过期的异步搜索结果不得覆盖当前平台和当前关键词的结果。

结果条目显示标题、歌手、专辑和可用的实际音质状态。普通点击建立当前搜索结果的临时在线队列并播放选中项。长按只显示该条目的下载按钮；条目同时提供等价的 TalkBack 自定义操作“下载”。下载按钮和平台选择器的可点击目标至少为 48dp。

在线队列只存在当前进程，不写入持久化队列。系统返回、迷你播放器、正在播放页和现有播放器控制保持当前行为。在线播放失败时停留在当前曲目，不自动切换下一首。

## API 与平台模型

### 共享 HTTP 层

继续复用 `YaohuApiClient` 和 `YaohuHttp`：

- 请求根地址为 `https://api.yaohud.cn`。
- 自动附加 `key`，对所有参数进行 UTF-8 URL 编码。
- 连接超时 10 秒，读取超时 15 秒。
- HTTP 非 2xx、连接异常和无效响应转换为不包含密钥的 `YaohuApiException`。
- 平台 Catalog 负责解析自己的业务 JSON，不共享未经验证的平台响应字段。

`MusicPlatform` 扩展为 `QQ`、`Kuwo`、`Kugou`、`Netease`，其显示名分别为“QQ”“酷我”“酷狗”“网易云”。网易云现有质量模型和 Catalog 保留；新增平台各自定义质量枚举、搜索结果和解析结果。

### QQ

接口：`GET /api/music/qq_plus`。

不发送 Cookie。搜索使用关键词参数 `msg`，可使用接口文档定义的结果数量参数 `g`；解析使用 `msg`、服务端序号 `n` 和质量参数 `size`。应用首期展示 `mp3`、`hq`、`flac` 三个无用户 Cookie 质量挡位，不展示需要额外会员凭据的高级挡位。

搜索结果读取 `data.songs` 中的服务端序号 `n`、`name`、`singer`、`album`、`mid` 和 `image`。封面优先读取解析结果中的 `picture`，搜索结果的 `image` 作为回退。解析地址按 `music_url`、`musicurl`、`url` 顺序选择；实际音质按 `actual_size`、`cached_size`、`format`、`request_size` 顺序选择。没有有效地址时抛出中文 `YaohuApiException`。

### 酷狗

接口：`GET /api/music/kg`。

不发送 Cookie。搜索使用 `msg`，服务端序号 `n` 和结果数量 `g`；解析使用 `msg`、`n` 和 `quality`。应用首期展示 `128`、`320`、`flac` 三个不要求用户凭据的质量挡位，不展示 `high`、`viper_*` 等文档中可能依赖 VIP Cookie 的挡位。

搜索结果读取 `data` 中的 `n`、`name`、`singer`、`album`、`rid` 和 `cover`。解析结果读取 `data.play_url`、`data.selected_quality` 和 `data.ext_name`；服务端返回空 `data` 或空 `play_url` 时显示中文错误。

### 酷我

接口：`GET /api/music/kuwo`。

不发送 Cookie。搜索使用 `action=so`、`msg` 和结果数量 `g`；解析使用 `action=song`、平台歌曲 `id` 和质量参数 `size`。应用展示接口文档声明的 `standard`、`exhigh`、`SQ`、`lossless` 和 `hires` 五个挡位。

搜索结果读取服务端序号 `n`、`name`、`songname`、`singer`、`album`、`rid` 和 `picture`；解析时使用搜索结果的 `rid` 作为 `id`。解析地址读取服务端 `url`，实际音质优先读取 `level`，格式读取 `format`。地址为空时抛出中文错误。

每个平台的 JSON 根对象都必须校验业务 `code == 200`。业务错误优先使用服务端 `msg`，缺失时使用固定中文错误。字段缺失的搜索条目跳过，不让单条坏数据导致整个搜索崩溃。

## 状态与播放数据流

在线页使用一份当前平台结果列表。ViewModel 在 UI 边界提供以下状态：

- 当前平台、当前搜索关键词、当前结果列表和搜索中标记。
- 当前平台的播放/下载默认音质。
- 正在下载的在线曲目标识集合。
- 当前在线播放曲目的服务端实际音质和中文状态。

各平台 Catalog 的独立搜索结果在 ViewModel 中转换为统一的 `OnlineTrack` 展示模型。该模型至少包含平台、原始关键词、服务端序号或平台 ID、标题、歌手、专辑、封面 URL 和格式提示。平台特有字段不在 UI 层解析。

点击结果时，ViewModel 将结果映射为带负 ID 的 `NativeTrack`，并在进程内保存平台特有搜索结果。负 ID 和 `yaohu` 平台 URI 只用于当前进程识别，不能写入持久化队列或 URL。`startLocalPlaybackNow` 在进入本地 BASS 播放前识别在线曲目并调用对应 Catalog 解析，随后只把直连 URL 交给 `NativeBassPlayer.playUrl`。

网易云继续保留现有 VIP/免费端点回退序列。QQ、酷我和酷狗分别调用各自唯一端点，不把其中一家字段当成另一家的回退。每次用户显式点播最多在当前曲目上进行有限解析尝试；失败后停留当前曲目，不自动换歌。

解析 URL 使用进程内缓存，缓存键包含平台、平台曲目标识和请求质量。缓存的值包含 URL、实际质量、MIME/扩展名和歌词等播放边界数据。BASS 打开缓存 URL 失败时先删除当前缓存，再重新解析一次；URL 不写入 SharedPreferences、播放列表、日志或导出数据。

在线播放成功后沿用现有音量、倍速、进度轮询、播放历史和 MediaSession 更新。在线流仍不参与 DLNA/Chromecast 投送；投送状态下点播在线结果显示中文提示并保持原播放状态。

## 下载与错误处理

下载按平台的默认下载音质重新解析，不直接复用播放地址。写入流程沿用现有模式：

1. 在 `Music/汪汪播放器/` 下插入 `MediaStore.Audio` pending 条目。
2. 使用解析结果的 MIME/扩展名生成安全文件名“歌手 - 歌名”。
3. 通过 `HttpURLConnection` 流式复制地址内容。
4. 成功后清除 `IS_PENDING`，刷新本地媒体库。
5. 任意异常都删除 pending 条目并清除下载中状态。

以下情况均显示可见的简体中文状态，并通过在线页 live region 让 TalkBack 感知：密钥缺失、网络超时、HTTP 错误、业务 `code` 非 200、无搜索结果、无播放地址、格式不支持、BASS 打开失败和 MediaStore 写入失败。错误文案不得包含密钥、完整带鉴权的请求 URL 或 Java 异常堆栈。

搜索和播放不自动重试整个队列。用户再次点击搜索或结果时，才开始新的请求轮次。下载按钮在当前条目下载期间禁用，完成或失败后恢复。

## 文件边界

- `android/app/src/main/kotlin/com/example/local_music_player/YaohuNeteaseApi.kt`
  - 保留网易云模型和 Catalog；扩展平台枚举或与共享在线模型连接。
- `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt`
  - 新增 QQ、酷我、酷狗的质量枚举、搜索结果、解析结果、Catalog 和响应字段映射。
- `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
  - 管理当前平台、搜索、临时在线队列、四个平台质量偏好、平台解析缓存、播放和下载协调；不改变 `NativeBassPlayer`、投送或蓝牙控制器。
- `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
  - 通用搜索框、平台下拉菜单、结果列表、下载操作、进度和错误语义。
- `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
  - 将 OnlineScreen 回调接到通用平台选择和搜索动作；设置页增加 QQ、酷我、酷狗的播放/下载质量菜单，保持已有导航和返回逻辑。
- `android/app/src/test/kotlin/com/example/local_music_player/YaohuOtherMusicApiTest.kt`
  - 覆盖三个 Catalog 的参数和 JSON 映射、实际音质、空地址和业务错误。
- `android/app/src/test/kotlin/com/example/local_music_player/OnlinePlatformStateTest.kt`
  - 覆盖平台标签顺序、质量偏好隔离、在线缓存键隔离和在线曲目不持久化的纯逻辑。
- 当前模块没有 Compose UI 测试依赖和 `androidTest` 基础设施；平台选择、显式搜索、搜索中禁用和下载自定义操作通过设备 UI 树与 TalkBack 冒烟验收完成，不新增测试依赖。

## 验证门槛

实现前先写失败测试，观察测试因缺少实现而失败，再写最小实现。实现后至少运行：

- `rtk git diff --check`。
- `rtk cmd /c "gradlew.bat :app:testDebugUnitTest"`，工作目录 `D:\musicplayer\android`。
- `rtk cmd /c "gradlew.bat :app:compileDebugKotlin"`，工作目录 `D:\musicplayer\android`。
- `rtk cmd /c "gradlew.bat :app:assembleRelease"`，工作目录 `D:\musicplayer\android`。

设备验收覆盖：默认网易云搜索、QQ/酷我/酷狗平台切换后搜索、四个平台播放与下载、实际音质提示、搜索中禁用、空关键词不请求、TalkBack 平台选择和下载操作、最大字体与小屏布局；同时回归本地播放、DLNA、Chromecast、蓝牙路由和 logcat 无本应用 FATAL/ANR。
