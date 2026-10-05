# 发布 Runbook（月播）

> 发布新版本时按本文件逐步执行。本文件是发布流程的唯一详细手册；摘要见仓库根 `AGENTS.md` §13。
> 路径已于 2026-09-04 校正：`D:\musicplayer` 联接已失效（目标 `D:\本地音乐播放器` 不存在），
> 仓库本体 `D:\wangwangplayer` 即 ASCII 路径，构建与发布仓库都在仓库内。

## 0. 前置事实

- 版本号定义：`android/app/build.gradle.kts` 的 `versionCode` / `versionName`（versionCode 必须单调递增，旧版靠它感知更新）。
- APK 输出（Gradle 9 重定向到仓库根 `build/`）：`D:\wangwangplayer\build\app\outputs\apk\release\app-release.apk`。
- 发布仓库：`D:\wangwangplayer\发布。\local-music-player-releases`（独立 git 仓库，origin 为
  `git@gitcode.com:gcw_PIYVVfKZ/local-music-player-releases.git`）。**只推 SSH**，HTTPS push 会 403/要密码。
- 签名：当前用本机调试签名（`android/key.properties` 指向 `~/.android/debug.keystore`，与用户设备已装版一致，可原地升级）。
  原始正式 keystore（CN=Wangwang Player）已丢失；正式分发前必须重建并妥善备份。
- 更新检查 URL（稳定不变，代码无需改）：`https://gitcode.com/api/v5/repos/gcw_PIYVVfKZ/local-music-player-releases/contents/update.json`。

## 1. 发布步骤

```powershell
# 0. 确认 SSH 可用（~/.ssh/config 已配 Host gitcode.com）
rtk ssh -T git@gitcode.com
# 预期：Welcome to GitCode, gcw_PIYVVfKZ

# 1. 改版本号：android/app/build.gradle.kts 的 versionCode / versionName

# 2. 构建（工作目录 = 仓库根 D:\wangwangplayer；无 gradlew 脚本，直调本机 Gradle 9.1.0）
rtk cmd /c "C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat -p android testDebugUnitTest"
rtk cmd /c "C:\Users\35024\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat -p android :app:assembleRelease"
# 注：docs/handoff/2026-09-03-* 里写的 `android\gradlew.bat` 当前不存在；若日后补了 wrapper 则优先用 wrapper。

# 3. 复制 APK + 生成 update.json（把 X.Y.Z / NNNN 换成实际值）
rtk python -c "
import hashlib, json, shutil, os

apk = r'D:\wangwangplayer\build\app\outputs\apk\release\app-release.apk'
release = r'D:\wangwangplayer\发布。\local-music-player-releases'

# git blob hash（gitcode raw 的 blobs 链接需要）
with open(apk, 'rb') as f:
    data = f.read()
header = f'blob {len(data)}\x00'.encode('utf-8')
blob = hashlib.sha1(header + data).hexdigest()

# 包体文件名只能英文字符 [A-Za-z0-9._-]（2026-09-11 起应用改名「月播」，产物名用 YueboPlayer-）
shutil.copy2(apk, os.path.join(release, 'YueboPlayer-vX.Y.Z.apk'))

update = {
    'versionCode': NNNN,
    'versionName': 'X.Y.Z',
    'apkUrl': f'https://raw.gitcode.com/gcw_PIYVVfKZ/local-music-player-releases/blobs/{blob}/YueboPlayer-vX.Y.Z.apk',
    'apkSize': len(data),
    'sha256': hashlib.sha256(data).hexdigest(),
    'changelog': 'vX.Y.Z: ...',
}
with open(os.path.join(release, 'update.json'), 'w', encoding='utf-8') as f:
    json.dump(update, f, ensure_ascii=False)
print(f'APK blob: {blob}')
"

# 3.5 同步更新清单镜像到服务器（客户端检测更新第一优先源，20260920 起）
rtk scp -i ~/.ssh/id_ed25519_music_server "发布。/local-music-player-releases/update.json"   ubuntu@124.223.159.230:/var/www/update/update.json

# 4. CHANGELOG.md 增加条目；按 docs/release/_template.md 写 docs/release/<版本>.md 验收记录

# 5. 三处一致性校验（versionName/versionCode/SHA-256）
rtk powershell -File scripts/verify-release.ps1

# 6. 推送发布仓库（SSH，main + latest 标签）
rtk git -C "D:\wangwangplayer\发布。\local-music-player-releases" add YueboPlayer-vX.Y.Z.apk update.json
rtk git -C "D:\wangwangplayer\发布。\local-music-player-releases" commit -m "release vX.Y.Z"
rtk git -C "D:\wangwangplayer\发布。\local-music-player-releases" push origin main
rtk git -C "D:\wangwangplayer\发布。\local-music-player-releases" tag -f latest
rtk git -C "D:\wangwangplayer\发布。\local-music-player-releases" push origin latest -f
```

若 push 报 `Access denied` 或要密码：remote 被切成了 HTTPS，先切回再推：

```powershell
rtk git -C "D:\wangwangplayer\发布。\local-music-player-releases" remote set-url origin git@gitcode.com:gcw_PIYVVfKZ/local-music-player-releases.git
```

## 2. 发布后自动提交（强制，无需再问用户）

这是"不自动 commit"硬规则的**唯一例外**。发布成功后立即执行：

1. 更新 `AGENTS.md` §1.1「当前快照」：已发布版本、开发版本、进行中工作。
2. 在 `docs/handoff/archive/release-notes.md` 顶部追加 `## <版本> 发布交接（<日期>）` 小节
   （首句标注"本版已发布：`<versionName>`（`versionCode NNNN`）"；0.6.8 及以后的版本也可以只记在这里）。
3. 自动提交主仓库：`rtk git add`（源码、`AGENTS.md`、`CHANGELOG.md`、`docs/release/<版本>.md`、
   本 runbook 等发布相关文件；**排除临时脚本、构建产物、`发布。/`、用户未跟踪文件**），提交信息 `release vX.Y.Z`。不推送远程。
4. 提交后校验：`rtk git diff --check` 干净、`scripts/verify-release.ps1` 通过。

## 3. 关键坑（历史踩过，别再踩）

- `apkUrl` 必须用 **blob hash 形式** `https://raw.gitcode.com/<owner>/<repo>/blobs/<blob-sha>/<文件名>`；
  `raw/main/` 路径对二进制文件返回 403（0.6.7 首发踩过）。
- 包体文件名**只能英文**：下载端 `URL()` 不支持非 ASCII 文件名；`update.json` 的 `apkUrl` 与实际文件名必须一致。
- `versionCode` 不变的重发布（如换 key 重建）：已装用户**不会收到**应用内更新提示，需手动覆盖安装（0.6.7 百度 key 修复踩过）。
- 版本号三处必须一致：`build.gradle.kts`、`update.json`、`CHANGELOG.md`——`verify-release.ps1` 校验。
- 真机验收未完成不能宣称"设备支持"；设备项可按用户指示延后，但必须记录在 `docs/release/<版本>.md` 待补测。

## 4. 撤回已发布的版本（发出版本有致命缺陷时）

发布仓库只有 `main` 与 `latest` 两个引用；应用内更新读的是 GitCode API 的 `update.json`，所以撤回＝把这两个引用回退到上一个好版本：

```powershell
# 1. 确认上一个好版本提交（例：1.0.2 = 3440a32）
rtk git -C "D:\wangwangplayer\发布。\local-music-player-releases" log --oneline -5
# 2. 硬回退到好版本（删除坏版 APK 与 update.json 改动；坏版 APK 仍在历史里，可随时找回）
rtk git -C "..." reset --hard <好版本提交>
rtk git -C "..." tag -f latest <好版本提交>
# 3. 强推两个引用
rtk git -C "..." push origin main --force
rtk git -C "..." push origin latest -f
# 4. 验证线上 update.json 已回退
curl -s "https://gitcode.com/api/v5/repos/gcw_PIYVVfKZ/local-music-player-releases/contents/update.json"
```

**注意**：已装坏版的用户不会自动降级（versionCode 更高），必须由新版本（更高 versionCode）覆盖修复——
即撤回后要尽快发一个修复版，否则用户停留在坏版本上。`main` 被强推后本地与远程一致，无需额外处理。

## 5. 客户端标识门禁（服务端部署）

自 2026-09-16 起客户端所有 `/v1` 与媒体请求都带 `X-App-Id`（去后缀的包名 `com.yuebo.player`）与
`X-App-Version`（versionCode）。服务端门禁**默认关闭**，在 `.env` 配置后才生效：

```bash
# /opt/local-music-player/.env
ONLINE_MEDIA_APP_CLIENT_ID=com.yuebo.player   # 空串=门禁关闭（默认）
ONLINE_MEDIA_APP_REQUIRED_VERSION=20260915    # 单一可接受 versionCode（精确匹配）；0=不校验版本
# 版本白名单（可选，逗号分隔）：非空时以它为准，用于「正式版 + 内测版并存」；
# 为空则退化为上面的「精确匹配单一版本」（旧部署行为不变）。
ONLINE_MEDIA_APP_ALLOWED_VERSIONS=20260915,20260917
```

部署：上传 `server/app/config.py` 与 `server/app/main.py`（`enforce_client_identity` /
`allowed_client_versions`），重启服务。
生效后：不带标识、标识不符、或版本**不在允许清单内**的 `/v1` 请求返回 **426**
（`client_app_unsupported` / `client_version_unsupported`）；`/healthz` 与 `/admin/*` 豁免。
**每次发版后必须把新 versionCode 加进 `ONLINE_MEDIA_APP_ALLOWED_VERSIONS`（或改
`ONLINE_MEDIA_APP_REQUIRED_VERSION`）**，否则刚发布的新版客户端会被挡在门外（这是最容易踩的坑）。

**2026-09-17 状态：生产已启用（白名单版）**（`ONLINE_MEDIA_APP_CLIENT_ID=com.yuebo.player`、
`ONLINE_MEDIA_APP_REQUIRED_VERSION=20260915`、`ONLINE_MEDIA_APP_ALLOWED_VERSIONS=20260915,20260917`）。
实测：无标识 / 错误 App-Id / 旧版（10002）/ 未列版本（20260916、20260918）一律 426；
**正式版 20260915 与内测版 20260917 均放行**（无 Bearer 时 200 的前一步 401）；`/healthz` 与 `/admin/*` 豁免。
内测版 APK（`20260917beta`，versionCode 20260917）实测可拉 `/v1/platforms`、
`/v1/video/categories` 并浏览分类。
**兜底/回滚（客户端若自锁，最快二分钟）**：清空 `.env` 的 `ONLINE_MEDIA_APP_CLIENT_ID`（或把
`ONLINE_MEDIA_APP_ALLOWED_VERSIONS` 置空 + `ONLINE_MEDIA_APP_REQUIRED_VERSION` 置 0）→
`sudo systemctl restart local-music-player` 即关闭门禁；
`.env` 备份见 `/opt/local-music-player/.env.bak-identitygate-0915`（旧）与
`.env.bak-verwhitelist-0917`（本版），代码备份 `app.bak-verwhitelist-0917/`。
**注意**：门禁启用后，curl/探测脚本访问 `/v1` 必须带 `X-App-Id: com.yuebo.player` 与
**清单内任一** `X-App-Version`（如 `20260915` 或 `20260917`，否则一律 426）。

### 影视聚合源的合规剔除（2026-09-18 起）

**优先「整源拿掉」，不要只加词**：换词表永远追不上新用词（合规修复连续两批各漏一次，都是因为出现
了表里没有的新词）。发现某源返回成人内容时：

1. **逐上游定位**（`scripts/tmp_probe_rel_src.py`）：关系词 × 各上游源 + **保守成人判定**
   （只认无歧义露骨词），找出**具体是哪个 source** 在漏。
2. **确认是否混合站**：用通用影视词（流浪地球/庆余年）搜该源——有正常结果就是混合站，
   但仍可**按用户要求整源剔除**（本例妖狐 source=3 即混合站，照样拿掉）。
3. **从 `yaohu.YINGSHI_SOURCES` 移除**并登记到 `REMOVED_ADULT_SOURCES`（三处防护：
   聚合不再查询 + 旧缓存 `yingshi:N:*` 引用被拒 + 换源不回落）。
4. **验证要用独立判定**（`scripts/tmp_final_sweep.py`，**不要用本系统词表自证**——
   同样的盲区会假报 0 命中）。

**当前状态**：妖狐 `YINGSHI_SOURCES = ("1","2","4")`，source=3 已整源剔除。

### 客户端违禁词表（XOR+base64 内嵌，2026-09-18 起）

客户端**内嵌**违禁词表（过滤开箱即用、不依赖服务端），但**不写明文**——先按 `TABLE_XOR_KEY`
XOR 再 base64，存为 `ComplianceRules.ENCODED_TABLE`，运行时解码。

**为什么不能只 base64**：纯 base64 能被安全软件**直接解出中文词**，等于没编码（仍会报毒）。
先 XOR 再 base64 后，「按明文扫描」与「按 base64 解码扫描」都失效。

**改词表后必须重生成客户端常量**（否则客户端还是旧表）：

```bash
rtk python scripts/tmp_gen_compliance_table_0918.py     # 生成 .tmp-encoded-table.txt
# 再把该 blob 按 118 字符/行替换 ComplianceRules.kt 的 ENCODED_TABLE 常量
```

自检（发版前必做）：`unzip -p app-release.apk classes.dex > /tmp/c.dex` 后
`grep -c -a 麻豆 /tmp/c.dex` 必须为 **0**（连注释/测试里都不能有真实违禁词，测试用自造标记词）。

**排查「搜索某词仍有成人内容」的正确姿势**（本批教训）：
**不要用自己的词表自证**（会因同样的盲区而报「0 命中」）。要**逐源原样打印标题**
（`scripts/tmp_hunt_raw_0918.py`），再用**独立口径**（日本女优名 / 明显成人词，`tmp_validate_0918.py`）判定，
最后逐上游定位到具体 `source=N`（`tmp_probe_yaohu_src.py`）。妖狐有 4 个上游站，
本批漏网的是 **source=3**（混合站，不能整站剔除，只能补词）。

### 内容合规规则加密下发（2026-09-17 起）

客户端 `GET /v1/compliance/rules` 拉取**密文**规则表，解出的明文用于**直连上游**的内置采集站/官方影视源
链路（这些链路不经服务端，服务端过滤对它们不生效）。

**客户端不内置任何明文违禁词**——第一版把词表逐字内置进 APK，结果被安全软件**报毒**（用户实测反馈）；
现在客户端只内置解密种子与匹配逻辑，明文只在运行时解出。改动 `compliance.py` 的表即两边同步。

```bash
# /opt/local-music-player/.env（可选；不配则用代码内置种子 yuebo-compliance-lock-v1）
ONLINE_MEDIA_COMPLIANCE_RULES_KEY=...   # 改这里必须**同步发客户端**，否则客户端解不开
```

加/改关键词与已知成人源域名：只改 `server/app/providers/compliance.py` 的四个表并重启，**无需发版**——
客户端下拉后立即生效（**无需重新打包**，这正是分发加密规则的意义）。

**注意四点**：
① 客户端判据对 haystack 做 `lowercase()`，故**表里的词必须小写**（大写词会静默失效）；
② 已知成人源黑名单按**域名**匹配（`KNOWN_ADULT_SOURCE_HOSTS` 只收裸域名，含子域）；
③ 该密文是「防篡改 + 不透明（进 APK 不报毒）」而非机密性——种子必然随包分发，别当密钥保管；
④ **客户端源码/测试里不得出现任何真实违禁词**（注释也不行，仍会被扫）——单测用自造标记词 +
服务端固定 nonce 向量。发版前自检：
`unzip -p app-release.apk classes.dex | grep -c -a 麻豆` 应为 **0**。

**安全兜底**：规则未就绪（未拉到/解不开）时，客户端直连链路**一律回落服务端端点**
（`canServeCategoriesDirectly`/`canServeDirectly` 要求 `rulesReady`），**不会放行未过滤结果**。
故**服务端必须先部署**本端点，否则新客户端的直连分类/搜索会一直走服务端（功能正常、只是慢一点）。

端到端验证：`curl -s -H "Authorization: Bearer $TOKEN" -H "X-App-Id: com.yuebo.player" -H "X-App-Version: 20260917" \
http://127.0.0.1:8000/v1/compliance/rules` 应返回 `{"version":"…","sealed":"…"}`（明文关键词不得出现）。
