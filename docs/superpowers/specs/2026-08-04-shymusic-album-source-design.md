# ShyMusic 在线源接入设计

日期：2026-08-04
状态：已确认（用户确认需求与选项）
关联：`docs/handoff/2026-08-02-yaohu-api-integration.local.md`（妖狐规则，密钥不进 git）

## 1. 背景与目标

当前在线音乐只有妖狐一个源：搜索按平台返回，专辑是"按专辑名搜索凑出来的分组"（`groupOnlineAlbums`），专辑曲目不准确。用户提供 ShyMusic（shybot.top）账号并续费 Music VIP，要求把 ShyMusic 作为**在线媒体主源**：

- 歌曲搜索、专辑搜索、真实专辑曲目解析、音质切换、播放地址、歌词全部走 ShyMusic；
- **妖狐只做同平台兜底**：ShyMusic 解析失败（返回 `not`、无播放地址、目标音质不可用）时，用妖狐同平台获取播放/音质；
- **删除跨平台候选逻辑**：不换歌、不跨平台；
- 不展示 SVIP 音质（最高档只到无损 flac）。

## 2. 需求确认记录

- 专辑解析：以 ShyMusic 真实专辑接口为准，不再用搜索结果凑专辑；
- 高音质：ShyMusic 为主，解析失败用妖狐同平台兜底，但要快；
- 平台下拉保留（QQ/酷我/酷狗/网易云），选定平台后全流程闭环；
- 去除跨平台逻辑。

## 3. ShyMusic API 逆向结论（2026-08-04 实测）

基址：`https://shybot.top/v2/music/api/`，所有 GET 请求统一带 `shykey=<key>`（`BuildConfig.SHYMUSIC_API_KEY`，来源 `local.properties`，git 忽略）。key 由账号后台"开发者"页生成。会员配额约 1200 次/天播放下载、总量 10000。

### 3.1 搜索

- 歌曲：`type=<平台>&name=<q>&page=1&limit=20`，返回数组。公共字段 `name/singer/album/pic/qn/url/url_lrc/duration`；平台标识：qq=`mid`，wyy=`id`，kg=`hash`，kw=`id`(字符串)。
- 专辑：`type=<平台>_album_search&name=<q>&page=1&limit=20`，返回数组。qq 用 `album_mid`（`url` 指向 `qq_album_info_list`），kg/kw/wyy 用 `id`；含 `name/singer/pic/song_count/time/desc`。

### 3.2 专辑详情与曲目

- wyy：`wyy_album_info&id=` → `data[0]` 为专辑信息，曲目在 `data[0].data`（数组）。曲目含 `id/name/album/singer/pic/url/url_lrc/duration/qn`。
- qq：`qq_album_info&id=<album_mid>` → `data` 为专辑信息；曲目 `qq_album_list&id=<album_mid>&page=1&limit=9999` → `data` 数组，曲目含 `mid/name/singer/pic/url/url_lrc/qn/duration`。
- kg：`kg_album_info&id=` → `data[0]` 专辑信息；曲目 `kg_album_list&id=&page=&limit=9999` → `data` 数组（含 `hash`）。
- kw：`kw_album_info&id=` → `data[0]` 专辑信息；曲目 `kw_album_list&id=&page=&limit=9999` → `data` 数组（含 `id`）。

### 3.3 播放地址

- `type=<平台>_url&<平台标识>&qn=<下标>`：qq=`mid`，wyy=`id`，kg=`hash`，kw=`id`。
- 返回：直接音频 URL 字符串；失败返回 `not`（VIP/付费曲/音质不可用）。
- `qn` 是曲目 `qn` 数组下标：0=普通（不传 qn），1=高品质，2=无损 flac。qq 特征：qn=0→M500，1→M800，2→F000(flac)。
- 音质档位接口：`wyy_song_qn&id=` → `{"data":[{"qn":"128"},{"qn":"320"},{"qn":"flac"}]}`（higH/master 为 SVIP 不展示）；`kg_song_qn&hash=` → `{"data":[[...]]}` 数组套数组（128/320/flac + viper_* SVIP）；qq/kw 无音质接口，用曲目 `qn` 数组前三档（`{master/atmos/hifi}` 对象视为 SVIP 跳过）。

### 3.4 歌词

- `type=<平台>_lrc&id=<标识>`（qq 用 mid，kg 加 `f=1`）→ `{"lyric":"..."}`。

## 4. 模块设计

### 4.1 新文件 `ShyMusicApi.kt`

- `ShyMusicException(message)`：中文错误，参照 `YaohuApiException`。
- `interface ShyMusicHttp { fun get(path: String, parameters: Map<String, String>): String }`。
- `class ShyMusicApiClient(apiKey = BuildConfig.SHYMUSIC_API_KEY)`：GET 基址、超时 10s/15s、失败抛 `ShyMusicException("网络请求失败，请稍后重试")`。
- 音质枚举：四平台 `ShyQqQuality`/`ShyWyyQuality`/`ShyKgQuality`/`ShyKwQuality`（`M500/M800/F000`、`128/320/flac`），统一 `interface ShyQuality { val qnIndex: Int; val label: String }`。
- 模型：`ShyTrack(platform, platformId, title, artist, album, albumId, artworkUrl, qnSizes, lyricUrl, durationMs)`、`ShyAlbum(platform, albumId, name, artist, artworkUrl, songCount, time, desc, tracks)`、`ShyResolvedTrack(playUrl, quality, mimeType, extension, lyrics)`。
- `class ShyMusicCatalog(private val http: ShyMusicHttp = ShyMusicApiClient())`：
  - `searchSongs(platform, query, limit)` → `List<ShyTrack>`
  - `searchAlbums(platform, query, limit)` → `List<ShyAlbum>`（无曲目）
  - `albumTracks(platform, albumId)` → `List<ShyTrack>`（按平台 `_album_info`/`_album_list`）
  - `resolveUrl(track, quality)` → 校验 `not` → `ShyResolvedTrack`
  - `lyrics(track)` → 歌词文本
  - `availableQualities(track)` → 过滤 SVIP 的音质列表
  - 平台参数名：qq=mid、wyy=id、kg=hash、kw=id。

### 4.2 兜底（同平台）

- 删除 `onlineFallbackPlatforms`、`exactOnlineFallbackCandidates`、`findExactOnlineFallbacks`。
- 新增同平台兜底：ShyMusic 解析失败（异常/`not`/无地址）时，用妖狐同平台目录解析**同一曲目**（`NeteaseCatalog`/`QqCatalog`/`KugouCatalog`/`KuwoCatalog`），不换歌、不跨平台。
- 解析缓存按 `(平台, 曲目 key, 音质)` 隔离；ShyMusic 与妖狐缓存分开，避免互相污染。

### 4.3 ViewModel（`NativeMusicViewModel.kt`）

- `fetchOnlineSearch`：主源改 ShyMusic；移除跨平台补搜；保留分页。
- 专辑 Tab：新增真实专辑搜索与详情状态（`onlineAlbums`、`onlineAlbumDetail`：加载中/失败/重试）；删除 `groupOnlineAlbums` 调用。
- 播放：`playOnline` 队列不变，解析统一走"ShyMusic → 妖狐同平台"。
- 下载：`downloadOnline`/`downloadOnlineAlbum` 同平台兜底。
- 音质：`onlineQuality(platform, download)` 改用 ShyMusic 档位（qq/kw 内置三档，wyy/kg 从接口取）。

### 4.4 UI（`OnlineScreen.kt`）

- 专辑 Tab 改为真实专辑搜索；专辑行显示封面/名称/歌手/歌曲数。
- `OnlineAlbumDetailScreen`：封面/专辑名/歌手/发行时间/简介 + 曲目列表；加载中进度、失败中文错误 + 重试按钮（TalkBack live region）。
- 专辑操作沿用现有：整专播放、整专下载、单曲播放/下载。

### 4.5 配置

- `build.gradle.kts`：从 `local.properties` 读 `SHYMUSIC_API_KEY`，`buildConfigField("String", "SHYMUSIC_API_KEY", ...)`；确认 `buildFeatures.buildConfig = true`。
- 密钥不进入 git、日志、文档。

## 5. 测试

- `ShyMusicApiTest`（JVM 单元测试，注入 stub `ShyMusicHttp`，不依赖网络）：
  - 四平台歌曲搜索字段映射
  - 专辑搜索解析
  - wyy 专辑详情 `data[0].data` 曲目解析；qq/kg/kw 专辑列表解析
  - `resolveUrl` 成功 / `not` 抛错
  - 音质列表：wyy/kg 接口解析、qq/kw qn 数组推导、SVIP 过滤
  - 歌词解析
- `ShySourceFallbackTest`：妖狐同平台兜底选择逻辑；确认不跨平台。
- 改写旧测试中依赖 `groupOnlineAlbums`/跨平台候选的部分。

## 6. 风险与限制

- ShyMusic key 内置 APK：任何人可提取使用，消耗账号配额（1200/天）；暂接受，后续可做设置页填 key。
- 网易云 VIP 付费曲 ShyMusic 返回 `not`（如《以父之名》id=186014），走妖狐网易云兜底。
- 妖狐接口偶发慢/失败：兜底失败给出中文提示，不自动跨平台。
- 曲目 `qn` 数组含 SVIP 档（master/atmos/hifi 等），UI 不展示、不请求。
- 配额耗尽时给出明确中文提示，可降级为纯妖狐模式（后续）。
