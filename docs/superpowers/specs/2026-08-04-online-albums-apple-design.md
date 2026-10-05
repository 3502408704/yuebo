# 在线专辑与 Apple Music 设计

日期：2026-08-04

## 目标

在现有在线流媒体页中加入 Apple Music，并把歌曲/专辑浏览能力统一扩展到 QQ、酷我、酷狗、网易云和 Apple Music 五个平台。用户可以按歌曲浏览，也可以按专辑浏览；进入专辑详情后播放整张专辑或单曲下载，专辑列表支持长按下载整张专辑。

## 范围

包含：

- Apple Music 妖狐 API 搜索和单曲解析。
- 五个平台共享“歌曲 / 专辑”两个标签。
- 使用同一次搜索的分页结果按专辑名聚合。
- 专辑详情页、播放全部、单曲播放和单曲下载。
- 专辑列表长按下载整张专辑，并提供等价的 TalkBack 自定义操作。
- 整专辑逐首下载、进度播报、部分失败汇总。
- 相关纯逻辑单元测试、编译和设备冒烟验收。

不包含：

- Apple Music 账号、Cookie、订阅凭据或登录。
- 妖狐未提供的独立 Apple 专辑 ID/专辑详情接口。
- 将在线 URL 写入持久化队列、播放列表或日志。
- 修改 DLNA、Chromecast、蓝牙和本地导入流程。
- 为 Apple 猜测未在文档中确认的音质参数或接口字段。

## 约束与事实

- API 根地址仍为 `https://api.yaohud.cn`。
- 密钥只从未提交的 `android/local.properties` 的 `YAOHU_API_KEY` 注入 `BuildConfig`；源码、测试、日志、截图和文档不得包含真实密钥。
- Apple 接口为 `GET /api/music/apple`，文档确认参数为 `msg`、`n`、`g`、`loc`、`type`。搜索结果位于 `data.songs`，至少包含 `n`、`name`、`singer`、`album`；单曲解析结果使用 `music_url`，并可读取 `songname`、`singername`、`album`、`cover`。
- 当前妖狐接口没有独立专辑 ID 查询。专辑由当前平台、当前关键词的分页歌曲结果按非空 `album` 名称聚合；“整张专辑”表示本次搜索分页实际返回的该专辑歌曲。
- 所有 Android 命令遵守仓库规则：命令以 `rtk` 开头；构建从 `D:\musicplayer\android` 执行；文件修改使用 `apply_patch`；不创建 worktree、不自动提交。

## 方案

采用客户端统一聚合方案。

每个平台 Catalog 只负责平台自己的搜索和单曲解析。各 Catalog 的搜索结果继续转换为统一的 `OnlineTrack`，然后由共享纯函数按 `platform` 和规范化专辑名生成 `OnlineAlbum`。这样五个平台共享一套 UI、队列、播放和下载行为，平台字段不会泄漏到 Compose 层。

不采用点击后再次按专辑名搜索，因为同名专辑可能混入其他结果，且会让歌曲标签和专辑详情来自不同请求。不采用未在妖狐文档中提供的专辑接口，因为没有稳定的请求参数和返回契约。

## 用户流程

### 搜索和标签

1. 用户选择一个平台并提交非空关键词。
2. ViewModel 清空当前结果，按当前平台分页搜索，并合并去重后的 `OnlineTrack`。
3. “歌曲”标签显示歌曲列表，沿用现有点击播放、长按显示下载按钮和 TalkBack 自定义下载操作。
4. “专辑”标签显示 `OnlineAlbum` 列表。专辑名、主要歌手/多位歌手、封面和歌曲数量来自分组内歌曲。
5. 切换标签只改变当前展示，不发起网络请求；焦点、TalkBack 浏览和选中状态变化不触发搜索、解析或下载。

当搜索的是专辑名时，歌曲标签仍显示接口返回的相关歌曲，专辑标签按这些歌曲的专辑字段去重显示。

### 专辑详情

点击专辑列表项进入专辑详情页。详情页展示专辑信息和该专辑歌曲列表，提供“播放全部”显式操作。

详情页歌曲行为与歌曲搜索列表一致：点击播放当前歌曲；长按显示下载按钮；同时提供 TalkBack 自定义操作“下载”。下载单曲沿用现有在线下载流程。

系统返回、页面返回按钮和 Compose 无障碍焦点按现有子页面规则返回专辑列表，并恢复到进入详情前的专辑项；专辑被移除时回退到列表中的稳定位置。

### 下载专辑

专辑列表项长按后显示“下载整张专辑”按钮，并为同一操作提供 TalkBack 自定义操作。下载必须由显式点击或自定义操作触发，不能由焦点移动触发。

整专辑下载按详情中的歌曲顺序串行执行：每首歌单独解析指定下载音质、写入 `Music/汪汪播放器/`、成功后完成 MediaStore 条目；某首失败时删除该首 pending 条目并继续后续歌曲。任务期间禁用该专辑的重复下载，在线页显示“正在下载第 X/Y 首”。结束时报告成功数和失败数；成功歌曲保留，失败歌曲不留下半成品。

## 数据模型与数据流

### Apple Catalog

新增 Apple 专用质量/结果模型时只使用接口文档确认字段。Apple 搜索模型至少包含：

- 原始关键词。
- 服务端序号 `n`。
- 歌名、歌手、专辑。
- 可选封面。

Apple 解析模型至少包含播放 URL、`m4a` 扩展名和 `audio/mp4` MIME；只有接口实际返回其他格式时才按返回值映射。解析 URL 为空、业务 `code` 非 200、响应字段无效都转换为不泄露密钥的中文 `YaohuApiException`。

Apple 结果转换为 `OnlineTrack(platform = Apple, ...)`。Apple 不新增未确认的质量菜单；若接口文档未明确 `type` 的取值，搜索和解析不发送猜测值。

### 统一专辑模型

`OnlineAlbum` 是 UI 和 ViewModel 使用的共享模型，包含：

- 平台。
- 当前搜索关键词。
- 稳定的专辑 key。
- 显示专辑名。
- 专辑歌曲列表，保持接口分页合并后的顺序。
- 封面 URL。
- 去重后的歌手显示值。

专辑 key 使用平台和去除首尾空白后的专辑名；空专辑名不生成专辑项。相同平台中相同专辑名只生成一个专辑项；不同平台即使同名也必须分开。分组时使用 `OnlineTrack.key` 去重，防止分页重复结果造成重复歌曲。

### 播放

歌曲播放和专辑“播放全部”都建立当前进程内的在线临时队列。队列中的在线曲目继续由 `onlineTrackById` 保存原始平台结果，解析后只将短期 URL 交给 BASS。播放 URL 只进入进程内缓存，不写入 SharedPreferences、播放列表、恢复队列、日志或 Cast 历史。

专辑播放复用现有 `playOnline(tracks, index)` 和在线单曲播放逻辑，不创建第二个播放器。在线播放失败停留在当前曲目，不因一个解析失败自动跳过整张专辑。

### 缓存与刷新

解析缓存 key 继续包含平台、曲目标识和请求音质。Apple 由于没有确认的音质选择，使用固定的 Apple 解析配置作为缓存维度。BASS 打开缓存 URL 失败时先驱逐缓存，再按现有有限次数重新解析。URL 不持久化。

## 文件边界

- `android/app/src/main/kotlin/com/example/local_music_player/YaohuOtherMusicApi.kt`
  - 保留共享 `OnlineTrack`、`OnlineResolvedTrack` 和通用响应工具；不要把 Apple 专有字段塞进 QQ/酷我/酷狗模型。
- `android/app/src/main/kotlin/com/example/local_music_player/YaohuNeteaseApi.kt`
  - 扩展 `MusicPlatform` 为 Apple，并保持现有网易云 Catalog 行为不变；如新增共享枚举导致文件职责过重，可将平台枚举移到现有共享在线文件，但不引入单实现工厂。
- `android/app/src/main/kotlin/com/example/local_music_player/YaohuAppleMusicApi.kt`
  - 新增 Apple 专用质量/搜索/解析模型、Catalog、JSON 字段映射和 `OnlineTrack` 转换。
- `android/app/src/main/kotlin/com/example/local_music_player/NativeMusicViewModel.kt`
  - 增加 Apple Catalog 接入、共享专辑聚合状态、标签状态、专辑详情返回目标和整专辑串行下载协调；复用现有播放、缓存、MediaStore 下载和错误状态，不修改 BASS 播放协议。
- `android/app/src/main/kotlin/com/example/local_music_player/OnlineScreen.kt`
  - 增加“歌曲 / 专辑”标签、专辑列表、专辑长按/无障碍下载操作，并将专辑点击交给页面导航。
- `android/app/src/main/kotlin/com/example/local_music_player/MusicApp.kt`
  - 增加专辑详情页面导航、返回焦点目标和页面标题；保留现有歌曲列表与设置页行为。
- `android/app/src/test/kotlin/com/example/local_music_player/YaohuAppleMusicApiTest.kt`
  - 覆盖 Apple 搜索字段、解析字段、请求参数、空 URL 和业务错误。
- `android/app/src/test/kotlin/com/example/local_music_player/OnlineAlbumTest.kt`
  - 覆盖五平台分组隔离、空专辑跳过、分页去重、专辑 key 稳定性和详情歌曲顺序。
- `android/app/src/test/kotlin/com/example/local_music_player/OnlinePlatformStateTest.kt`
  - 扩展平台顺序、Apple 标签/偏好隔离和在线队列不持久化断言。

## 错误处理

- 搜索超时、HTTP 错误、业务 `code` 非 200、JSON 无效、空 URL 和 BASS 打开失败均显示简体中文状态，不显示完整请求 URL、密钥或异常堆栈。
- Apple 搜索无结果时两个标签都显示可感知的空状态；专辑标签只显示有有效专辑名的歌曲分组。
- 整专辑下载失败不删除已成功歌曲；pending 条目在单曲失败时立即删除，结束时汇总失败数量。
- 下载中禁用同一专辑/单曲的重复操作，完成和失败通过可见状态及 Compose `liveRegion` 反馈。
- 网络请求和下载只从显式点击、键盘提交、播放操作或无障碍自定义操作触发。

## 无障碍要求

- 标签使用清晰的“歌曲”“专辑”语义，并暴露选中状态；切换标签不执行网络操作。
- 专辑列表项使用一个可操作语义节点，显示专辑名、歌手和歌曲数量；子文本不制造重复焦点节点。
- 专辑项的长按下载必须有可见下载按钮和 TalkBack 自定义操作“下载整张专辑”。详情页歌曲保持“下载”自定义操作。
- 所有操作目标至少 `48dp`；状态、下载进度、错误和完成结果使用可见中文文本及 polite live region。
- 从专辑详情返回时恢复专辑列表滚动位置和可预测的无障碍焦点；不因搜索结果刷新抢夺焦点。
- 验收覆盖键盘/D-pad、TalkBack、大字体、小屏和灰阶模式。

## 测试与验收

实现前按 TDD 先增加失败测试，再实现最小代码。至少运行：

```powershell
rtk git diff --check
rtk cmd /c "gradlew.bat :app:testDebugUnitTest"
rtk cmd /c "gradlew.bat :app:compileDebugKotlin"
rtk cmd /c "gradlew.bat :app:assembleRelease"
```

构建工作目录必须为 `D:\musicplayer\android`。

设备冒烟覆盖：

- 五个平台歌曲搜索和播放。
- 五个平台歌曲/专辑标签切换，确认切换不重复请求。
- Apple 搜索歌曲名和专辑名，确认歌曲与专辑结果分别显示。
- QQ、酷我、酷狗、网易云的专辑聚合和详情页。
- 专辑详情播放全部、单曲播放、单曲下载。
- 专辑列表长按下载整专辑，验证进度、部分失败和重复操作禁用。
- TalkBack 标签、专辑项、自定义下载操作、详情返回焦点和 live region。
- 回归本地播放、DLNA、Chromecast、蓝牙路由及 logcat 无本应用 FATAL/ANR。
