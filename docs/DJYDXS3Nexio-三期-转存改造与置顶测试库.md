# DJYDXS3Nexio · 三期 —— 转存改造 · 空间提示 · 置顶测试库

> 对应版本：**v1.30**（`versionCode 30`）　分支：`DJYDXS3Nexio`
> 内嵌库：`db_version=2026091902` / `schema_version=2` / `sha256=3dd45ca6…b814a`
> 前序：一期《数据库重构方案》、二期《数据库重构方案-二期》（v1.28 内嵌库 + v1.29 分类栏白名单）

---

## 0. 本轮三项需求

| # | 需求原文（用户口径） | 落地位置 | 状态 |
|---|---|---|---|
| 1 | 测试库 `https://pan.baidu.com/s/1_2dpi1iheep_U-eh_J5zHA?pwd=fish` 写进数据库，放「4K全景声」栏目，**暂时放在首位** | 内嵌库 `movie` 新增 `pin_top` 列 + 手工条目 `src_tid=-1` | ✅ 已完成并验证 |
| 2 | 转存逻辑调整：**分享没有文件夹结构**，或**根目录下直接是影片文件**时，先建「影片名称」文件夹，再转存进去 | `BaiduPan.transfer(...)` 4 参重载 + 根条目 `isdir` 分流 | ✅ 已完成并验证 |
| 3 | 目标网盘**空间满了**无法转存时，提示「网盘空间满了，无法转存，请先删除部分文件腾出空间，之后才能正常在线播放和下载」 | `BaiduPan.quota()` 容量预检 + `MSG_NO_SPACE` 统一话术 + 三入口提示 | ✅ 已完成并验证 |

### 结论速览

1. **置顶不用伪造日期**，单独开 `pin_top` 列。旧做法（把 `release_date` 伪造成 9999 年）会让假日期直接显示在详情页资料区里，脏。
2. **转存建文件夹的判据是「分享根条目里有没有散文件」，不是「猜分享是文件夹型还是文件型」**。同一份分享经常是「根目录混着文件夹和散文件」，按类型猜必然漏。
3. **「空间满了」不能靠 errno 判**。外部核实后发现百度对空间不足的 errno **说法互相矛盾**（同一份资料把 `4` 说成存储问题、把 `-9/-10` 说成容量不足，另有 `31116/36009` 明确指空间）。本轮改成**用 `/api/quota` 拿真实剩余容量做确定性判定**，errno 只当旁证。
4. 三期改动**不影响老库**：新 App 装在老库上会自动退回旧排序（探测不到 `pin_top` 就换 `ORDER_BY_PLAIN`），不会白屏。

---

## 1. 需求 1：测试库入库 + 置顶机制

### 1.1 为什么加列，而不是复用 `release_date`

「暂时放在首位」最省事的做法是把 `release_date` 写成一个很晚的日期（比如 `9999-12-31`），靠现有排序白捡置顶。**这个做法没被采用**：

- 详情页的资料区直接读 `release_date` 显示，用户会看到「上映：9999-12-31」；
- 「置顶」与「上映日期」是两个语义，混用之后就没法单独取消置顶（取消就得把日期改回去，而原日期已丢失）。

所以 `movie` 表加一列 `pin_top INTEGER`，语义单一：**非 0 即置顶，数字大的排前面**。取消置顶只需 `pin_top=0`，其它字段一个不动。

### 1.2 手工条目的身份约定

手工条目不是论坛抓来的，必须能被 `sync` 认出来，否则下次同步会覆盖它或把它当孤儿删掉。

| 字段 | 取值 | 理由 |
|---|---|---|
| `src_tid` | `-1` | **论坛 tid 恒为正**，负数等于「这条不是抓来的」。sync 的 upsert 与孤儿清理都按 `src_tid` 走，天然不碰它 |
| `uid` | `manual-dts-test` | 与 `t####` 形式的论坛 uid 区分，便于 `WHERE uid LIKE 'manual-%'` 批量管理 |
| `fid` | `112` | 「4K全景声」栏目（数据侧 `forum_name='4KSDR.Remux'`） |
| `pan_status` | `ok` | 只有 `ok` + 有 `pan_url` 才会出现在列表里 |
| `pin_top` | `1` | 置顶 |

> 负数 tid 还有一层保险：App 的 `MovieAdapter` 按 `tid` 定位条目，负值不会与任何真实 tid 撞号。

### 1.3 视图契约：`v_movie_app` 必须暴露 `pin_top`

二期定下的架构保险是「**App 只读视图，不读基表**」。所以加列不够，还要让视图把它带出来，否则 App 的 `ORDER BY` 用不了它。

```sql
-- v_movie_app 片段（与 tools/db_upgrade_v2.py 的 DDL 逐字对齐）
    COALESCE(m.pin_top, 0)                         AS pin_top,
```

用 `COALESCE(...,0)` 而不是裸列：`ALTER TABLE ADD COLUMN` 加出来的列在老行上是 `NULL`，裸用会让排序出现「NULL 怎么排」的平台差异（SQLite 把 `NULL` 当最小值，但显式写 0 更不容易被误解）。

### 1.4 App 侧：新 App 装在老库上不能白屏

```java
// MovieStore.java
private static final String ORDER_BY_PIN =
        "COALESCE(pin_top,0) DESC, COALESCE(release_date,'') DESC, "
                + "COALESCE(year,0) DESC, src_tid DESC";
private static final String ORDER_BY_PLAIN =
        "COALESCE(release_date,'') DESC, COALESCE(year,0) DESC, src_tid DESC";

private static volatile Boolean pinKnown;

private static boolean pinTopReady() {          // PRAGMA table_info(v_movie_app) 找 pin_top
    Boolean k = pinKnown;
    if (k != null) return k;
    ...
}
private static String orderBy() { return pinTopReady() ? ORDER_BY_PIN : ORDER_BY_PLAIN; }
```

关键点：**在 `ORDER BY` 里写一个不存在的列，整条查询会报错**——不是「忽略这一列」，是**整个列表查空**。老库（schema v1）的视图没有 `pin_top`，如果无条件写死 `ORDER_BY_PIN`，装了新版 App 的老用户会看到「这个版块暂时没有内容」。

```java
// 换库之后必须重探 —— 在线更新把库从 v1 换成 v2 的那一刻，
// 缓存的 pinKnown=false 就过期了，不重置会一直用旧排序。
reload()    { ... pinKnown = null; ... }
database()  { ... pinKnown = null; ... }
```

`pinKnown` 用 `volatile`：探测器可能被多个查询线程同时触发。

### 1.5 数据落地现状（从产物库导出，非推演）

```
movie.id       = 327
src_tid        = -1
uid            = 'manual-dts-test'
fid            = 112
forum_name     = '4KSDR.Remux'
title          = '杜比DTS影音测试库'
title_cn       = '杜比DTS影音测试库'
year           = 2026
genres         = ["测试", "杜比", "DTS"]
pan_status     = 'ok'
pan_url        = 'https://pan.baidu.com/s/1_2dpi1iheep_U-eh_J5zHA'
pan_pwd        = 'fish'
pin_top        = 1
video_count    = 0        ← 分享里的真实文件数要转存后才知道，**没有编造**
total_size     = 0        ← 同上
```

`video_count` / `total_size` 留在 0 是刻意的。App 的角标在 `<=1` 时不显示，所以是 0 不会露出「0 个文件」这种脏信息；等真机转存成功后再回填。

### 1.6 排序效果（用与 App 完全相同的 SQL 对跑）

```
ORDER_BY_PIN（新库）                   ORDER_BY_PLAIN（老库回退）
1. src_tid=-1 杜比DTS影音测试库  pin=1  1. src_tid=36062 兄弟连：薪火永续
2. src_tid=36062 兄弟连：薪火永续       2. src_tid=36001 求救信号
3. src_tid=36001 求救信号               3. src_tid=35989 诈死游戏
```

两条查询首位不同 —— 这才证明**置顶真的生效，而不是巧合**（如果两条一模一样，说明 pin_top 压根没起作用，只是原排序本来就是它）。

### 1.7 幂等与可回滚

迁移脚本 `tools/db_manual_entries.py` 是**幂等**的，可反复跑：

- upsert 前**逐列比对值**，值没变就一个字都不写（否则每次跑都刷 `updated_at`，「重跑产物是否一致」这个基本自查就失效了）；
- `VACUUM` 只在「确实改过东西 / journal 不是 delete / 有侧写文件」时才执行 —— `VACUUM` 会推进 SQLite 头部 change counter，本身就会改字节。

实测：连跑两次，`sha256` **完全相同**。

回滚：`python tools/db_manual_entries.py --db build/SuperMOV.db --unpin` 一键清掉所有置顶；要整条删除手工条目，按 `WHERE src_tid < 0` 删即可（论坛数据不会命中这个条件）。

---

## 2. 需求 2：转存目录决策

### 2.1 旧行为 vs 新行为

| 分享结构 | 旧行为 | 新行为 |
|---|---|---|
| 根目录是 **1 个文件夹**（如 `/老友记/…`） | 原样转存 → 网盘根出现 `老友记` | **不变** → 网盘根出现 `老友记` |
| 根目录是 **散文件**（如 `/xxx.mkv` `/yyy.ass`） | 原样转存 → 网盘根**散落一堆文件**，跟别的片子混在一起 | 先建 `网盘根/片名/`，再转进去 |
| 根目录**混着**文件夹和散文件 | 全部原样落到网盘根 | 散文件进 `网盘根/片名/`；文件夹仍落网盘根 |

### 2.2 判据不是「猜分享类型」，是「逐条看根条目」

最容易写错的实现是「先判断这个分享是文件夹型还是文件型，再决定怎么转」。**这个思路必然漏**——百度分享的根列表经常是混合的：

所以实现改成**逐条分流**：

```java
JSONArray dirArr  = new JSONArray();   // 根条目里的目录
JSONArray fileArr = new JSONArray();   // 根条目里的散文件
long rootFileBytes = 0;
for (int i = 0; i < roots.length(); i++) {
    JSONObject f = roots.optJSONObject(i);
    if (f == null || f.optLong("fs_id", 0) <= 0) continue;
    if (f.optInt("isdir", 0) == 1) dirArr.put(f);
    else { fileArr.put(f); rootFileBytes += Math.max(0, f.optLong("size", 0)); }
}
```

- 权威数据源是 `share/list` 的**根列表**（二期已定：`根列表权威，原样下发根条目递归复制`），不解析分享页 HTML 的 `yunData`（那条路会被风控和页面改版打断）。
- 判据是**条目自己的 `isdir`**，不是分享的某个全局类型字段。混合分享因此天然被正确处理。

### 2.3 落地目录

```java
String root = trimSlash(targetDir);
String fileDest = root;                       // 默认：散文件也落根目录（老行为）
if (fileIds.length > 0 && movieName != null && !movieName.trim().isEmpty()) {
    fileDest = root + "/" + safeDirName(movieName);   // 有散文件 + 有片名 → 建片名文件夹
    if (!ensureDir(fileDest)) { …失败返回… }
}
transferBatch(fileIds, fileDest, …);   // 文件 → 片名文件夹
transferBatch(dirIds,  root,     …);   // 文件夹 → 根目录
```

**顺序有意如此**：文件批次先发，让 `toPaths.get(0)` 就是片名文件夹 —— 调用方（详情页/播放页）拿第一个路径就能定位到这部片，不必再按片名猜。

`movieName` 为空的兜底：调用方没传片名时退回老行为（散文件落根目录），而不是建一个叫「未命名影片」的文件夹 —— 建错文件夹比不建更难收拾。

### 2.4 片名目录名清洗（`safeDirName`）

**片名带冒号是常态**：《志愿军：雄兵出击》。百度对目录名的硬约束是**不能含 `/ \ : * ? " < > |`**，否则报 `errno=-7「文件或目录名错误」`，而且**是在建目录那一步就失败**，整个转存直接断掉。

```java
static String safeDirName(String name) {
    String s = name == null ? "" : name.trim();
    s = s.replace('\uff1a', ':');                        // 全角冒号先归一，下一步统一处理
    s = s.replaceAll("[\\\\/:*?\"<>|\r\n\t]", " ");
    s = s.replaceAll("\\s+", " ").trim();
    s = s.replaceAll("[. ]+$", "");                      // 结尾的点和空格会被吃掉 → 「建了却找不到」
    if (s.length() > 60) s = s.substring(0, 60).trim();  // 长度有上限，超了会被截断
    if (s.isEmpty()) s = "未命名影片";
    return s;
}
```

三处不显眼但会真出问题的细节：

1. **全角冒号 `：` 也要处理**。中文片名用全角冒号，百度同样拒绝；
2. **结尾的点和空格必须去掉**。部分系统会静默吃掉结尾空白，结果是「建目录返回成功、按原名再查却找不到」——这种错最难查；
3. **60 字截断**。百度目录名有长度上限，超长会被截断或直接失败，而我们后续要按这个名字定位文件。

### 2.5 分批与 `errno=12` 顺延

`errno=12` 表示「目标目录已存在同名项，这批里有文件已存在」。旧实现为不丢文件，会顺延出 `(2)`~`(5)` 再转一遍。**这个策略保留，但顺延的基准改了**：

```java
String alt = dest.replaceAll("/$", "") + " (" + n + ")";   // dest 现在可能是 root/片名
```

以前无论写去哪儿都顺延**根目录**；现在文件进的是 `root/片名/`，冲突自然也该在 `root/片名 (2)/` 上解决。顺延成功的目录会记进 `out.superseded`，提示语随之变成「目标目录已有同名影片，已转存到 → …」。

`(2)`~`(5)` 都被占用时不再无限顺延，而是明确报「请在网盘清理后重试」——顺延到 `(99)` 只会把网盘搞成一堆垃圾目录。

### 2.6 调用方改动

`transfer(shareUrl, pwd, targetDir)` 保留为 3 参重载，内部委托给 4 参版本并传 `null` 片名 —— 这样老调用点不会编译失败，行为等于老逻辑。四个真实入口统一切到 4 参：

| 入口 | 文件 | 传的片名 |
|---|---|---|
| 详情页自动转存（进页面即解析） | `DetailActivity.ensureResolved()` | `movieName` |
| 详情页「转存到我的网盘」按钮 | `DetailActivity` | `movieName` |
| 首页长按快捷转存 | `MainActivity` | `name` |
| 播放页进度缺失时的自动转存 | `PlayerActivity` | `name` |

---

## 3. 需求 3：空间不足提示

### 3.1 先说结论：errno 不能用来判「空间满」

需求听上去是「捕获空间不足的 errno，弹提示」。**这条路走不通**，外部核实的结果是百度对空间不足返回的 `errno` 并不唯一，而且各路说法互相矛盾：

| 流传的说法 | 问题 |
|---|---|
| `errno=4` | 被解释为「存储问题/系统繁忙」，同时又被解释为「空间不足」，还被解释为「非会员转存次数超限」 |
| `errno=-9` / `errno=-10` | 被说成容量不足；但同一批资料里 `-10` 又指「分享已失效」 |
| `errno=31116` / `36009` | 明确指空间不足（可用的白名单） |
| `errno=12` | 明确是「目标目录已存在」，与空间无关 |

**错报的代价是不对称的**：误报「空间不足」会让用户去删本来不该删的文件；漏报只是提示语变成一句普通 errno。所以判定必须**从确定到不确定**，宁可原样报 errno，也不猜。

### 3.2 改用确定性判定：真实容量 vs 真实体积

主判据改成**直接比较真实剩余空间与要转存的体积**：

```
GET https://pan.baidu.com/api/quota?checkfree=1&checkexpire=1&clienttype=0&app_id=250528&web=1
  → { errno, total, used, free }
```

判定链（`BaiduPan.transfer` 与 `fillFailure`）：

| 顺序 | 判据 | 性质 |
|---|---|---|
| 1 | **容量预检**：`free < 需要体积` 或 `free < 20MB` | 确定性，转存前就拦下 |
| 2 | 响应体含「空间不足/容量不足/剩余空间/空间已满/存储空间不足/购买空间/扩容」 | 强旁证 |
| 3 | `errno ∈ {31116, 36009, -999}` | 白名单，明确的空间码 |
| 4 | **容量旁证**：`free < 20MB`（不管 errno 是什么） | 弱旁证，但结论对用户最有用 |
| — | 以上都不成立 | **原样报 errno**，不猜 |

### 3.3 阈值从哪儿来

```java
private static final long SPACE_PLENTY_BYTES = 150L * 1024 * 1024 * 1024;  // 150 GB
private static final long SPACE_DANGER_BYTES = 20L  * 1024 * 1024;         // 20 MB
private static final int  SIZE_WALK_DIRS     = 6;                          // 统计体积最多列 6 个目录
```

- **150 GB**：剩余超过这个数就直接跳过体积统计。库里最大的片子也就几十 GB，150 GB 余量下不可能放不下，省掉一次目录遍历（大分享那是几百次请求）。
- **20 MB**：库里全是 4K 片源，最小也几百 MB。剩余低于 20 MB 时，**任何片都放不下**，此时不管 errno 是什么，「空间满了」都是对用户最有用的结论。
- **6 个目录**：体积统计的预算上限。

### 3.4 为什么「体积下界」就够用

分享根是文件夹时，目录项的 `size` 在百度侧**常为 0**，只能递归统计：

```java
private static long shareSizeLowerBound(String shareid, String uk, String referer) {
    long[]  acc    = new long[]{0L};
    int[]   budget = new int[]{SIZE_WALK_DIRS};
    walkSize(shareid, uk, "/", referer, acc, budget, 0);   // 深度 ≤3，最多 6 个目录
    return acc[0];                                          // 这是下界，不是精确值
}
```

我们要判断的是「剩余空间 < 需要的大小」。下界偏小只会让我们**少判几次空间不足**（退回去走 errno 旁证），**不会误判成空间不足**——方向是安全的。所以预算用尽可以直接停，不必为了一个精确总数把整棵目录树爬完。

### 3.5 统一话术

```java
public static final String MSG_NO_SPACE =
        "百度网盘空间已满，无法转存。\n"
                + "请先删除网盘里的部分文件腾出空间，之后才能正常在线播放和下载。";
```

任何入口报这个错都用同一个常量，保证口径一致。后面可以挂一行上下文，例如：

```
百度网盘空间已满，无法转存。
请先删除网盘里的部分文件腾出空间，之后才能正常在线播放和下载。
（需要 12.34 GB，网盘只剩 512.00 MB）
```

> 需求原文是「才能**正在**在线播放和下载」，按语义应为「正常」，实现里用了后者。

### 3.6 三个入口的表现差异（有意设计）

| 入口 | 表现 | 理由 |
|---|---|---|
| 详情页（自动转存） | **弹框**：标题「网盘空间不足」，正按钮「知道了」，负按钮「去清理空间」→ 打开 `https://pan.baidu.com/main/disk` | 这是用户主动进详情页等播放，失败了必须说清楚，且给一条直达清理页的路 |
| 详情页 / 首页（手动按钮） | 按 `spaceFull` 分流：弹框 or 普通 toast | 同上 |
| 播放页（自动转存） | **只 toast，不弹框**；且 `fail(tr.spaceFull ? tr.message : "转存失败：" + tr.message)` | 播放页弹框会中断播放本身。而且这条话术已经是完整句子，再套「转存失败：」会变成半截话 |

电视遥控器上的焦点细节：弹框默认焦点在「知道了」，避免一按确认就跳到浏览器把用户弹出应用。

---

## 4. 数据库变更清单

| 项 | 内容 |
|---|---|
| 新增列 | `movie.pin_top INTEGER` |
| 新增索引 | `idx_movie_pin ON movie(pin_top)` |
| 重建视图 | `v_movie_app` 新增 `COALESCE(m.pin_top, 0) AS pin_top`（列数 30 → **31**） |
| 新增数据 | `movie.id=327`，`src_tid=-1`「杜比DTS影音测试库」 |
| `meta` | `db_version=2026091902`、`db_build_id=29a23ce486893d5a`、`generator=db_manual_entries/1.0`、`counts={"movie":327,"pan_link":499,"pan_video":2525,"ext_id":625}` |
| 文件形态 | 单文件、`journal_mode=DELETE`、无 `-wal/-shm/-journal` 侧写文件、`VACUUM` 收紧 |

产物指纹：

```
size   = 2531328 字节 (2.41 MB)     ← 上一版 2527232
sha256 = 3dd45ca6cb0f1f7728920a24f982261312a0fca4f05db470357cd0c97c8b814a
```

### 4.1 两条升级路径的 DDL 必须对齐

同一个库有两条进入 v2 的路：

- **离线/打包路径**：`tools/db_manual_entries.py`（产内嵌库）
- **在线更新路径**：`tools/db_upgrade_v2.py`（设备上跑）

两条路走完出来的结构必须一样，否则线上排查时会遇到「同样的库、不同的表结构」。本轮发现并修掉了一处不一致：**`db_manual_entries.py` 之前没建 `idx_movie_pin`**，导致内嵌库比在线升级出来的库少一个索引。

修法是在迁移里加第 3b 步 `ensure_indexes()`（`CREATE INDEX IF NOT EXISTS`），并在两个脚本的校验里各加一条断言。这类一致性断言值得留着：改一处忘另一处的成本，比多跑两行校验高得多。

新增 changelog 条目：

```
2026091902  2026-09-19 10:20
置顶机制(pin_top) 补齐 idx_movie_pin 索引（与 db_upgrade_v2 的 DDL 对齐）；
手工条目「杜比DTS影音测试库」(fid=112 4K全景声, 首位) 不变
```

---

## 5. 验证：沙箱没有 javac，靠五道本地防线

云端编译一次要跑几分钟，而且失败信息埋在 Actions 日志里。**SQL 写错、符号悬空、括号不平衡这类问题在那里是「编译过了、运行才崩」，最难查**。所以推送之前先本地拦一遍。

| # | 脚本 | 拦什么 | 本轮结果 |
|---|---|---|---|
| 1 | `tools/precheck.py` | 13 项结构化检查（文件存在、CRLF 一致性、import 完整等） | ✅ 通过 |
| 2 | `tools/verify_symbols.py` | 跨类符号：调用的方法/常量在对方类里是否真的声明过 | ✅ **0 处悬空** |
| 3 | `tools/verify_sql.py` | 把 `MovieStore.java` 的 SQL **逐条在真实库上跑一遍** | ✅ **OK=56 FAIL=0** |
| 4 | `tools/verify_edits.py` | 完整性断言：每个改动都真的落地了 | ✅ **OK=94 FAIL=0** |
| 5 | `tools/java_balance.py` | 剥注释/字符串后检查 Java 括号平衡 | ✅ 5 个文件全平衡 |
| 6 | `tools/verify_apk.py` | 产物取证（构建后跑） | ⏳ 待云端构建产出 |

### 5.1 本轮对验证器本身做的两处加固

**(a) 验证脚本不能再手工抄 SQL 副本。** 上一轮的教训：脚本里存了一份 SQL 的副本，源码改了 SQL、脚本还在测那个已经不存在的查询，**而且照常打印 OK** —— 比没有验证更危险。

现在 `verify_sql.py` 第 0 节**从 `MovieStore.java` 源码里解析出** `LIST_COLS` / `ORDER_BY_PIN` / `ORDER_BY_PLAIN` / `CAT_FIDS` / `CAT_NAMES`，再与实际执行的 SQL 比对：

```
0) 脚本副本 vs 源码 —— 防止「验证器失效」
  OK   从源码解析出 LIST_COLS / ORDER_BY_PIN / ORDER_BY_PLAIN / CAT_FIDS / CAT_NAMES
  OK   LIST_COLS 与源码一致
  OK   ORDER_BY_PIN 与源码一致
  OK   ORDER_BY_PLAIN 与源码一致
```

**(b) 校验产物一致性，而不只是「文件在不在」。** 新增两条断言：

- **`SuperMOV.version` 里的 sha256 == `assets/SuperMOV.db` 的真实 sha256** —— 防「验的是 A 库、发的是 B 库」，那种情况下 App 端 sha256 校验会直接失败；
- **`assets` 库 == `build` 源库** —— 防忘记同步。

### 5.2 本轮踩到的坑（都已修）

| 坑 | 现象 | 修法 |
|---|---|---|
| 同文件并行编辑 | 两个编辑互相静默覆盖 | 改成「带锚点唯一性校验的补丁脚本」一次落地 |
| 验证脚本副本过期 | 源码改了、脚本测旧查询仍打 OK | 从源码解析常量再比对（见 5.1a） |
| 迁移不幂等 | 连跑两次 sha256 不一致 | upsert 逐列比差值、meta 未变不刷、`VACUUM` 仅在真有改动时执行 |
| CRLF 锚点匹配 | `_patch_baidupan.py` 锚点 0 匹配（文件 CRLF、锚点 LF） | 补丁脚本内 `text.replace("\r\n","\n")` 归一化匹配、落盘还原 CRLF |

---

## 6. 未决项与风险（**都还没验证，别当成已完成的**）

| # | 事项 | 状态 | 影响 |
|---|---|---|---|
| 1 | **提取码 `fish` 是否正确**、分享是否仍有效 | ❌ **未验证** | 沙箱 IP 被百度风控：`share/verify` 对 `fish` / `zzzz` / **空码** 返回**完全相同**的 `errno=105`。三种输入结果一致 → 说明是被风控拦下，而不是「密码对/错」 |
| 2 | 真实转存（建片名文件夹 → 落盘） | ❌ **未验证** | 同上，沙箱无法完成真实转存 |
| 3 | 「空间满了」提示是否真的会触发 | ❌ **未验证** | 需要「网盘剩余 < 影片体积」的账号才能触发。判定链是确定性的，但**没有真实触发过一次** |
| 4 | `video_count` / `total_size` = 0 | ⏳ 待回填 | 需要真机转存成功后才知道真实值；现在填 0 是不编造 |
| 5 | 片名文件夹在电视端的实际观感 | ⏳ 待真机 | 长片名被截到 60 字后是否还可读 |

### 6.1 风控这条要记住

`share/verify` 返回 `errno=105`、且**不同提取码返回完全相同的响应** —— 这是判断「被风控」而不是「密码错」的关键特征。以后在沙箱里探测百度分享，先做这个对照（故意传一个错误密码 + 空密码），如果三者一致，就是风控，不要再纠结参数写法。

**这三项要靠真机验证**：装 APK → 登录百度 → 进「4K全景声」栏目 → 看到置顶的测试库 → 点进去自动转存 → 观察网盘里是否出现「杜比DTS影音测试库」文件夹。

---

## 7. 交付物清单

**代码（`DJYDXS-TV/app/src/main/java/com/supermov/tv/`）**

| 文件 | 改动 |
|---|---|
| `MovieStore.java` | `ORDER_BY_PIN` / `ORDER_BY_PLAIN`、`pinTopReady()` 探测、`orderBy()`、换库后重置缓存 |
| `BaiduPan.java` | `TransferResult.spaceFull` / `superseded`、4 参 `transfer` 重载、`quota()`、`shareSizeLowerBound()`、`safeDirName()`、`transferBatch()`、`fillFailure()`、`MSG_NO_SPACE` |
| `DetailActivity.java` | 4 参调用、`showNoSpaceDialog()`、自动转存与手动转存分流 |
| `MainActivity.java` | 4 参调用、`showNoSpaceDialog()` |
| `PlayerActivity.java` | 4 参调用、空间不足不套前缀 |
| `app/build.gradle` | `versionCode 29 → 30`、`versionName "1.29" → "1.30"` |

**数据库资产**

| 文件 | 内容 |
|---|---|
| `app/src/main/assets/SuperMOV.db` | 2531328 字节，`sha256=3dd45ca6…b814a`，含 `pin_top` + 手工条目 |
| `app/src/main/assets/SuperMOV.version` | `db_version=2026091902` 等五键 |
| `SuperMOV.json`（在线更新清单） | `version=2026091902`、`movies=327`、`4KSDR.Remux` 89 → **90** |

**工具**

| 文件 | 用途 |
|---|---|
| `tools/db_manual_entries.py` | 手工条目 + 置顶迁移（幂等、事务安全、含索引自愈） |
| `tools/db_upgrade_v2.py` | 在线升级（补 `pin_top` 列 / 视图 / 索引） |
| `tools/verify_sql.py` | SQL 预演 + 源码常量比对（第 0 节新增） |
| `tools/verify_edits.py` | 完整性断言 94 项（含产物 sha256 一致性） |
| `tools/verify_apk.py` | 产物取证（新增三期断言段 `[C2]`） |
| `tools/java_balance.py` | 无 javac 环境下的括号平衡检查 |

---

## 8. 运营操作手册

**加一条手工影片到某个栏目首位**

1. 编辑 `tools/db_manual_entries.py` 的 `MANUAL_ENTRIES`，加一条：
   ```python
   {
       "src_tid": -2,                       # 下一个负数，别重复
       "uid": "manual-xxx",
       "fid": 112,
       "forum_name": "4KSDR.Remux",
       "classification": "4KSDR.Remux",
       "title": "片名",
       "title_cn": "片名",
       "year": 2026,
       "pan_status": "ok",
       "pan_url": "https://pan.baidu.com/s/xxxx",
       "pan_pwd": "abcd",
       "video_count": 0, "total_size": 0,
       "pin_top": 1,
   }
   ```
2. `python tools/db_manual_entries.py --db build/SuperMOV.db`
3. `python tools/build_manifest.py --db build/SuperMOV.db --out SuperMOV.json --version-file DJYDXS-TV/app/src/main/assets/SuperMOV.version`
4. `cp build/SuperMOV.db DJYDXS-TV/app/src/main/assets/SuperMOV.db`

**取消某条置顶**：把该条 `pin_top` 改成 `0` 重跑，或 `--unpin` 清掉全部。

**换掉专栏白名单 / 显示名**：改 `MovieStore.java` 的 `CAT_FIDS` / `CAT_NAMES`，然后必须复跑 `tools/verify_sql.py`（第 0 节会比对源码与脚本，改一处忘另一处会被拦住）。

**改转存目录行为**：只改 `BaiduPan.transfer` 里 `fileDest` 那段。`safeDirName` 的清洗规则**不要动**，每一条都对应一个实测过的失败（冒号 → `errno=-7`、结尾点空格 → 建了找不到、超长 → 被截断）。
