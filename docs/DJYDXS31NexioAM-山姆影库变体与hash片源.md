# DJYDXS31NexioAM · 山姆影库（hash 片源变体）

> 分支：`DJYDXS31NexioAM`，蓝本：`DJYDXS3Nexio`（网盘片源版）。
> 本文是**后续做其它机型变体（威动 / 视易 / 海美迪…）的模板**：先读 §1 的改名清单，
> 再按 §5 的机型扩展步骤替换策略，片库生成见 §2，hash 链路的实测结论见 §4（**必读**）。

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
| 内嵌片库 | 网盘库（pan_url + v_episode） | **4K hash 库**（movie.hash，1109 条） | `assets/SuperMOV.db` |
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
python -X utf8 tools/build_am_db.py
# 读  c:/py/DJYDXS31NexioAM/tmdbam.db 的 movie_tmdb_4k（4K 版本，1109 行）
# 读  C:/py/艾美mov/ew3.db 的 preview_poster / small_poster / movie_image（海报）
# 写  app/src/main/assets/SuperMOV.db 与 SuperMOV.version
```

生成侧的关键决定，改脚本前先看懂：

1. **schema 照抄蓝本库**（DDL 从旧 asset 库现取），只在 `movie` 上 `ALTER TABLE`
   加一列 `hash TEXT`。这样 `MovieStore` / `MovieDb` 的既有查询几乎不用动。
2. 视图 `v_movie_app` 重写：多带 `COALESCE(m.hash,'') AS hash` 与 `source_type`
   （`hash` / `baidu` / 空）。`v_episode` 保留但**本库里 0 行** —— 4K hash 库没有
   网盘路径，硬凑剧集表只会让详情页列出假文件。
3. `fid` 沿用蓝本的版块号语义：`112` = 单片（1080 条），`37` = 按集收录（29 条）。
   分类栏白名单就按这两个 fid 出栏，见 §1。
4. `meta` 写 `schema_version=2`（`MovieDb.SCHEMA_SUPPORTED=2`，别越过）、
   `db_version=2026092501`、`db_build_id=am4k-…`。`SuperMOV.version` 与
   `db_version` 必须一致，`MovieStore` 靠它决定要不要重新拷库。
5. 未来一份库里**同时**有 `pan_url` 和 `hash`：`MovieStore.detail()` 会把 hash 线路
   排在 `boxes[0]`，网盘线路排其后。想让网盘优先就调那一段顺序，别在 UI 层判断。

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

- `sn` / `distributor` 都可在 **设置 → 云端取址** 改；缺省 `9CF8DB056A49` / `mymei`
  （`AimeiCdn.DEFAULT_SN`，`Settings.cdnSn()`）。输入框只收 12 位十六进制。
- `register()` 幂等且**失败不阻断**：早就在册的 sn 直接取址也能过。
- `segm` 按 `index` 升序排好后拼接；`sum(length) == fileSize` 是硬不变量，
  不自洽直接抛（`CdnInfo.consistent()`）。
- **只对厂商域名放宽 TLS**（`isVendorHost`：`mymei.vip` / `mymei.tv` / `imovie.com.cn`）。
  百度网盘那条链完全不经过这里，仍走系统信任库。完整性另有保障：每段落盘后回读算
  SHA1，与服务端声明的 `sha1sum` 比对，不符即作废该段。

### 3.2 占位桩必须识别（这是本链路最容易踩的坑）

服务端对**当前设备无授权**的内容不报错，而是回一份假清单：

| 特征 | 值 |
|---|---|
| `fileSize` | `9999999999`（≥ 9e9 哨兵值） |
| `segm[*].url` | 指向 `golang.org` 的一个 tar.gz |
| `segm[*].sha1sum` | 以 `1234567890` 开头 |
| `extension` | `.ic2`（厂商加密格式，明文拼不可播） |

拿它去下会得到「大小对得上、SHA1 全错」的垃圾文件。`AimeiCdn.placeholderReason()`
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

---

## 4. 实测结论（**先看完这节再决定要不要上真机**）

2026-09-25 在本机用 sn `9CF8DB056A49` 实测：

- `register` 成功（返回记录 id `601525`）。
- 对 `movie_tmdb_4k` 的 hash 做批量取址：**汇总 取样 60 / 占位桩 60 / 真实清单 0 / 异常 0**。
- 作为对照，两个已知免费演示 hash 返回**真实** `.mkv` 分段清单：
  - `0750e31f…`（巴霍巴利王 精彩片段 2）
  - `c1531d18…`（利维坦 演示片）

**结论**：取址、签名、解析、占位桩判定这条链是对的（同一个 sn、同一份代码，
演示片能拿到真清单）；库里的 4K 正片对这个 sn **一律无授权**，属于服务端权益问题，
不是客户端 bug。

所以真机上验证 §3.3/§3.4 时，先用演示 hash 建一条测试任务（把它的 hash 塞进
`movie` 表某一行，或用设置页改 sn 后再试正片）。正片下载要等：
①拿到已授权机器的 sn，或 ②云端把这批内容开给当前 sn。

> 复测脚本口径：串行 + 每条间隔 0.6~1.2s 抖动，别并发打 `api.mymei.vip`。
> 这条链路的失败模式里有「请求太密被判异常」，实测时保持低频。

---

## 5. 机型识别与后处理：做下一个版本只改这里

### 5.1 识别：`DeviceProfile.detect()`

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

### 5.2 三个切入口（**唯一**允许写机型差异的地方）

| 方法 | 通用版行为 | 机型差异放这里 |
|---|---|---|
| `targetDir(File root, String title)` | 在下载根目录下建一层中文片名目录 | 目录层级/命名模板 |
| `finalName(String title, String ext, Caps caps)` | `片名.ext`（ext 取云端 `extension`） | 是否保留原始文件名、是否加季目前缀、扩展名白名单 |
| `afterDownload(File finalFile, Dl task)` | 空 | 写 nfo/侧车文件、触发媒体库扫描、厂商 SDK 通知 |

新增机型 = 在 `Brand` 加枚举 + `ALIASES` 加一行 + 这三个方法里加分支。
**不要**把 if 写进 `DlEngine`：引擎只管「下下来、校验收」，长什么样归 `DeviceProfile`。

### 5.3 文件名合法性：`FilmNaming`

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

## 6. 已知缺口 / 待办

- [ ] **CI 不构建本分支**：`build.yml:5` 白名单没有 `DJYDXS31NexioAM`。
- [ ] **正片权益未通**：§4 的 60/60 占位桩；拿到已授权 sn 前，真机只能用演示 hash 验证。
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
