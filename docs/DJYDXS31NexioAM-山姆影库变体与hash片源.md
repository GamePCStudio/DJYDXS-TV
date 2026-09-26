# DJYDXS31NexioAM · 山姆影库（hash 片源变体）

> 分支：`DJYDXS31NexioAM`，蓝本：`DJYDXS3Nexio`（网盘片源版）。
> 对应版本：**v1.36**（`versionCode 36`），内嵌库 `db_version=2026092601`（1519 部 hash 片源，v1.33 起未变）。
> v1.34 只动界面文案（sn 与 40 位云指纹不再出现在任何用户可见处，见 §3.4）；
> v1.35 在下载队列页顶部加了「前台极速 / 退后台限速」说明，见 §3.5；
> v1.36 把那段说明的措辞改了，并把它拆成两行排版（第一行接在标题后，见 §3.5）。
> 本文是**后续做其它机型变体（威动 / 视易 / 海美迪…）的模板**：先读 §1 的改名清单，
> 再按 §6 的机型扩展步骤替换策略，片库生成见 §2，hash 链路的实测结论见 §4（**必读**），
> 列表排序与海报墙加载见 §5。

---

## 1. 本变体与蓝本的差异一览

| 项目 | 蓝本 DJYDXS3Nexio | 本分支 DJYDXS31NexioAM | 位置 |
|---|---|---|---|
| 应用显示名 | 电影影大师 | **山姆影库** | `res/values/strings.xml:3` |
| 英文名 | DJYDXS-TV | SamMov | `res/values/strings.xml:4` |
| applicationId | `com.supermov.tv` | **`com.supermov.tv.am`** | `app/build.gradle:11` |
| Java 包名 / namespace | `com.supermov.tv` | **不变**（见下方口径） | `app/build.gradle` |
| 网盘转存根目录 | `/电影影大师` | **`/山姆影库`** | `Settings.java:42` |
| 下载落盘目录名 | 含应用名 | **含应用名** | `Storage.java:94,98` |
| 内嵌片库 | 网盘库（pan_url + v_episode） | **4K hash 库**（movie.hash，1519 条） | `assets/SuperMOV.db` |
| 片源路径 | 百度网盘分享 → 转存 → 直链 | **云端 hash → 分段直链 → 拼接** | `AimeiCdn.java` / `DlEngine.runHashTask` |
| 分类栏 | 4 栏（4K全景声/最新剧集/蓝光/杜比5.1） | **2 栏（4K电影 / 4K纪录片）** | `MovieStore.java:252` |
| 数据库在线更新 | `db/SuperMOV.json` | **`db/SuperMOV-am.json`**（单独一份） | `DbUpdater.java:43` |

**改名口径（做下一个变体时照抄这条，别扩大范围）**：只改 ①显示名 ②`applicationId`
③网盘与下载目录名。`namespace`、Java 包路径、logcat TAG（`SupeMov`）、assets 里的
`SuperMOV.db` 文件名**都不动** —— 动它们换来的只有全项目 reflow，没有任何产品价值。

CI 现在**不会**构建本分支：`.github/workflows/build.yml:5` 的分支白名单是
`[ main, master, DJYDXS2Nexio, DJYDXS3Nexio ]`。要在 CI 出 APK，把分支名加进去；
不加就一直本地打包。

---

## 2. 片库：`tools/build_am_db.py`

内嵌库不再手改，一律由脚本从上游 SQLite 生成：

```bash
python -X utf8 tools/build_am_db.py            # 默认：只出 hash 片源（当前 1519 部）
python -X utf8 tools/build_am_db.py --with-pan # 另并 SuperMOV.db 的网盘 4K（现在不并，见 §2.1）
# 读  c:/py/DJYDXS31NexioAM/tmdbam.db   :: movie_tmdb_4k（40 位 hash 片单）
# 读  c:/py/DJYDXS31NexioAM/cdn_census.db :: census（云端真实容器，剔 .ic2）
# 读  C:/py/艾美mov/ew3.db              :: preview_poster / small_poster / movie_image（海报）
# 写  app/src/main/assets/SuperMOV.db 与 SuperMOV.version
```

### 2.1 `tmdbam.db` 从哪来：上游四段链 + **必须先快照**

内嵌库的片单不是手写的，是 `C:\py\艾美mov\` 里那套脚本的产物：

```
ew3.db（厂商活库，46 表）
  → ew3_tmdb_map.py    逐个 en_name 搜 TMDB，增量写 tmdb_map（已有行跳过）
  → ew3_finalize.py    离线重判：feat_ids 含 11 + file_name_ext='mkv' + douban_id 非空
                        + CLIP_PAT 排短片 → 丢掉并重建 movie_tmdb_4k
  → ew3_tv_fill.py     纪录片分集（第N季第M集 / Season N Episode M）走 /tv 补到集
  → cp ew3_tmdb.db C:/py/DJYDXS31NexioAM/tmdbam.db      ← 就是一份改名拷贝
  → cdn_census.py <sn> 逐个 hash 问云端容器（相对路径，必须在该目录里跑）
```

**`ew3.db` 会被原地改写**：同一个文件、同一天，先后读到 `8238 行 / MAX(times)=2021`
和 `11088 行 / MAX(times)=2026`，文件大小 50683904 与 mtime 都没动（旁边长出
`ew3.db-wal` / `ew3.db-shm`）。所以**跑链路前先 `cp` 一份快照**，本轮用的是
`C:/py/DJYDXS31NexioAM/ew3_snapshot_20260926.db`（11088 行 / 最大 2026）。

这就是「2026 年的片子明明有 4K 却不在库里」的全部原因 —— **`tmdbam.db` 是 09-25 上午
的旧快照**，`movie_tmdb_4k` 1109 行；`ew3_tmdb_map.py` 又是按 `movie_id` 增量跳过已跑过的，
所以老片一直复用旧判定、新片根本没进 `tmdb_map`，`ew3_finalize.py` 自然扫不到。
09-26 重跑：`tmdb_map` 1224 → **1698**（feat-11 全覆盖，本轮 474 条新增、命中 ABC 450、
网络失败 0）→ `movie_tmdb_4k` **1562 行**（1533 电影 + 29 分集）→ 云端真实 mkv **1519 部**。
2022+ 从 0 涨到 **358 部**，2026 年 31 部（《超级少女》`movie_id 604791` → TMDB 1081003，
A1 标题全等+年份精确，57.56 GB）。**hash 侧自己就覆盖到新片，网盘并库因此被关掉**（§4.3）。

生成侧的其他关键决定，改脚本前先看懂：

1. **schema 照抄蓝本库**（DDL 从旧 asset 库现取），只在 `movie` 上 `ALTER TABLE`
   加一列 `hash TEXT`。这样 `MovieStore` / `MovieDb` 的既有查询几乎不用动。
2. 视图 `v_movie_app` 重写：多带 `COALESCE(m.hash,'') AS hash` 与 `source_type`
   （`hash` / `baidu` / 空）。`v_episode` 保留但**本库里 0 行** —— 4K hash 库没有
   网盘路径，硬凑剧集表只会让详情页列出假文件。
3. `fid` 沿用蓝本的版块号语义：`112` = 单片（1490 条），`37` = 按集收录（29 条）。
   分类栏白名单就按这两个 fid 出栏，见 §1。
4. `meta` 写 `schema_version=2`（`MovieDb.SCHEMA_SUPPORTED=2`，别越过）、
   `db_version=2026092601`、`db_build_id=am4k-…`。`SuperMOV.version` 与
   `db_version` 必须一致，`MovieStore` 靠它决定要不要重新拷库。
5. 一条 `hash` 对应一个落盘文件，`MovieStore.detail()` 把 hash 线路排在 `boxes[0]`；
   真并了网盘行时它也排在 `pan_url` 之前。想让网盘优先就调那一段顺序，别在 UI 层判断。
6. **只收 mkv 片源**：脚本按 `file_name_ext == 'mkv'` 过滤并打印被删条数。
   但这一列在源头就不可信（1562 行全写 mkv，云端却会回 `ic2`），所以真正的清点是
   第二段：跑 `cdn_census.py` 逐个 hash 问云端，按 `cloud_ext == 'mkv'` 再筛，
   详见 §4.2。上游快照（09-26，11088 行）的 `movie.file_name_ext` 全量分布可作参照 ——
   `ic2 5603 / mkv 5232 / iso 157 / m2ts 57 / 空 27 / mp4 11 / mkva 1`，
   即**将近一半的艾美片库是加密容器**，扩库时这道过滤会真的开始咬人。

生成后自检（脚本末尾自带，也可以在 python 里手跑）：影片数、fid 分布、
`hash` 非空条数、海报 URL、`v_movie_app` 列清单。

---

## 3. hash 片源链路（已实现）

### 3.1 取址：`AimeiCdn.java`

两步协议，与已验证可用的 Python 脚本逐字段对齐：

```
POST https://api.mymei.vip/api/ewatch/register
     {sn, distributor, time, sign}          sign = md5(time + distributor + "mYmoV10238")  # 小写
GET  https://api.mymei.vip/api/movie/getCdnUrl?sn=<sn>&hash=<40位hash>
     → {extension, fileSize, host, segm:[{index,start,length,sha1sum,url}]}
```

- `sn` / `distributor` 都可在 **设置 → 云端取址** 改；缺省 `9CF8DB078B44` / `mymei`
  （`AimeiCdn.DEFAULT_SN`，`Settings.cdnSn()`）。输入框只收 12 位十六进制。
  **这个 sn 是有权益的**（见 §4），换 sn 等于换一台机器能看哪些片。
- `register()` 幂等且**失败不阻断**：早就在册的 sn 直接取址也能过。
- `segm` 按 `index` 升序排好后拼接；`sum(length) == fileSize` 是硬不变量，
  不自洽直接抛（`CdnInfo.consistent()`）。
- **只对厂商域名放宽 TLS**（`isVendorHost`：`mymei.vip` / `mymei.tv` / `imovie.com.cn`）。
  百度网盘那条链完全不经过这里，仍走系统信任库。完整性另有保障：每段落盘后回读算
  SHA1，与服务端声明的 `sha1sum` 比对，不符即作废该段。

### 3.2 占位桩必须识别（这是本链路最容易踩的坑）

服务端对**当前设备无授权**的内容不报错，而是回一份假清单：

| 特征 | 值 | 判定写法 |
|---|---|---|
| `fileSize` | `9999999999` | **只认这个精确值** |
| `segm[*].url` | 指向 `golang.org` 的一个 tar.gz | `contains("golang.org")` |
| `segm[*].sha1sum` | 以 `1234567890` 开头 | `startsWith(...)` |
| `extension` | `ic2`（厂商加密，明文拼不可播） | 独立成立即判桩 |

> **这条口径是用误报换来的**：第一版把 `fileSize` 写成 `>= 9e9` 就算哨兵，
> 可 4K 原盘本来就有 40~80 GB（实测 霸主 42,317,745,422、沉默的羔羊 83,033,340,078），
> 结果**已授权的真实清单被 100% 判成占位桩**、一部都下不动。
> `AimeiCdn.placeholderReason()` 现在只做精确匹配，改这里前先照 §4 的样本回归一遍。

拿占位桩去下会得到「大小对得上、SHA1 全错」的垃圾文件。`placeholderReason()`
命中即返回原因串，`runHashTask()` 直接失败并把「序列号对该片无授权」显示给用户。

### 3.3 下载：`DlEngine.runHashTask()`

`Dl.source` 新增 `SRC_BAIDU` / `SRC_HASH`（`Dl.java:18`），`DlDb` 升到 `VER=2`
并加 `source/hash/ext` 三列（`onUpgrade` 走 `addColumnIfMissing`，老行 `source`
读作 baidu）。`runTask()` 开头分流：`SRC_HASH` → `runHashTask()`，网盘逻辑一行不碰。

1. 每次跑都**重新取址**（分段链接带服务端签名的日期段与令牌，绝不复用旧链接）。
2. 中间文件 `<hash>.dlpart`（`hashStageName()`）—— 只跟 hash 有关，云端换了
   `extension` 也不会让断点失效。`setLength(fileSize)` 预分配，各段按绝对偏移写。
3. 断点元数据 `<hash>.dlpart.json`：`{kind:"cdn", hash, total, segs:[{i,done,ok}]}`。
   `hash`/`total` 对不上就整份作废、删中间文件从零开始，**不会拿脏数据续**。
4. `CDN_WORKERS = 4` 路并发，工作线程从同一个 `AtomicInteger` 游标领段。
   4 路已能跑满家宽；再多只会让边缘节点更早停流。
5. 单段停滞 45s（`CDN_STALL_TIMEOUT_MS`）判卡死，最多续传 12 次
   （`CDN_RESUME_RETRY`）。`416` 视为「这段其实已经在盘上」，直接去校验。
6. **每段下满后从文件里回读该段字节算 SHA1**（`sha1Range()`），而不是边下边算 ——
   这样上一进程写的前缀和本次续上的部分拼出来的摘要一定等于落盘内容。
7. 终检 `verifyFinal()`：落盘长度 == `fileSize`、各段之和 == `fileSize`、
   `mkv` 的话文件头必须是 EBML 魔数 `1A 45 DF A3`。三关全过才进后处理。

### 3.4 后处理与入口

- 详情页：库里 `hash` 非空 → `boxes[0].type == "hash"` → `DetailActivity.hashMode`。
  此时**隐藏「在线播放」与「转存」**（两者都依赖网盘分享链接），只留「下载」。
- 下载入口：不要求网盘登录、不解析分享、没有多选文件那一步 ——
  一个 hash 就是一个落盘文件。
- 落盘：`DeviceProfile.targetDir()` 在用户选的根目录下建**中文片名**目录；
  校验通过后 `renameTo(片名.ext)`，跨分区 rename 失败退回 `copyFile`；
  同级重名走 `FilmNaming.unique()` → `名字 (2)`。
- **界面上不出现 sn / 40 位云指纹**（v1.34 起的口径）：详情页与下载管理一律显示
  影片名称（+ 大小），中间文件在屏幕上走 `Dl.displayName()` 显示成「片名.dlpart」，
  磁盘真名 `<hash>.dlpart` 与断点续传定位键都不受影响；取址失败的报错也不带 sn
  （`DlEngine.runHashTask` 只说「本设备暂时无法取得影片授权，稍后再试」）。
  细节仍留在 logcat：`AimeiCdn.fetch` 与 `DlEngine` 的 `Log.d` 会打 hash 与占位桩特征。
  唯一还显示 sn 的地方是 设置 →「云端取址」，那是换 sn 的入口，删了就换不了机器。

### 3.5 前台极速 / 后台限速：这件事要写在下载页明面上

限速只有两个开关点，别在别处再乘系数：

| 时机 | 代码 | 效果 |
|---|---|---|
| 进 下载队列页 | `DownloadActivity.onResume` → `setRateLimit(0)` | 不限速（用户正盯着进度） |
| 离开该页 / 冷启动 | `DownloadActivity.onPause`、`DlEngine.init` → `setRateLimit(Settings.dlLimitBps())` | 按设置的后台限速 |

`Settings.dlLimitMbps()` 默认 20、区间 1~40 Mbps，换算成字节见 `dlLimitBps()`
（`1MB/s = 8Mbps`，单位别弄反）。这个口径在下载页明面上写成两行（v1.36 起的排版）：

| 位置 | 视图 | 内容 |
|---|---|---|
| 顶部第一行 | `tvDlTitle` | 「⬇ 下载队列」+ 前台不限速的说明，同一段文字接排 |
| 顶部第二行 | `tvDlSpeedTip` | 退到后台后降速的说明，含 Mbps 数值 |

第一行是 `setTopTitle()` 用 `SpannableString` 分两段样式拼出来的：标题那段保持布局里的
18sp 粗体蓝，后面接的说明压掉加粗、换成浅灰，并用 `RelativeSizeSpan` **缩到和第二行一样的
字号**。比例不写死：`tvSpeedTip.getTextSize() / tvDlTitle.getTextSize()`（= 11/18），
布局里改任一处 sp 都不用回来动这里。**这里不能用 `AbsoluteSizeSpan(11)`** —— 它收的是
像素，11px 在盒子上基本看不见。第二行的数值直接取 `Settings.dlLimitMbps()`、在 `onResume`
赋值 —— 用户改完设置回到这页要能看到新值。
所以：**改了限速语义，这两处文案必须跟着改**。

---

## 4. 实测结论（**先看完这节再决定要不要上真机**）

### 4.1 sn 决定一切：两台机器实测对照

| sn | register | 对 4K 正片取样结果 |
|---|---|---|
| `9CF8DB056A49`（旧缺省） | 成功，记录 id `601525` | **取样 60 / 占位桩 60 / 真实 0** —— 该片库对它无授权 |
| `9CF8DB078B44`（现缺省） | 成功，记录 id `693833` | 随机取样 24：**真实 23 / 占位桩 1** |

真实清单的样子（sn `9CF8DB078B44`，逐条实测）：

- `extension=.mkv`，`fileSize` 与 `movie_tmdb_4k.file_size` **逐字节相等**
  （霸主 42,317,745,422 / 小美人鱼 53,517,157,534 / 波西米亚狂想曲 42,388,359,419）。
- 分段数 195~792 段，每段约 100 MiB，`sum(length) == fileSize` 全过；
  分段主机 `tx.cdn.mymei.tv`（清单里 `host` 字段写的是 `bd.cdn.mymei.tv`，以 `segm[*].url` 为准）。
- 那一条占位桩是 `movie_id 600700 蜘蛛侠：英雄远征`，`extension=ic2`
  + `fileSize=9999999999` + `url=golang.org` + `sha1=1234…` 四条特征齐全。
- 演示 hash `0750e31f896f425be10fdc3e02c85bd339abf94f`（巴霍巴利王 精彩片段 2）在任何 sn 下
  都回真实清单：`.mkv` 1,544,975,507 字节 / 15 段 —— **冒烟测试就拿它验证下载与校验链**。

**结论**：换到 `9CF8DB078B44` 之后，片库里的 4K 正片是**真能下**的；
之前那批 60/60 全桩不是代码问题，是那台机器没权益。

### 4.2 「只保留 mkv 片源」为什么必须问云端

`tmdbam.db::movie_tmdb_4k` 的 `file_name_ext` 这一列 1562 行**全写着 `mkv`**，
但同一批 hash 里 `600700` 云端回的是 `ic2` —— 也就是说这一列不可信，
按它过滤等于没过滤。所以片源清点是逐个 hash 调 `getCdnUrl` 认**云端真实容器**：

- 脚本 `c:\py\DJYDXS31NexioAM\cdn_census.py`，结果表 `cdn_census.db::census`
  （`movie_id / lib_ext / cloud_ext / file_size / segs / sum_len / stub / err`），
  已清点的自动跳过，可断点续跑。
- 抽样 24 条：`cloud_ext` 全部 `mkv`，除 `600700` 一条 `ic2`。
- **全量清点结果**（sn `9CF8DB078B44`，串行 + 0.9~1.6s 抖动跑完；09-25 清 1109 条，
  09-26 重跑上游后又补 453 条，现在盖住全部 1562 条）：

  | `cloud_ext` | 条数 | 说明 |
  |---|---|---|
  | `mkv` | **1519** | 真实清单，`sum(segm.length) == fileSize` 全过（0 行不自洽） |
  | `ic2` | **43** | 全部同时命中 `fileSize == 9999999999` 占位特征 |
  | 合计 | 1562 | `stub` 43 / `err` **0** |

  43 条 `ic2` 的 `movie_id` 全落在 `600636..600902`（例：`600636 王牌保镖 /
  600637 不可能的事 / 600638 捉鬼敢死队2 / 600639 雷霆沙赞 / 600641 地狱男爵：血皇后崛起 /
  600644 速度与激情6`），也就是 09-25 那批老片；**09-26 新增的 453 个 hash 一条桩都没有**。
  真实清单的体积：中位 **49.2 GB**、最小 8.7 GB、最大 85.1 GB、分段 90~872 段。
  补清点这一轮第一遍有 **116 条本地网络报错**（100× `WinError 10013` 套接字权限、
  12× `11001 getaddrinfo failed`、1× `10053` 连接中止），**不是云端拒绝** ——
  这些行的 `err` 非空，第二遍只重试它们，全部恢复成 `err` 0。

`tools/build_am_db.py` 的入库过滤现在是**两道**：①`file_name_ext == 'mkv'`；
②按 `cdn_census.db` 里 `cloud_ext == 'mkv'`、且 `stub`/`err` 皆空再筛一遍
（非 mkv 的一律不进库，每条剔除都会打印片名与原因）。第二道是**硬门**：
清点表缺了、或者没盖住全部片单，脚本直接退出而不是把「没清到」的片当非 mkv 删掉。

`db_version=2026092601` 这次重建实测：第一道 `总 1562 → 留 mkv 1562 / 删 0`
（这一列全写着 mkv，等于没过滤，正是必须问云端的理由）；
第二道 `留 1519 / 剔 43`。内嵌库最终 **1519 部 = fid 112 单片 1490 + fid 37 按集 29**。
上一版（`2026092502`）是 `总 1109 → 留 1066 / 剔 43 = 1037 + 29`。

### 4.3 网盘并库：算过之后**关掉**了（`--with-pan`）

09-25 那版库只到 2021，为了让 2022~2026 的 4K 片有货，`build_am_db.py` 里写过一段
并 `C:/py/SuperMOVDB/SuperMOV.db`（fid=112 `4KSDR.Remux`）网盘片的逻辑。
09-26 重跑上游（§2.1）之后重新量化了一遍：

| | 条数 | 2022+ | 体积中位 | ≥30 GB | 取址 |
|---|---|---|---|---|---|
| hash 片源 | **1519** | 358（2026 年 31 部） | 49.2 GB | 1435 条（94.5%） | 免登录分段直链 + 逐段 SHA1 |
| 网盘净新增 | 255 | 184（2024+ 174 部） | **17.2 GB** | 48 部 | 要先登录百度、转存才能下 |

去重口径：fid=112 候选 436 → 内部去重 413（同片多帖留 `total_size` 最大）→ 与 hash 侧
同豆瓣 158 条 → 净新增 255。也就是说**网盘侧带来的新片，hash 侧现在已经自己有了**，
剩下的 255 条里只有 48 条体积过 30 GB（中位 17.2 GB，源侧没标清晰度，
只能按体积判断它更像 1080p 重导），而它们会把首屏占满
（`release_date` 更新到 2026-09，点开却要登录转存）。
2026-09-26 决定：**默认不并**，代码留在 `--with-pan` 后面，`meta.db_source` 会跟着变，
以后要并随时并得回来。

### 4.4 剩下的真实风险

单片中位 49.2 GB（8.7~85.1 GB）、90~872 段：下载盘的分区格式（FAT32 装不下 >4 GB）、
剩余空间、以及 4 路并发下的 CDN 停流才是接下来要盯的，权益已经不是瓶颈。

> 复测脚本口径：串行 + 每条间隔 0.9~1.6s 抖动，别并发打 `api.mymei.vip`。
> 这条链路的失败模式里有「请求太密被判异常」，实测时保持低频。

---

## 5. 海报墙：排序与加载性能

### 5.1 「最新在前」已经是现状，别再改 SQL

`MovieStore` 的排序串只有两份（`ORDER_BY_PIN` 带置顶、`ORDER_BY_PLAIN` 不带），
键都是 `COALESCE(release_date,'') DESC, COALESCE(year,0) DESC, src_tid DESC`，
`queryPage()` 分页时统一套用。对**设备上真正跑的**内嵌库（`db_version=2026092601`）
按这个串实测：

| 版块 | 条数 | 逆序对 | `release_date` 为空 | 首屏前三 |
|---|---|---|---|---|
| 112 4K电影 | 1490 | **0** | 0 | 超级少女(2026-06-24) / 揭秘日(2026-06-10) / 诺曼底72小时(2026-05-28) |
| 37 4K纪录片 | 29 | **0** | 0 | 七个世界，一个星球 第1季第7集(2019-12-08) → E6 → E5 |

末屏也就是最老的：112 栏是《生活多美好》(1946-12-20) 与《绿野仙踪》(1939-08-21)。

也就是说「按时间倒序」不需要动 SQL —— 要做的是**让它在真机上可验证**。
所以 `MainActivity` 每次拿下一页会打一行
`list fid=… page=… first=片名(2026-06-24)`，logcat 里直接看到列表头部是谁；
配合设置页那行「数据 v2026092601」，就能区分「排序不对」和「设备上跑的还是旧内嵌库」。
（排序键用 `release_date` 而不是 `updated_at`：入库时间会随重建抖动，上映日期才是用户认的「新片」。）

### 5.2 海报慢在哪：解码，不是下载

原实现（蓝本沿用）的问题不是网络，是**每张图按原始尺寸解码成 Bitmap**：

- 海报 URL 形如 `http://sto.imovie.com.cn/store/poster/348/602348_preview.jpg`，
  一屏 7 列、一页 40 部，全按原尺寸解码就是 40 张巨图。
  随机抽 20 张实测：**中位 3.53 MB / 均值 2.93 MB / 最小 516 KB / 最大 4.79 MB**，
  同一批里抽 2 张看像素：`602384` 是 **2000×3000**（解成 ARGB_8888 = 22.9 MB），
  `600920` 是 **1080×1620**（6.7 MB）—— 整页 40 张按大图算就是 ~900 MB Bitmap，
  而可用堆通常只有一两百 MB。
  同一批样本串行整图下载的往返耗时 **6.4~44.6 s（均值 11.7 s）** —— 也就是说到手字节
  本来就这么慢，降采样解决的是「拿到之后别再拖死 UI」，不会让首屏凭空变快。
- 缓存是 `static ConcurrentHashMap<String, Bitmap>`，**无上限、永不淘汰** ——
  翻几页就把堆吃光，表现为越滚越卡、最后 OOM。
- 没有磁盘缓存：退出版块再进来又要重新下载一遍。

现在 `ImageLoader` 改四件事：

1. **降采样解码**：`inJustDecodeBounds` 读尺寸 → 算 `inSampleSize` → 只解到
   目标宽（缺省上限 `MAX_DECODE_W=384` px）。海报格子的目标宽由
   `MovieAdapter.setCellWidthPx()` 传入 = `屏幕宽 / 列数`，绑定读 `ivPic.getWidth()`。
2. **有界内存缓存**：`LruCache`，`maxMemory()/8` 并夹在 8~48 MB，
   `sizeOf` 用 `getAllocationByteCount`（按真实占用记账，不是按 Bitmap 数）。
3. **磁盘缓存**：`filesDir/posters/<sha1(url)>`，容量 96 MB，每 16 次写入 `trimDisk()`
   一次按 `lastModified` 淘汰；命中时 touch 时间戳，等价 LRU。
4. **失败退避**：某 URL 失败后 5 分钟内不再重试（`FAIL_TTL_MS`），
   避免一屏里几张死链被反复重下、把线程池占满。

`MainActivity` 侧另两处：`setItemViewCacheSize(列数×3)`（往回翻不重新绑定/解码）。
**没加** `setHasFixedSize(true)`：release 的 `:app:lintVitalRelease` 会以
`InvalidSetHasFixedSize` 判致命错误（`item_movie.xml` 根高度是 `wrap_content`，
「条目尺寸固定」这个前提不成立），CI 直接挂 —— 本地 javac 是查不出这类问题的。

真机证据（小米盒子 32 位进程，logcat `23136`，装在 v1.30 上）：进海报墙 2 秒必崩，
两次启动 PID 7252 / 7306 都是同一条栈 ——

```
java.lang.OutOfMemoryError: Failed to allocate a 24000012 byte allocation
    with 8751640 free bytes and 8MB until OOM
    at android.graphics.BitmapFactory.decodeStream(BitmapFactory.java:651)
    at com.supermov.tv.ImageLoader.fetch(ImageLoader.java:64)      ← 老版整图解码
    at com.supermov.tv.ImageLoader.lambda$load$1(ImageLoader.java:33)
```

`24000012` 字节 = 2000×3000 的 ARGB_8888，正好是 §5.2 实测那张 `602384` 海报；
崩溃前进程堆已被顶到 `Clamp target GC heap from 199MB to 192MB`。
所以这不是「海报慢」，是**必崩**，而栈顶那个 `decodeStream` 调用就是降采样要消掉的那一步。
另外给工作线程加了 `catch (Throwable)` 兜底：线程池里抛出任何未捕获 throwable（含 OOM）
都会连带整个进程被杀，一张海报失败最多是格子空着，不能把应用带崩。

### 5.3 还没解决的：payload 本身

降采样只省内存和解码时间，**字节数一张没少**（§5.2 实测中位 3.53 MB / 均值 2.93 MB）。
试过的三条省流量的路都不通，别重复踩：

- `..._small.jpg`：实测 769×250，是**横版 banner**，放进 2:3 的 `PosterView` 会变形。
- 阿里云 OSS 的 `?x-oss-process=image/resize`：**被忽略**，返回字节与原图完全一致。
- 换 `apicdn.mymov.net` 的 350 px 缩略图：字节确实小（47~102 KB），但实测延迟
  6.6~87 s 且不稳定 —— 电视上宁可大一点也别转圈，没换。

要再快只剩两个办法，都属于**数据侧决策**，没定：①生成库时把 ~350 px 缩略图烘进
assets（APK 体积会明显涨）；②真找到又小又快的同形状缩略图域名再换 URL。

---

## 6. 机型识别与后处理：做下一个版本只改这里

### 6.1 识别：`DeviceProfile.detect()`

`Build.MANUFACTURER + Build.BRAND + Build.MODEL` 拼成小写串，按 `ALIASES`
表「多别名 + 包含」匹配。`ALIASES` 每行首列是 `Brand` 枚举名，其余是关键字：

```java
{"AIMEI",   "mymei", "emei", "ewatch", "imovie", "艾美"},
{"HAIMIDI", "himd", "haimidi", "海美迪"},
{"WEIDONG", "weidong", "vdo", "vod", "威动"},
{"SHIYI",   "shiyi", "shi-yi", "视易"},
```

命中不了 → `Brand.GENERIC`（通用版）。**加机型的正确姿势**：拿真机 logcat 里
`机型识别: 厂商=… 型号=… -> …` 那行的**原始三元组**来补关键字，不要凭品牌中文名猜
（同一品牌不同批次填的字符串能差十万八千里）。设置页那行 `识别机型:` 显示的就是
`DeviceProfile.raw()`，截图即证据。

### 6.2 三个切入口（**唯一**允许写机型差异的地方）

| 方法 | 通用版行为 | 机型差异放这里 |
|---|---|---|
| `targetDir(File root, String title)` | 在下载根目录下建一层中文片名目录 | 目录层级/命名模板 |
| `finalName(String title, String ext, Caps caps)` | `片名.ext`（ext 取云端 `extension`） | 是否保留原始文件名、是否加季目前缀、扩展名白名单 |
| `afterDownload(File finalFile, Dl task)` | 空 | 写 nfo/侧车文件、触发媒体库扫描、厂商 SDK 通知 |

新增机型 = 在 `Brand` 加枚举 + `ALIASES` 加一行 + 这三个方法里加分支。
**不要**把 if 写进 `DlEngine`：引擎只管「下下来、校验收」，长什么样归 `DeviceProfile`。

### 6.3 文件名合法性：`FilmNaming`

先认分区（`capsFor()` 读 `/proc/mounts`，取覆盖目标目录的最长挂载点），再按规矩裁名：

- 通用禁忌：`\ / : * ? " < > |` 与控制字符 → `_`；全角 `：` `／` 同样换 `_`
  （片名里太常见，换成半角反而会撞禁忌）。
- FAT/NTFS 额外：结尾的点号与空格会被**静默丢弃**，所以主动 `trim` 掉；
  `CON/PRN/AUX/NUL/COM1-9/LPT1-9` 作主名时整个名不可用 → 前缀 `_`。
- 长度：按 **UTF-8 字节** 截（中文一个字 3 字节），上限取
  `min(挂载点限制, 120)`，且绝不砍半个多字节字符（`cutBytes()`）。
- 判不出挂载类型时按**最严并集**处理：宁可名字短一点、多两个下划线，
  也不要下完 40 GB 才发现文件写歪。

---

## 7. 已知缺口 / 待办

- [ ] **CI 不构建本分支**：`build.yml:5` 白名单没有 `DJYDXS31NexioAM`。
- [x] ~~**正片权益未通**~~：换用已授权 sn `9CF8DB078B44` 后已通（§4.1）。同时修掉了
      「`fileSize >= 9e9` 就算占位桩」这个误杀 —— 4K 原盘本来就是 40~83 GB。
- [x] ~~**全量 mkv 清点未跑完**~~：1562 条全部清完（`stub` 43 / `err` 0），
      内嵌库已按云端真实容器重建成 1519 部，详见 §4.2。
- [x] ~~**库只到 2021、2026 新片进不来**~~：根因不是过滤规则，是 `tmdbam.db` 用了
      `ew3.db` 的旧快照（1224 条 feat-11）。09-26 先快照再重跑上游四段链，
      feat-11 1698 → `movie_tmdb_4k` 1562 → 真实 mkv 1519，2022+ 有 358 部（§2.1）。
      **代价是这条链今后必须定期重跑**，见下面那条待办。
- [ ] **上游快照会过期，且看不出过期**：`ew3.db` 原地改写、mtime 不动，唯一可靠的判据是
      `select count(*), max(times) from movie` 与 `(','||feat_ids||',') like '%,11,%'` 的条数。
      每次出包前跑一遍 §2.1 的链（先 `cp` 快照），并核对 `tmdb_map` 是否盖住全部 feat-11。
- [x] ~~**海报墙进页必崩**~~：v1.30 真机 logcat 是 `BitmapFactory.decodeStream` 单次
      申请 24,000,012 字节 OOM（§5.2）。v1.31 起降采样 + 有界缓存，v1.32 再给工作线程
      加 `catch (Throwable)` 兜底 —— 加载失败只留空格子，不再带崩进程。
- [ ] **海报首屏还是慢**：§5.2 的降采样 + 双层缓存只解决了内存和重复下载，
      单张 2~4 MB 的原始 payload 没动。真正的解法在 §5.3 末尾那两个选项里，
      需要定：烘图进 assets（APK 变大）还是换 URL。
- [ ] **hash 片没有剧集清单**：`v_episode` 为 0 行，详情页「库内已收录 N 个视频文件」
      那段不会出现。若未来一部片对应多个 hash（多集/CD2），要在 `movie` 之外加一张
      hash 清单表，并让 `runHashTask` 支持一条任务多文件 —— 现在是一对一。
- [ ] **设置里的 `distributor` 没有 UI**：只有 `Settings.setCdnDistributor()`，
      切换厂商风味（`yszn/yunmao/emei/mymeihome/v2share`）目前得改代码。
- [ ] **暂停后保留 `<hash>.dlpart` 与预分配大小**：40 GB 的片暂停着也占满空间，
      是否要在暂停时 `setLength(已下)` 收缩，等有真机反馈再定。
- [ ] `AM` 专用数据库清单 `db/SuperMOV-am.json` **尚未上线**：现在点
      设置 → 影片数据库 得到的是「没有更新」（`DbUpdater` 把 404/超时都当无更新
      静默处理）。上线时注意别让 `db_version` 低于内嵌库，否则装完又被换回去。
