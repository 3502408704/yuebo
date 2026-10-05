# 5sing（中国原创基地）伴奏源接入设计

日期：2026-08-15
状态：已确认（用户确认需求：1A 搜索+在线播放+下载；2B 伴奏为主、可切换 全部/原创/翻唱/伴奏）

## 1. 背景与目标

用户需要一个平台来获取伴奏（K 歌/翻唱用的纯伴奏曲）。5sing 原创音乐基地（酷狗旗下，`5sing.kugou.com`）按「原创(yc)/翻唱(fc)/伴奏(bz)」分类，提供海量免费伴奏。本次把 5sing 接入现有「在线音乐」体系：

- 搜索 5sing 伴奏（默认）并可在 全部/原创/翻唱/伴奏 间切换；
- 点击在线播放（BASS 直接播放 5sing 返回的 mp3 地址）；
- 可下载到本地（复用现有 DownloadEngine）；
- 播放/下载地址解析走 5sing 官方 `song/getsongurl`（需 MD5 签名，已实测可行）。

## 2. 需求确认记录

- 功能范围：搜索 + 在线播放 + 下载（全链路）。
- 内容范围：伴奏为主；搜索页可切换 全部/原创/翻唱/伴奏，默认伴奏。
- 不新增第三方依赖；不引入 ShyMusic/妖狐（5sing 无对应源）。

## 3. 5sing API 逆向结论（2026-08-15 实测）

### 3.1 搜索（无需签名）

- `GET http://search.5sing.kugou.com/home/json`
- 参数：`keyword`、`sort=1`、`page`、`filter`、`type=0`、`from=web`
- `filter`：0=全部、1=原创、2=翻唱、3=伴奏（实测分别只返回 yc/fc/bz）
- 响应：`{"list": [...], "pageInfo": {"cur":1,"totalCount":N,"totalPages":M}}`
  - 无结果时 `list` 为 `null`（不是空数组），`totalCount=0`
  - list 项字段：`songId`、`songName`（含 `<em class="keyword">` 高亮需剥掉）、`originSinger`（原唱）、`singer`/`nickName`（上传者）、`singerId`、`type`（3=伴奏）、`typeEname`（bz/yc/fc）、`typeName`（伴奏）、`songurl`、`downloadurl`、`ext`、`songSize`、`playCnt`、`popularityCnt`、`createTime`
  - 每页 10 条

### 3.2 播放/下载地址（需 MD5 签名，实测返回可播 mp3）

- `GET https://5sservice.kugou.com/song/getsongurl`
- 参数（除 signature 外全部必带）：
  - `appid=2918`、`clientver=1000`、`mid=<随机 32 位 hex>`、`uuid=<同上>`、`dfid=-`
  - `songid`、`songtype`（bz/yc/fc，与 typeEname 一致）、`version=6.6.72`
  - `clienttime=<当前毫秒时间戳>`
  - `signature`
- 签名算法（来自 5sing 公开前端 inf_sign-2.0.0.min.js，已逆向并实测）：
  - `secret = "5uytoxQewcvIc1gn1PlNF0T2jbbOzRl5"`
  - 取全部 GET 参数（H5 路径下 `srcappid=2919` 会被删除，因为传入了 appkey），按 key 字典序排序，拼成 `k1=v1k2=v2...`（直接拼接无分隔符）
  - `signature = md5(secret + 拼接串 + secret)`
  - `mid`/`uuid` 任意随机 md5 hex 即可
- 响应：`{"code":0,"success":true,"data":{"songid","songtype","lqurl","hqurl","squrl","songName","songKind","user":{"ID","NN"},"DigitalAlbumID"}}`
  - 免费歌：`lqurl` 至少存在；部分有 `hqurl`/`squrl`；均为 mp3（实测 128kbps）
  - 付费数字专辑：`code==200049` 或 `DigitalAlbumID!=0` → 需购买，中文提示

### 3.3 音质

- 三档映射：`squrl`→无损、`hqurl`→极高、`lqurl`→标准；按用户选择优先取对应档，缺失逐级降档。
- 排除 `wav`/`wma` 扩展名（前端播放器同样排除）。

### 3.4 兜底源调研（2026-08-15 追加）

- **MusicFree 5sing 插件（猫头猫）**：搜索与播放地址走**与本文案完全相同**的接口与签名（`search.5sing.kugou.com/home/json` + `5sservice.../song/getsongurl` + secret `5uytoxQewcvIc1gn1PlNF0T2jbbOzRl5`），等于验证本实现即社区标准做法，非独立兜底。
- **`guohuiyuan/music-lib`（Go）**：使用**免签名**接口 `http://mobileapi.5sing.kugou.com/song/getSongUrl?songid=&songtype=`（`code:1000`，字段 `squrl/hqurl/lqurl` + `_backup`）。**已实测 yc/fc/bz 均返回可播 mp3，作为播放地址的独立兜底源**。
- 其他第三方解析站（tianqiapi/tjit 等）需 key/收费，不采用。
- **歌词接口**（MusicFree 插件暴露，已实测）：`http://5sing.kugou.com/fm/m/json/lrc?songId=&songType=` → `{"lrc":{"data":"<br/>分隔的歌词"}}`。

## 4. 模块设计

### 4.1 新文件 `FiveSingApi.kt`

- `FiveSingException(message)`：中文错误，参照 `YaohuApiException`。
- `FiveSingPaidException(message) : FiveSingException`：付费数字专辑的最终结论，不参与兜底回退。
- `interface FiveSingHttp { fun get(url: String, parameters: Map<String, String>): String }`。
- `class FiveSingApiClient()`：HttpURLConnection，超时 10s/15s。
- `enum class FiveSingFilter(val apiValue: Int, val label: String, val typeEname: String)`：`All(0,"全部","")`、`Original(1,"原创","yc")`、`Cover(2,"翻唱","fc")`、`Accompaniment(3,"伴奏","bz")`。
- `class FiveSingCatalog(private val http: FiveSingHttp = FiveSingApiClient())`：
  - `search(query, filter, limit)` → `List<OnlineTrack>`（按页翻，凑够 limit；platformId 编码 `"$typeEname|$songId"`）
  - `resolve(track, quality)` → `OnlineResolvedTrack`（主源签名 getsongurl；失败/无地址回退免签名 mobileapi；付费不回退）
  - 内部 `signature(parameters)`、`stripTags()`、`pickUrl()`、`extensionForUrl()`、`mimeForExtension()`

### 4.2 修改点

- `YaohuOtherMusicApi.kt`：`MusicPlatform` 增加 `FiveSing("5sing")`；4 处 exhaustive `when(platform)` 补 FiveSing 分支：
  - `yaohuApiValue`、`onlineQualityFromApiValue`（返回 null/标准均可）
  - `ShyMusicApi.kt: shyQualityFor`（归入 OnlineQuality 分支）
  - `OnlineTrackResolver.resolveViaYaohu`：FiveSing → `fiveSingCatalog.resolve`
- `OnlineTrackResolver`：`resolveShy` 对 FiveSing 直接 return null（5sing 无 ShyMusic 源，避免无效请求）。
- `NativeMusicViewModel`：
  - state 增加 `fivesingFilter: FiveSingFilter = FiveSingFilter.Accompaniment`
  - `selectOnlinePlatform`：切到 FiveSing 重置 filter 为默认并重搜
  - `fetchOnlineSearch`：FiveSing → `fiveSingCatalog.search(query, filter, limit)`
  - `loadOnlineAlbums`：FiveSing 返回 empty（专辑页签隐藏，防御）
  - 新增 `setFiveSingFilter(filter)`：改 filter 后重搜当前 query
  - `requestDownloadOnline`：FiveSing 走质量提示（同其他平台）
- `OnlineScreen.kt`：
  - 平台下拉新增 5sing（枚举自动带出）
  - `platform == FiveSing` 时显示筛选行（全部/原创/翻唱/伴奏，默认伴奏），点击回调 `onSelectFilter`
  - `preferredOnlineViewMode(FiveSing)` → Songs
  - 隐藏 5sing 的「专辑」页签（只显示「歌曲」）
- `MusicApp.kt`：把 `setFiveSingFilter` 接到 OnlineScreen 回调。

### 4.3 数据流

```text
OnlineScreen 筛选行 → setFiveSingFilter(filter) → searchOnline(query)
  → fetchOnlineSearch(FiveSing) → FiveSingCatalog.search(query, filter, limit)
  → OnlineTrack(platformId="bz|123", artist=原唱, ...)
点击播放 → playOnline → OnlineTrackResolver.resolve(FiveSing)
  → FiveSingCatalog.resolve(track, quality) → getsongurl(签名) → mp3 URL → BASS
下载 → requestDownloadOnline → 质量提示 → DownloadEngine → 同一 resolve
投送 → resolveCastSource → 同一 resolve → 直接 http mp3
```

## 5. 错误与边界

- 搜索空结果（`list:null`）→ "未找到相关伴奏"（复用现有 status）。
- 网络/解析失败 → "在线搜索失败，请稍后重试"。
- 付费伴奏（200049 / DigitalAlbumID!=0）→ "该伴奏需在 5sing 购买后才能播放/下载"。
- 播放失败 → 现有兜底文案。
- 5sing 返回的 `songName` 含 `<em>` 标签，需剥掉后再展示。

## 6. 测试

新增 `android/app/src/test/kotlin/com/example/local_music_player/FiveSingApiTest.kt`：

- 搜索解析：字段映射（title 剥标签、artist=原唱、platformId 编码）、空 `list:null`、filter 映射、分页翻页凑 limit。
- 签名：固定参数下 signature 与独立实现一致。
- 播放地址解析：lq/hq/sq 选档、wav/wma 排除、付费 200049、DigitalAlbumID!=0。
- 兜底：签名源失败/无地址时回退 mobileapi；付费不触发兜底。
- `platformId` 编解码（`"bz|123"` → type/songId）。

## 7. 风险

- 签名 secret 与参数来自 5sing 公开前端 JS，属非官方接口；若 5sing 改签名/加风控需同步更新（与现有 ShyMusic/妖狐源同类风险）。
- 部分伴奏为付费数字专辑，无法免费播放/下载。
- 5sing 无封面图字段，搜索结果走占位图。
