# 客户端优化总体规划（子项目③b+④，2026-08-27）

状态：规划稿（等用户确认优先级后逐轨执行；每轨独立走 brainstorm→计划→实现→验证）。
前置事实：③a 上线后服务端已具备「平台清单下发、搜索/目录缓存、预算超时、失败归因日志」能力；本规划是这些能力的客户端侧承接 + 遗留缺陷修复。

## P0 基线采集（2026-08-27 首轮已完成，模拟器 emulator-5554 / Android 15 / Codex_API35_UI）

已产出（`docs/client-baseline/`）：启动+公告弹窗、在线页默认喜马拉雅、网易云搜"love"结果九宫格、本地存储页截图；关键 UI 树文本全程采集。

首轮实证结论：
1. **服务端连通性全通**：公告拉取、11 站点清单下发、网易云歌曲搜索 200 九条结果——③a 全部改动在真客户端上生效。
2. **T3 排除一个假设**：点击第二首时 `playOnline startIndex=1 titles=[...]`、`startLocalPlaybackNow index=1 title=LOVE.` ——**选曲与状态提交层正确**（OnlineDebug 日志实证）。缺陷必然在更下游（resolve 网络等待/BASS 打开/自动前进链），下轮用「服务器 journal 访问观察窗 + usage 计数前后差 + logcat」三件套在真机上定案。
3. **新发现缺陷 N1（导航）**：播放页连按两次系统返回会把整个 Activity 退出到桌面而非回曲库——与 AGENTS「返回只关当前页」约束冲突，需修。
4. **新发现缺陷 N2（权限向导回归）**：fresh 安装后向导跳过未完成"所有文件访问"时，每次冷启动都重新拦截主界面且无记忆提示；配合"应用从桌面二次进入直接重定向播放页但权限面板又在最上层"，形成卡死体感。与 T5 稳定性合并修。
5. **构建环境实况修正**：本机 `D:\musicplayer -> D:\本地音乐播放器` 联接目标不存在（断链），AGENTS 的构建路径规则失效。实际采用：仓库内 ASCII 路径 `D:\wangwangplayer\android` + 本机 Gradle 发行版缓存 `~/.gradle/wrapper/dists/gradle-9.1.0-all` 直调（仓库 wrapper 脚本缺失、仅有 properties）。APK 输出被 Gradle 9 重定向到根级 `build/app/outputs/apk/debug/`。→ 待用户决定：重建联接指向 `D:\wangwangplayer`，还是更新 AGENTS 构建章节为当前路径。
6. 模拟器上 BASS 扩展插件全部加载失败（错误码 2）走系统解码兜底——不影响 mp3/aac 在线流验证，本机有 x86_64 jniLibs，具体插件名映射待 T3 执行期核对。

## P0 基线采集（所有轨道的第一步，半天）

用 test-android-apps:android-emulator-qa 流程跑一次基线：当前 beta 包装上模拟器，截取 曲库/播放页/在线页/设备页/下载页 五屏截图与 UI 树，抓一次完整在线听歌 logcat（搜索→点歌→resolve→BASS 打开）。产物：
- `docs/client-baseline/` 截图集与问题清单；
- **两缺陷的直接证据**：网易云点第 2/3 首时的 `/v1/resolve` 请求体序列、汽水播放失败瞬间 BASS 错误码。
Android 构建/安装一律从 `D:\musicplayer\android` 走 rtk 流程。

## T3/T4 修复实录（2026-08-27 晚间批次，服务端已部署）

**T3 网易云“永远播第一首”**：根因＝org.json `optString` 对显式 JSON null 返回字面量 `"null"`，客户端把 `"platform_id":"null"` 回传 → 服务端解析键坍缩为 `v1:Netease:null:*`。修复：客户端 `decodeOnlineTrack` 可空字段改 `isNull()` 防护（含 artwork_url 同坑）；服务端 `OnlineTrack._literal_null_to_none` validator 兜底历史包。实证：缓存表同 query 三首各持 `…|artist|album|<seq>` 独立键；新增回归 `test_resolve_keys_differ_across_netease_tracks_despite_null_ids`。

**T4 汽水无法播放（最终定论，2026-08-27 晚）**：用户"改默认音质即可播放＋无音质切换"的观察引导出上游实测结论——shybot `qishui_url` 仅 qn=0 返回 douyinvod 可直连实体流，qn=1/2 是 `proxy_qishui_decrypt` 加密代理链（设备端 BASS 47）。修复组合：① 服务端对 Qishui 锁定 qn=0 单档解析 + 双 UA 头嗅探通过后把中转链改写为最终实体直链下发；② yaohu 备用源优雅降级；③ 客户端 BASS `NET_AGENT` 设浏览器 UA。生产实测：首解 ~0.8s、缓存命中 ~20ms、下发热链绝迹。真机请以最新 debug 包复验。

**遗留下轮**：N1（双返回退出）与 N2（权限向导反复拦截）修复；全文件 optString(null) 同类审计（decodeOnline* 各字段与其它 JSON 解析点）；若需 release 包验证 R8 是否影响 OpenSSL/BASS 加载另开小批。

## T3｜修复：网易云搜索列表永远只播第一首【双端根因已锁定】

服务端 `_resolve_key` 对网易云（无 platform_id，yaohu `ID_FIELDS[Netease]=(None,)`）回退到 `title|artist|album|sequence` 拼 key——代码注释里明确警告过“缺 sequence 会命中同键缓存导致永远播放第一个解析地址”，这正是用户症状。因此键退化必然来自**客户端对每行传了相同 sequence（或空字段）**。

排查路径：P0 抓包对比三行不同歌曲的 resolve JSON → 定位 sequence 装配点（NativeMusicViewModel 在线队列构建处）→ 客户端修复为使用行序真实 sequence → 服务端补一条回归测试：「两条不同 sequence 的网易云请求必须得到不同的解析结果」。验收=真机网易云列表任意点击即播对应曲目，且服务端日志不再出现同 key 复用。

## T4｜修复：汽水音乐无法播放【按证据分流】

假说排序（Phase0 定案）：
1. shybot `qishui_url` 返回的 https 直链带时效签名——从搜索到点播间隔过长或二次解析失败；
2. CDN 需要 Range/UA 而 BASS 请求被拒（对照 curl -I 带 Range 探测 play_url）；
3. 音质降级链返回 `not` 被静默映射成不可用地址；
4. SSL 配置回归（此前 0.6.9 已修过 libssl 加载，需确认仍生效）。

路径：服务器侧先 probe `/v1/resolve{Qishui}` 拿 play_url → curl 直接拉流头验证可达性与签名时效 → 模拟器复现抓 BASS 错误码（10=SSL/2=fileopen/41=format 分流）。修复面视结论落在 provider 归一化、重签时效提示（客户端遇失效地址自动重解析一次），或编码兜底。

## T1｜通信安全（依赖外部条件：域名）

- 必做：域名解析到 124.223.159.230 后配 Let's Encrypt（Nginx TLS 或换 Caddy 全托管）→ Android `ONLINE_MEDIA_BASE_URL` 切 https；同步把 update.json 的下载域名评估进同一证书体系。
- 可选加固：Network Security Config 对该域做证书锁定（pin 中间层需权衡续期风险，默认建议只锁站点公钥备用名单不强制）。
- 客户端本地令牌迁移 EncryptedSharedPreferences（现状待查证，若已是普通 SP 则列改造项）。
- 运维遗留勾销：服务器控制台密码重置（你操作）+ SSH 仅密钥登录（我来改 sshd）。

## T2｜服务端衍生新功能池（等你勾选后细化）

1. 站点维护模式公告联动：管理台禁用某平台时，客户端在线页对该入口显示维护中文文案（复用 announcements 通道或新 /v1/platforms 字段不建议动协议——直接用现有 enabled=false + 本地文案映射即可，零协议变更）。
2. 「新歌速递/榜单」接口试点（若上游有稳定源）。
3. 下载任务云端校验（hash 回传）。
以上均为候选，不默认全做。

## T5｜稳定性增强（贯穿各轨的质量底线）

- 崩溃红线持续执行：logcat 无 FATAL/ANR 才算过；`LocalMediaServer` IOException 吞除审计复查一遍既有 catch 面。
- DownloadService 网络恢复重试已在 Fetch2 层；补「前后台切换时在线播放进度恢复」真机断言。
- AppErrorRecorder 导出报告纳入每轮验收必附项。

## T6｜UI 视觉优化（模拟器截图自查驱动）

Phase0 截图 → 按 DesignTokens.kt 一致性清单出「视觉差异表」（间距/圆角/色彩/暗色模式偏差/TalkBack 对比度复检）→ 分两批落地小步改动（批次 A 结构性统一、批次 B 微调），每批过无障碍门槛并在最大字体下回归关键流。可选配合 superdesign 画布出对比稿再动手。

## 建议排期

| 序 | 轨 | 前置 | 粗估 |
|---|---|---|---|
| 1 | P0 基线采集 | 无 | 半天 |
| 2 | T3 网易云第一首修复 | P0 | 1 天内 |
| 3 | T4 汽水无法播放 | P0 | 1 天内（按证据可延伸） |
| 4 | T6 UI 视觉批次A | P0 | 2–3 天 |
| 5 | T1 通信安全 | **你提供已解析域名** | 半天起 |
| 6 | T5 贯穿验收 | 随各轨 | 并行 |
| 7 | T2 功能池 | 你勾选 | 另行细化 |

每轨完成后照例贴证并请求批准提交；β 版本号递增策略届时按发布门槛走。
