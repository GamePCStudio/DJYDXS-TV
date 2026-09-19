# DJYDXS3Nexio 数据库重构方案 · 二期

> 承接《DJYDXS3Nexio-数据库重构方案.md》（一期）。
> 一期解决的是「从论坛 cookie 拉取 → 从本地数据库读」，二期解决**三件新事**：
> 数据库结构如何演进、转存策略如何固化、转存后如何更名。
>
> 本文件同时是**阶段 1 的实现说明**——文中提到的 `tools/`、`build/`、`preview/`
> 以及 `app/src/main/java/com/supermov/tv/` 下的新类都已经落地并跑通。

---

## 0. 本次实测数据（不是推演，是从真实库跑出来的）

| 项 | 结果 |
|---|---|
| 源库 | `C:\py\SuperMOVDB\SuperMOV.db`，schema v1，1.89 MB，326 部 / 499 链接 / 2525 视频 |
| 迁移产出 | `build/SuperMOV.db`，schema v2，**2.41 MB**，sha256 `0d6b8f886b0281b5…` |
| 干净中文名 | **326 / 326 = 100%** |
| 英文名 | 310 / 326（16 部确实没有官方英文名，属正常） |
| 年份 | 322 / 326 |
| **豆瓣 ID** | **322 / 326（99%）** ← 一期以为只有 3%，是正则写错了 |
| **IMDb ID** | **303 / 326（93%）** |
| 类型 / 产地 / 语言 / 上映日期 | 319 / 322 / 322 / 321 |
| 主创（导演 414 + 编剧 766 + 演员 313 条） | 覆盖 **321 / 326 部** |
| 简介 | 96 / 326 ← **被 `content` 600 字截断卡死了**，见 §1.6 |
| 完整性校验 | `integrity_check = ok`，孤立 `pan_video` = 0，`title_cn` 空 = 0 |

**三条实测出来的关键事实，直接决定了下面的设计：**

1. **`movie.content` 被硬截断在 600 字符**（`max=600 / median=600 / p75=600`）。
   而 `◎简 介`、`◎主 演` 这些字段在帖子正文里**排在最后面**，所以「简介」只有 29% 的覆盖率。
   这不是解析写错了，是数据本身被砍掉了。
2. **上映日期/豆瓣链接的域名有两种写法**：`movie.douban.com/subject/xxx` 和
   `douban.com/subject/xxx`。只认前者会漏掉 96% 的豆瓣 ID（一期就栽在这里）。
3. **发帖人会把 `◎译 名` 和 `◎片 名` 写反** —— 实测 **23 部**（如
   `◎译 名 Pegasus 3 ◎片 名 飞驰人生3`）。所以解析**不能认字段名，只能认内容语言**。

---

## 1. 第一件事：数据库结构该怎么演进

> 需求原话：「数据库未来会重构，会直接加入干净的影片名称，同时还会加入诸如影片豆瓣ID、TMDB ID 之类方便扩展的字段，你有什么好建议，也可以给出」

### 1.1 【最重要的建议】外部 ID 不要加成列，要用 ID 表

这是本次最该坚持的一条。**不要说 `movie.douban_id` / `movie.tmdb_id` / `movie.imdb_id` 各加一列**，而是建一张通用表：

```sql
CREATE TABLE movie_ext_id (
    movie_id   INTEGER NOT NULL,
    source     TEXT    NOT NULL,   -- douban / imdb / tmdb / tvdb / bangumi / myanimelist / ...
    id         TEXT    NOT NULL,   -- 5675910 / tt28814949 / 12345
    subtype    TEXT,               -- movie / tv / season / episode
    season     INTEGER,            -- 仅 subtype=season 有值
    episode    INTEGER,            -- 仅 subtype=episode 有值
    url        TEXT,
    confidence INTEGER DEFAULT 100,-- 95=正文明确, 60=推断, 100=人工确认
    origin     TEXT,               -- content / manual / api
    created_at TEXT, updated_at TEXT,
    PRIMARY KEY (movie_id, source, id, subtype)
);
```

为什么值得多一张表：

- **ID 空间会一直长**。今天是豆瓣 / IMDb / TMDB，明天就是 TVDB、Bangumi、豆瓣剧集、
  MyAnimeList、WikiData。加列方案每加一个来源就要 `ALTER TABLE` + 改一遍 App 的读代码；
  ID 表方案**零 DDL、零 App 改动**。
- **一部片在同一来源可能有多个 ID**。剧集的「剧集 ID」和「某一季 ID」在 TMDB 是两个数；
  一部片既有豆瓣电影页也有豆瓣剧集页。列方案表达不了，`subtype` + `season` 能。
- **需要记来源与置信度**。正文里扒出来的是 90，人工核对过的是 100，按片名猜的是 60。
  将来要做「自动匹配元数据」时，这个字段决定谁覆盖谁。
- **能做反查**。`WHERE source='douban' AND id='5675910'` 直接命中，天然带索引。

**同时建议给 `movie` 加 `title_cn / title_en / title_alt / year / ...` 这些真实列**——
它们要参与排序、筛选、搜索，是「热字段」，必须落列不能用 EAV。

一句话的分工：**要排序筛选的 → 列；只是标识符与溯源 → ID 表。**

### 1.2 `uid`：现在就定死，将来不要改

一期我把 `uid` 定为 `t{src_tid}`（例：`t23566`）。这里要补一条**决策纪律**：

> **`uid` 一旦发布就永久不变，将来就算有了豆瓣 ID 也不要换成 `d{豆瓣_id}`。**

原因：App 会在本地按 `uid` 存播放进度、收藏、下载记录、更名台账。
`uid` 一换，所有用户状态全部错位——「看到一半的片子变成另一部」。

同理，**绝对不要用片名派生 uid**：片名会被运营修正（「千年魔界」→「千年魔戒」，
实测库里这两个名字都出现过），片名也会重复（同名不同版）。

所以：`src_tid` 做 `uid` 是当前唯一稳定的锚点。豆瓣 ID 只作为**附加 ID 存在 `movie_ext_id`**，
用来跳转、匹配元数据、去重提示，不承担主键职责。

> 补充：`movie.id`（自增）也**不可**当跨端标识。重建库时行序可能变，自增值就会漂移。
> 对外的标识一律走 `uid`。

### 1.3 `schema_version` 契约：让「库变了」这件事可协商

一期的方案里已经有了 `schema_version`，这里把它升级成一份**硬契约**：

| 变更类型 | 例子 | 是否 bump `schema_version` | App 行为 |
|---|---|---|---|
| **加列 / 加表 / 加视图** | 加 `tmdb_id`、加 `movie_asset` | **不 bump** | 老 App 照常用，新字段看不见而已 |
| **改语义 / 删列 / 改类型** | `content` 改成结构化 JSON、删 `forum_name` | **必须 bump** | 老 App 检测到 `schema_version > 支持上限` → **明确提示「请升级应用」**，不硬读 |

落到 `meta` 表：

```
schema_version   = 2      -- 结构版本，只在破坏性变更时 +1
db_version       = 2026091901   -- 内容版本，每次同步递增，用户可见
db_build_id      = 66113014c6e46466  -- 内容指纹，内容不变则不变
min_app_version  = 28     -- 完整利用本库（在线更新 + 更名规则）所需的最低 versionCode
generator        = db_upgrade_v2/1.0
db_built_at      = 2026-09-19 07:17:35
```

> 线上当前 `versionCode = 27`，本次重构随 **28** 发布。
> 低于 28 的老版本没有在线更新与更名能力，但**仍然能读内嵌库**（因为 v2 全是加法），
> 所以这里的 `min_app_version` 只作说明用，不能拿它去拦老用户。

App 侧对应的判断（已实现在 `MovieDb.isSchemaReadable()`）：
**库比自己新 → 提示升级；库比自己旧 → 静默降级**。这两种情况必须区别对待，
一期的文档里混在一起了。

### 1.4 【关键的架构保险】让 App 只读视图，不读基表

这是「未来数据库会调整」这件事的**终极答案**。已建两个视图：

- **`v_movie_app`** —— App 的列表/详情主视图。列名是**面向 UI 的稳定契约**：
  `uid, name, title_en, year, rating_douban, rating_imdb, genres, region, poster,
   video_count, shareid, share_uk, pan_link_id, ext_id_count …`
- **`v_episode`** —— 影片展开到剧集：`movie_id, movie_uid, movie_name, path, filename,
  ext, final_filename, size, season, episode, shareid, share_uk …`

这样做的好处非常具体：

> 将来运营把 `movie.title` 拆成三张表、把 `content` 换成 JSON，
> **只要视图的列名不变，App 一行代码都不用改。**
> 视图就是对内结构变化的缓冲层。App 里写 `SELECT name, year FROM v_movie_app`，
> 而不是 `SELECT title_cn, year FROM movie WHERE src_deleted=0 AND ...`。

配套纪律：**App 侧禁止直接 `SELECT` 基表**（`movie` / `pan_link` / `pan_video`）。
`pan_video` 只通过 `v_episode` 访问。

### 1.5 建议的完整目标结构

```
movie              正片骨架（uid / title_cn / title_en / title_alt / year / region /
                   language / genres / rating_* / runtime_min / release_date / synopsis /
                   poster_url + 一期的源站字段）
movie_ext_id       ★ 外部 ID（douban/imdb/tmdb/…，subtype 区分电影/剧集/季）
movie_credit       ★ 主创：role(director/writer/actor) + name + name_en + ord
movie_asset        （建议新增）海报/背景/剧照：kind + url + source + w/h
pan_link           分享链接（url/pwd/status/shareid/share_uk/dir_count/file_count…）
pan_video          文件清单（path/filename/ext/size/season/episode + fs_id + rename_*）
rename_rule        ★ 转存后更名规则
meta               版本与构建信息
v_movie_app        ★ App 列表/详情视图
v_episode          ★ App 剧集视图
```

`★` = 本次新增。

`movie_asset` 与 `movie_credit` 建议现在就加上——它们的信息目前**只能挤在 `content` 里**，
而 `content` 是被截断的。抽成表以后，抓一次就永久干净。

### 1.6 必须解决：`content` 的 600 字截断

这一条要单独讲，因为它会**卡死所有"资料类"字段**。

实测：`content` 的 `max / median / p75` 全是 600。而帖子正文的排布是
`译名 → 片名 → 年代 → 产地 → 类别 → 语言 → 上映日期 → 评分 → 链接 → 片长 → 编剧 → 主演 → 简介`。
**`◎简 介` 排在最后**，所以被砍掉了：简介覆盖率只有 96/326（29%），
好在 `◎豆瓣链接` 通常还在 600 以内，所以豆瓣 ID 才能有 99%。

**两条路，我推荐第二条：**

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 放宽截断 | sync 侧把 600 改成 4000 或干脆不截 | 改动最小，但库会明显变大，且原文里大量重复的论坛模板文字也会一起进库 |
| **B. sync 侧解析后入库**（推荐） | 解析出 `title_cn/title_en/year/genres/actors/directors/writers/synopsis` **直接落成列和表**，正文原文**不再存**（或单独放 `movie_raw` 且不打包进 APK） | 库更小、更干净；**App 端不需要任何正则解析代码**，解析逻辑收敛在 sync 一个地方，改错了重新同步即可，不用发版 |

方案 B 还有一个隐藏好处：**解析规则集中在 Python 侧，可以用本文档第 5 节的
`tools/rename_plan.py --selftest` 那种方式写单元自检**，而不用为了验证一个正则去云端构建一遍 APK。

### 1.7 其他建议（按价值排序）

1. **给 `pan_video` 补 `fs_id`。** 即便坚持转存策略（转存后能自行 `listDir` 拿到 fs_id），
   库里有 fs_id 也能省掉「转存后重新列一遍目录」这一次网络往返。一期说这是为「不转存直播」，
   现在看它更大的价值是**更快**——而不是解锁新能力。
2. **给 `pan_link` 补 `surl`。** 百度接口要 `surl` 而不是完整 URL；现在每次都要用正则从
   `https://pan.baidu.com/s/1XXXX` 现切。存成一列，等于把一次解析错误变成一个查表。
3. **`movie_ext_id` 的 `subtype` 一定要用起来。** 特别是 TMDB：`tv/12345` 是剧，
   `tv/12345/season/2` 是季。别把季的 ID 塞进 `movie` 行里。
4. **海报别只存论坛附件地址。** `movie.pic` 现在指向 `djydxs.oss-cn-chengdu.aliyuncs.com`
   的论坛附件——这个域名将来一变，全库海报全裂。建议加 `poster_url` + `poster_source`，
   并把图片**镜像到自有 OSS**（你在 800915.xyz 上已经有地方放了）。这也是 §1.5 里
   `movie_asset` 的用途。
5. **加时间戳与溯源**：`movie_meta_updated_at`、`movie_ext_id.origin`。
   将来接了 TMDB 自动匹配，必须能分清「这条是抓来的还是人工填的」。
6. **别把用户状态写回 SuperMOV.db。** 它是可覆盖的只读库。转存台账、更名台账、播放进度
   全部放本地 `appstate.db`（见 §3.5）。这条必须写进纪律，否则某天库一更新，用户数据全没。
7. **合规提醒（不是技术问题但要说）**：TMDB 有明确的 API 使用条款，图片与数据商用受限；
   豆瓣无公开 API，抓取需谨慎。**只存 ID + 让用户跳转到官方页面**风险最低；
   如果要在 App 内展示 TMDB 的海报/简介，先确认授权范围。

---

## 2. 第二件事：转存策略（保持不变，但可以更强）

需求原话：「还是采用转存策略」。好——这也确实是正确的选择。转存策略的真正价值是：

> **转存之后影片在「自己的网盘」里。分享被删、被改提取码、被风控，都影响不到已转存的用户。**
> 代价是第一次要等转存完成（大剧集可能几十秒），换来的是长期稳定可播。

一期已经把 `BaiduPan.transfer()` 打磨得很扎实（`toPaths` 精确落地路径、errno=12 自动顺延
`(2)~(5)`、`share/list` 根条目整体递归复制保层级），**这一块不用动**。二期只在它周围加三样东西。

### 2.1 转存前：用库里的数据先告诉用户"要花多久、占多少"

`scheduled/计划` 已入库：`movie.video_count`、`movie.total_size`、`pan_link.video_count`。
所以点「转存」之前就能弹出：

> 将转存 **1107 个文件，约 607 GB**（数据截至 2026-09-19）

这一条现成就能做，且能挡掉大量"点了发现要几分钟又退出"的体验问题。一期提到的
`MAX_FILES = 300` 截断在这里也顺手解决——库里的数字是完整的，不经过 `listDir`。

### 2.2 转存中：可以省掉一次分享页解析（但要留兜底）

`pan_link` 表已经有 `shareid` 和 `share_uk`，且**327 条 ok 链接全部有值**（同步时就探测过了）。
于是 `transfer()` 里的第 3~4 步（打开分享页 → 正则提取 shareid/uk）可以跳过，直接用库里的值。

**但必须保留兜底**，原因：分享者重新分享后 `shareid` 会变。设计成：

```
用库里的 shareid/uk 直接发起 transfer
  ├─ errno=0            → 完成
  ├─ errno 表示"分享不存在/参数错" → 回退到"解析分享页"老路子，再试一次
  └─ 其它 errno          → 按原逻辑报错
```

这一步是**可选优化**，收益是一次 HTTP 往返；风险是 errno 分支要写对。
建议放到阶段 2 做，并且**必须在真机上抓一次「分享者重新分享」的场景验证**。
（`BDCLND` 仍然必须要——带提取码的分享一定要先 `/share/verify` 换 `randsk`，这一步省不掉。）

### 2.3 转存后：立刻做三件事

```
1. collectTargets() 已拿到 toPaths（精确落地路径，绝对不能靠片名猜）
2. ★ PanRename.renameAfterTransfer(ctx, toPaths)   ← 本次新增，见 §3
3. ★ 写本地转存台账（tid → 落地目录 / 是否已更名）
```

第 3 条的价值：转存是**用户可见的、不可撤销的、有成本的动作**。有了台账才能：

- 详情页显示「✅ 已转存到 /超级影库/XXX」，而不是每次点播放都重新转存一遍；
- 用户换了设备也能通过"重新转存"恢复（台账不跨设备，所以要允许重转）；
- 改名后仍然能定位到文件（台账里存 `oldPath → newPath`）。

---

## 3. 第三件事：转存后更名

需求原话：「部分影片未来还有转存以后的更名操作，比如原始影片存储在百度链接是 .xs 后缀，转存需要改名为 .mp4 后缀等等」

### 3.1 先说一句实话

**当前真实库里一个需要改名的文件都没有。** 实测后缀分布：

```
.mp4   1984
.mkv    541
```

没有任何 `.xs` / `.xsv` / `.txs`。所以：

- 这个功能**现在无法在真实数据上端到端验证**；
- 因此机制必须是**数据驱动**的，而不是把 `.xs→.mp4` 硬编码进 Java；
- 我另外提供了一个**不碰 Android 就能验证规则**的工具（§3.6），
  在真实文件名上把后缀替换成 `.xs` 来验证命中链路。

如果你手上确实有那个 `.xs` 的分享，**给我链接，我实测一次**，把真实行为（是否命中、
改名后能否播放、`filemanager` 的 errno 具体是什么）补进文档。

### 3.2 三条硬规则（踩过坑的结论）

**① 只改文件，绝不改目录。**
一旦改了目录名，目录下**所有子路径**都失效。而我们是按 `pan_video.path` 记绝对路径的，
改一个目录 = 整部剧的路径记录全废。所以 `PanRename.plan()` 里第一句就是
`if (f.optInt("isdir", 0) == 1) continue;`。

**② 改名必须发生在转存之后。**
分享里的文件不属于当前账号，改不了。转存到自己网盘后 `fs_id` 才是可改的。
所以调用点固定在 `transfer()` 成功之后。

**③ 改完必须回读校验，不能只看 errno。**
`filemanager?opera=rename` 的响应在不同账号/风控下不一致（有无 `async` 任务化、
返回结构差异）。所以实现是**提交 → 重新 `listDir` → 确认新名字真的在**，
三态分明：`ok` / `fail` / `skip`。

### 3.3 规则从哪来（这是设计的重点）

规则的**权威来源是数据库的 `rename_rule` 表**，Java 里只留一份兜底：

```sql
CREATE TABLE rename_rule (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    scope       TEXT NOT NULL,      -- 'ext' | 'filename_regex'
    pattern     TEXT NOT NULL,      -- '.xs'  或  正则
    new_ext     TEXT,               -- scope=ext 用
    replacement TEXT,               -- scope=filename_regex 用
    priority    INTEGER DEFAULT 100,
    enabled     INTEGER DEFAULT 1,
    note        TEXT
);
```

已预置（本次迁移写入 7 条，其中 6 条启用）：

| priority | pattern | new_ext | 说明 |
|---|---|---|---|
| 10 | `.xs` | `.mp4` | 需求里点名的那个 |
| 10 | `.xsv` | `.mp4` | 伪装后缀变体 |
| 10 | `.txs` | `.mp4` | 伪装后缀变体 |
| 20 | `.mp41` | `.mp4` | 数字尾巴笔误 |
| 20 | `.mp4v` | `.mp4` | 容器误标 |
| 20 | `.mkv1` | `.mkv` | 数字尾巴笔误 |
| 90 | `.rmvb` | `.mkv` | 预置但**默认关闭**（`enabled=0`） |

**为什么放数据库而不是硬编码**：伪装后缀的本质是「运营/分享者对抗探测」，花样一定会变。
放库里，运营改一行 SQL 就能生效，**不用重新发版、不用用户更新 APK**。
`PanRename.loadRules()` 读不到表时（老库/库未就位）自动退回内置的 6 条，功能不会整体失效。

### 3.4 目标名怎么算

优先级：**`filename_regex` 与 `ext` 规则按 `priority` 升序取最小者**（表里 `ORDER BY priority`）。
名字处理四件事，缺一不可：

1. **大小写无关匹配**：`.XS` 也要命中（移动端从各种来源复制来的文件名大小写很乱）。
2. **只换最后一段后缀**：`a.b.c.mp41 → a.b.c.mp4`，不能把 `a.b` 当成后缀。
3. **非法字符替换**：百度禁 `< > : " / \ | ? *`。注意**中文全角 `：` 是合法的**，
   别顺手把 `志愿军：雄兵出击` 改成 `志愿军_雄兵出击`（这个坑很容易踩）。
   另外**尾部点和空格**百度也不收，要去掉。
4. **UTF-8 字节长度上限 250**：中文一个字 3 字节，所以大约 80 个汉字就到顶。
   超长时**保留后缀、截断主名**（`sanitize()`）。

**撞名**：先在本目录的现有文件名集合里预检，撞了就 `xxx (2).mp4`，最多试到 `(99)`。
预检的意义是**把可预期冲突挡在客户端**，避免依赖对服务端 errno 的猜测。

### 3.5 调用链与台账

```
DetailActivity（点播放）
   └─ BaiduPan.transfer(shareUrl, pwd, targetDir)
        → TransferResult{ ok, toPaths[], shareid, uk }
   └─ PanRename.renameAfterTransfer(ctx, toPaths)      ★ 新增
        → Result{ scanned, planned, renamed, failed, items[] }
   └─ 把 items[] 写进本地台账（appstate.db 或 SharedPreferences）
   └─ 重新读取剧集列表（因为路径变了）
   └─ 起播
```

**台账是必须的**，因为改名会让 `pan_video.path` 与网盘实际路径**对不上**：

```
SuperMOV.db 里:  /转载/千年魔戒/千年魔界...edit110.mkv     ← 库里的记录，改不了
网盘里实际:      /超级影库/千年魔戒/千年魔界...edit110.mp4  ← 改名后
```

`PanRename.resolvePath(ctx, path)` 就是查这条台账。
设计上，台账**只增不改**（`oldPath → newPath` 单向映射），改名失败就查不到，自然退回原值。

**状态放哪里**：`SuperMOV.db` 是随 APK 分发、可被在线覆盖的**只读库**，
所以台账绝不能写回去。现在临时用 `SharedPreferences("pan_rename_ledger")`，
将来本地状态（进度/收藏/下载记录/台账）建议统一迁到 `appstate.db`：

```sql
-- appstate.db（App 私有，永不随 APK 更新覆盖）
CREATE TABLE local_state  (uid TEXT, kind TEXT, k TEXT, v TEXT,
                           updated_at TEXT, PRIMARY KEY (uid, kind, k));
```

### 3.6 落地前怎么验证（不碰 Android 也能验）

`tools/rename_plan.py` 是规则引擎的 Python 参考实现，与 Java 侧同一套语义：

```bash
# 规则单元自检（12 条边界样例：大写后缀、已是目标、多段点、非法字符、超长…）
python tools/rename_plan.py --selftest
# → 失败 0 / 12

# 真实文件名上模拟伪装后缀，验证命中链路
python tools/rename_plan.py --db build/SuperMOV.db --all --simulate-ext .xs --limit 5
# → 需要改名的文件 2525 个（把每个文件都当成 .xs）

# 不做模拟：当前库其实一条都不需要改
python tools/rename_plan.py --db build/SuperMOV.db --all
# → 需要改名的文件 0 个   ← 符合预期
```

改规则时，**Python 和 Java 两边一起改**，然后重跑 `--selftest`。

### 3.7 兜底：改名失败也要能播

改名不是播放的必要条件。`LocalProxy` 已有按内容嗅探的能力，建议**同时**做一层兜底：

> 取流 URL 的后缀按「目标名」给（`BaiduPan.streamingUrl()` 用内存里的映射名，
> 而不是网盘真实名）。这样即使改名失败，播放链路拿到的仍是 `.mp4` 结尾的 URL。

这条在 §3.1 说的"没有真实 `.xs` 样本"前提下**尤其值得做**——它让功能在改名不可用时不至于全废。

---

## 4. 已交付的东西（阶段 1，都已跑通）

### 4.1 数据产物

| 文件 | 说明 |
|---|---|
| `build/SuperMOV.db` | schema v2 数据库，326 部 / 2525 视频，2.41 MB |
| `DJYDXS-TV/app/src/main/assets/SuperMOV.db` | 同上，**已放进 assets**（第一版内嵌） |
| `DJYDXS-TV/app/src/main/assets/SuperMOV.version` | 5 行构建信息，App 读它判断内嵌库版本 |
| `SuperMOV.json` | 更新清单（manifest_version 2） |
| `preview/index.html` | 改造效果预览（用真实数据渲染列表/剧集/更名） |

### 4.2 工具

| 文件 | 说明 |
|---|---|
| `tools/db_upgrade_v2.py` | schema v1→v2 迁移 + 元数据回填。输入只读、幂等、无损 |
| `tools/build_manifest.py` | 生成 `SuperMOV.json` + `SuperMOV.version` |
| `tools/rename_plan.py` | 更名规则引擎参考实现 + 自检工具 |
| `tools/make_preview.py` | 生成效果预览 HTML |

### 4.3 Android 新增类（`app/src/main/java/com/supermov/tv/`）

| 类 | 职责 |
|---|---|
| `MovieDb.java` | 内嵌库就位（assets→files）、只读打开、三层版本号取最大、schema 可读性判定 |
| `DbUpdater.java` | 拉 `SuperMOV.json` → 比版本/比结构 → 下载 → **size+sha256+integrity_check+库内版本 四重校验** → 原子替换 |
| `PanRename.java` | 更名规则加载、目标名计算、批量改名、回读校验、台账 |

**这三个类只新增文件，不修改任何现有类**，所以对当前能跑的构建是零风险。

### 4.4 阶段 2 改动的现有文件（**已完成**，详见 §8）

| 文件 | 实际改动 |
|---|---|
| `Site.java` | **已整文件删除**（1007 行论坛解析） |
| `WebLoginActivity.java` | **已整文件删除**（孤儿页面，设置里早已无入口） |
| `MovieStore.java` | **新增**：App 唯一取数入口，只读 `v_movie_app` / `v_episode`，承接从 `Site` 移植过来的文本工具 |
| `MainActivity.java` | 列表改读 `v_movie_app`；启动调 `MovieStore.init()`；**删掉整个「百度链接探测」段**（旧版要逐帖请求论坛，现在 SQL 一步到位） |
| `DetailActivity.java` | 详情/剧集改读数据库；资料区与简介拆成两个字段直接显示；**加剧集数与总容量提示（零网络）** |
| `SettingsActivity.java` | 加「影片数据库」入口（接 `DbUpdater`）；诊断段改为报数据库状态；清理论坛段 |
| `Http.java` / `ImageLoader.java` | 去掉 `4kzimu.top` 的 Referer |
| `CookieStore.java` | 去掉 `4kzimu.top` 的桶 |
| `app/build.gradle` | `versionCode 28`；新增 `androidResources { noCompress += 'db' }` |
| `.github/workflows/build.yml` | 推送分支补上 `DJYDXS3Nexio`（原来只监听 `DJYDXS2Nexio`，推上去不会构建） |

---

## 5. 发布与更新

### 5.1 上传顺序是硬要求

```
1. 上传 SuperMOV.db         （覆盖线上旧包）
2. 下载回来核对 sha256 与本地一致
3. 最后才上传 SuperMOV.json
```

反了会出现「清单说有新版 → App 去下载 → 校验失败 → 反复重试」的典型故障。
清单里的 `sha256` 必须指向**已经在线上的那个包**。

另外 `800915.xyz/db/` 尚未上线，所以 `DbUpdater` 把 404 / 超时 / 解析失败
一律按「没有更新」处理，只写日志。**绝不能因为更新服务不可用而挡住 App 启动。**

### 5.2 build.gradle 必须加一句

```gradle
android {
    aaptOptions {
        // ★ 必须：.db 被压缩后 SQLite 无法 mmap，且 assets 里的库
        //   需要能按"原样"读出。默认压缩会让某些设备上解包出的库损坏。
        noCompress 'db'
    }
}
```

（AGP 8+ 用 `androidResources { noCompress += 'db' }`。）
这一条不做的话，会出现「开发机好好的、个别盒子启动就崩」这种极难查的问题。
**已确认当前 `app/build.gradle` 里没有任何 `noCompress` / `aaptOptions` 配置，必须补上。**
（当前 `compileSdk 36 / minSdk 23 / versionCode 27`。）

### 5.2b 打包前必须把库切回 DELETE journal（实测发现的坑）

源库是 **WAL 模式**（`PRAGMA journal_mode = wal`）。这带来两个真实风险：

1. **只复制 `.db` 可能丢数据。** WAL 模式下「库 = `.db` + `-wal`」，如果 `-wal` 里还有
   没 checkpoint 的页，光拿 `.db` 就是一个**残缺的库**。清单里的 sha256 算的是这个残缺文件，
   线上传的也是它，用户拿到的是旧数据 —— 而且**没有任何报错**，最难查的那类 bug。
2. **部分设备上打开会失败。** WAL 需要同目录可写以创建 `-shm/-wal`。将来若把库放到不可写
   的位置（或做只读挂载），会直接 `unable to open database file`。

所以迁移脚本现在在收尾时强制：

```python
cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
cur.execute("VACUUM")
cur.execute("PRAGMA journal_mode=DELETE")   # ← 关键：退回单文件自洽
```

让打包库变成**一个自洽的单文件**。同时 `build_manifest.py` 增加了**打包前置检查**：
库若不是 DELETE 模式、或目录里存在 `-wal/-shm/-journal` 侧写文件，会直接打印
`✗ 打包前置检查未通过`。

> 另外提醒：`assets/` 目录里也**绝对不能残留** `SuperMOV.db-wal` / `SuperMOV.db-shm`。
> 它们会被一起打进 APK，装到设备上后与新库不匹配，可能引发"库打不开"。
> 本次就实际出现过（因为先前的只读查询在 assets 目录旁生成了侧写文件），已清理；
> 建议把 `*.db-wal`、`*.db-shm` 加进 `.gitignore`。

### 5.3 同步侧建议补的两件事

1. `supermov_sync.py` 每次跑完**顺便生成 `SuperMOV.json` 与 `SuperMOV.version`**
   （复用 `tools/build_manifest.py`），避免人工漏步。
2. 把 `tools/db_upgrade_v2.py` 的回填逻辑**合并进 `supermov_sync.py`**，
   让库一出生就是 v2 结构（现在这个脚本是用来"补历史库"的迁移工具）。

---

## 6. 风险与待实测

| # | 风险 | 影响 | 处置 |
|---|---|---|---|
| 1 | **`filemanager?opera=rename` 未在真机验证** | 改名可能整体不生效 | 文档已标注；需要一次真机实测。**这是阶段 2 的第一件事** |
| 2 | `async=2` 是否返回任务化响应 | 只看 errno 会误判成功 | 已用「回读校验」兜住，不依赖响应语义 |
| 3 | 撞名 errno 究竟是 12 / -8 / -7 | 退化为撞名重试可能不触发 | 已做客户端预检（拿实际列表比对），把服务端撞名降到极低概率 |
| 4 | 当前库无 `.xs` 样本 | 机制无法端到端验证 | `--simulate-ext` 覆盖了规则链路；端到端需你给我那个分享链接 |
| 5 | `content` 600 字截断 | 简介 29%、主创覆盖不全 | §1.6 方案 B（sync 侧解析后入库） |
| 6 | 论坛附件域名（aliyun oss）变更 | 全库海报裂 | §1.7 第 4 条：镜像到自有 OSS |
| 7 | 线上 DB 与清单不同步 | App 反复下载校验失败 | §5.1 上传顺序；`DbUpdater` 已校验「库内版本 == 清单版本」 |
| 8 | 用户先在线更新、后装旧 APK | **数据降级**，影片变少 | `MovieDb.ensureReady()` 三层版本取最大，只在严格更大时替换 |
| 9 | `assets/` 库被 zip 压缩 | 个别设备解包出的库损坏 | §5.2 `noCompress 'db'` |
| 10 | App 把用户状态写回 SuperMOV.db | 库一更新用户数据全没 | 纪律：只读库；状态放 `appstate.db` |
| 11 | 改目录名 | 整部剧路径记录全废 | `PanRename` 硬性跳过 `isdir==1` |
| 12 | 全角 `：` 被当非法字符 | 中文片名被误改 | `ILLEGAL` 只匹配半角；自检样例已覆盖 |
| 13 | 用片名派生 `uid` | 用户进度错位 | 纪律：`uid` 永久为 `t{tid}` |
| 14 | `schema_version` 忘了 bump | 老 App 硬读新结构 → 读出错数据 | 变更分级表（§1.3）+ `MovieDb.isSchemaReadable()` 拦截 |
| 15 | TMDB 图片/数据的使用授权 | 合规 | 只存 ID + 跳转；展示图片前确认授权范围 |
| 16 | **源库是 WAL 模式** | 只复制 `.db` 丢数据且不报错；个别设备打不开 | §5.2b：强制 `journal_mode=DELETE` + 清单生成的打包前置检查 |
| 17 | **连接未关闭就取 sha256** | 清单写进旧文件的哈希 → App 反复下载校验失败 | 迁移脚本已改为先 `close()` 再量体积/算哈希（实测踩到过：报出的哈希是**源文件**的） |
| 18 | `assets/` 残留 `-wal/-shm` | 被打进 APK，与新库不匹配 | 已清理；建议加 `.gitignore` |

---

## 7. 阶段划分

| 阶段 | 内容 | 状态 |
|---|---|---|
| **1** | schema v2 迁移、清单体系、更新器、更名模块、效果预览 —— **纯新增，零风险** | ✅ 已完成 |
| **2** | 接线：删 `Site.java` / `WebLoginActivity`，`MainActivity`/`DetailActivity` 改读视图，清理论坛残留 | ✅ 已完成（**v1.28**，见 §8） |
| **2c** | 分类栏定稿：白名单 4 栏目（按 fid）+ 显示名覆盖 + 顺序写死，下线 `转载资源区`/`1080P.Remux` | ✅ 已完成（**v1.29**，见 §9） |
| **3** | 置顶机制 `pin_top` + 测试库条目 + 转存建片名文件夹 + 空间不足确定性判定 | ✅ 已完成（**v1.30**，见《[三期](DJYDXS3Nexio-三期-转存改造与置顶测试库.md)》） |
| **3b** | 转存成功后自动调 `PanRename` 改名 | ⏳ 未接线。规则表已在库里，等真实 `.xs` 分享样本验证 `filemanager?opera=rename` 行为后再接 |
| **4** | sync 侧改造：合并回填逻辑、解除 `content` 截断、主创/简介补全 | 待定 |
| **5** | 元数据增强：TMDB 自动匹配、海报镜像、`movie_asset` | 待定 |

**下一步（阶段 3b / 4）**：

1. 拿一个真机 + 一个真实 `.xs` 分享跑一次 `PanRename`，确认 `filemanager?opera=rename` 的 errno 语义，结掉 §6 风险 1~3；
2. sync 侧把 `content` 从 600 字截断改成「解析后入库」，简介覆盖率（当前 96/326 = 29%）才能真正上去；
3. `800915.xyz/db/` 上线时，按 §5.1 的顺序上传（先 `.db`、校验线上 sha256、最后才传 `.json`）。

---

## 8. 阶段 2 实施记录（v1.28）

### 8.1 本次实测发现的四件事（都会影响设计，不是推演）

**① 论坛版块名早就改过了 —— 分类栏必须数据驱动**

App 里原本硬编码的分类名，和数据库里的实际版块名对不上：

| fid | App 里写死的名字 | 数据库里的真实名字 | 影片数 |
|---|---|---|---|
| 37 | 1080P最新剧集 | 1080P最新剧集 | 98 |
| 112 | 4K全景声 | **4KSDR.Remux** | 89 |
| 58 | 1080P蓝光 | **1080P高码版** | 76 |
| 2 | 1080P杜比5.1 | **最新1080P电影** | 57 |
| 86 | （未显示） | 转载资源区 | 4 |
| 119 | （未显示） | 1080P.Remux | 2 |

如果继续硬编码，用户会在界面上看到**四个已经不存在的版块名**，而 6 部片子永远看不到。
所以 `MovieStore.categories()` 改成从库里现取（`GROUP BY fid, forum_name`），
数据侧改版块名，App 重启即可生效。

**② `v_movie_app` 原本没有 `fid` / `forum_name` 列** —— 已补进视图 DDL。
视图加列属于「非破坏性变更」，按 §1.3 的契约**不需要 bump `schema_version`**。

**③ 326 部影片的 `pan_status` 全是 `ok`、`pan_url` 全部非空** —— 所以列表页**不需要**过滤。
旧版要逐个帖子请求论坛确认有没有百度链接（`filterBaiduOnly` + 专用线程池），
现在一句 SQL 就够了，那段两段式「先出画、后探测」的逻辑整体删掉。

**④ 《海贼王》一部就有 1175 个文件**——旧版 `BaiduPan.MAX_FILES = 300` 会把它截断。
现在详情页先用库里的 `v_episode` 报出真实的「集数 + 总容量」，不经过任何网络请求。

### 8.2 新增/删除的代码

| | 文件 | 行数 | 说明 |
|---|---|---|---|
| 新增 | `MovieStore.java` | 811 | App 唯一取数入口。只读两个视图；顺带把 `Site` 里调试过很多轮的文本工具（`splitAtUpcoming` / `tidyLines` / `cutInfoMiddle` / `dropNoiseLines` / `parseTags`…）原样移植过来，**没有重写** |
| 新增 | `MovieDb.java` | 356 | 内嵌库就位 + 三层版本号取最大 + schema 可读性判定 |
| 新增 | `DbUpdater.java` | 369 | 在线更新，四重校验 |
| 新增 | `PanRename.java` | 564 | 转存后更名（未接线） |
| 删除 | `Site.java` | -1007 | 论坛 Discuz 解析全量 |
| 删除 | `WebLoginActivity.java` | -52 | 孤儿页面 |

### 8.3 没有 javac 环境，怎么保证推上去能编过

沙箱里只有 JRE 8、没有 `javac`，云端一次往返 3~5 分钟。所以推之前跑了三道：

| 检查 | 内容 | 结果 |
|---|---|---|
| `precheck.py` | 13 项结构化检查（括号平衡、资源存在性、漏 import、组件注册、**lambda 捕获**、**API 等级越界**…） | `NO ERRORS` |
| 跨类符号校验 | 「删了 A 文件，B 文件还在引用」这类只有 javac 能发现的问题，用纯文本解析自家类的成员表对照 | 0 处悬空 |
| **SQL 逐条预演** | `MovieStore` 里每条 SQL 在真实库上跑一遍（分类/过滤/列表/搜索/详情/剧集/外部ID，共 33 条） | 33 通过 / 0 失败 |

第三项最值钱：SQL 写错列名属于「编译通过、运行才崩」，云端根本抓不到。

### 8.4 刻意没做的事（避免一次改太多）

- **转存后自动更名**没有接进 `DetailActivity` 的转存链路（`PanRename` 已就绪但无调用点）。
  原因：当前库里一个需要改名的文件都没有，而 `filemanager?opera=rename` 的 errno 语义
  还没在真机上验证过。先不接，等样本。
- **`Site` 里那 9 个文本工具方法**只移植了 `splitAtUpcoming` / `tidyLines` / `cutInfoMiddle` /
  `dropNoiseLines` / `stripTags` / `unescape` / `extractDate`，其余（论坛 HTML 专用）随文件一起删。
- **启动时的静默更新检查**没加（远端清单地址还没上线，每次启动都会发一个 404 请求）。
  当前只在「设置 → 影片数据库」手动触发。上线后可在 `GateActivity` 加一行静默调用。

---

## 9. 分类栏定稿（v1.29）

### 9.1 决策：从「全量数据驱动」改成「白名单 + 显示名覆盖 + 固定排序」

§8.1 ① 的结论是「分类栏必须数据驱动，否则用户看到的是已经不存在的旧版块名」。
方向没错，但**只做对了一半**——v1.28 的实测暴露了这样做的两个副作用：

1. **数据侧的版块名是给站长看的，不是给用户看的。**
   `4KSDR.Remux`、`1080P高码版`、`最新1080P电影` 是资源站的归档口径，
   不是产品语言。原样透给用户，界面像是把数据库表直接糊在屏幕上。
2. **数据侧一改，界面就跟着漂。**
   版块顺序按影片数倒序（`ORDER BY n DESC`），意味着**数据一波动，栏目顺序就变**；
   数据侧再多一个「转载资源区」这类边角版块，界面上就凭空多一个栏目。

所以 v1.29 反过来定：**只保留四个版块、用产品化的名字、顺序写死**。
数据侧再冒出别的版块，不会漏到界面上。

### 9.2 但匹配必须用 `fid`，不能用版块名

这是这次最关键的一条。版块名**已经改过至少一轮**了
（App 里曾写死 `4K全景声`，库里实际是 `4KSDR.Remux`）。
拿名字当 key，等于把「界面稳定性」押在别人随时会改的字符串上；`fid` 是数据侧主键，稳。

| 位次 | 显示名 | fid | 数据侧实际名 | 部数 |
|---|---|---|---|---|
| 1 | `4K全景声` | 112 | `4KSDR.Remux` | 89 |
| 2 | `最新剧集•美剧` | 37 | `1080P最新剧集` | 98 |
| 3 | `蓝光影片` | 58 | `1080P高码版` | 76 |
| 4 | `杜比5.1影片` | 2 | `最新1080P电影` | 57 |

> 需求里给的是 `4K.SDR.REMUX`、`高码1080P`、`最新1080P` 这几个写法，
> 与实际库里的 `4KSDR.Remux`、`1080P高码版`、`最新1080P电影` 对不上。
> 这不影响落地 —— **正因为按 fid 匹配，名字怎么写都不影响**，这本身就是选 fid 的价值。

### 9.3 被下线的两个版块，以及它们的代价

| fid | 数据侧名 | 部数 | 处置 |
|---|---|---|---|
| 86 | `转载资源区` | 4 | 界面不显示 |
| 119 | `1080P.Remux` | 2 | 界面不显示 |

**这 6 部片并没有从 App 里消失**：`search()` 不按 `fid` 过滤，
它搜的是整个 `v_movie_app`（`name` / `title_en` / `title_alt`），所以逐部搜得到：

```
千年魔界 / 志愿军：雄兵出击 / 泰勒·斯威夫特：时代巡回演唱会 / 极度深寒 / 完美风暴 / 夜王
```

代价是它们**只能搜、不能翻**（不再有栏目入口）。如果哪天想让他们回来，
把 fid 加回白名单数组即可，不用动数据。

### 9.4 实现要点

```java
// MovieStore.java —— 数组顺序即界面顺序
private static final int[] CAT_FIDS = {112, 37, 58, 2};
private static final String[] CAT_NAMES = {"4K全景声", "最新剧集•美剧", "蓝光影片", "杜比5.1影片"};
```

- 仍然读一次库拿各版块的影片数，但**只用于 logcat 诊断**
  （「分类栏 [4K全景声] fid=112 共 89 部」—— 哪个栏目为什么是空的，一看便知）。
- **即便某个 fid 当前 0 部也照样出栏**，保证界面上这四个位置永远固定，
  不会因为数据波动导致栏目错位。
- 删掉了原「库为空就显示『全部』」的兜底分支 —— 白名单恒非空，那个兜底已无意义。
- `MainActivity` 一行没改：分类栏本来就是在 `MovieStore.categories()` 上迭代渲染的，
  改数据源即可，UI 自动跟随。`⚙ 设置` / `⬇ 下载` 是动作按钮、不是内容栏目，保持不动。

### 9.5 验证与产物

| 检查 | 结果 |
|---|---|
| 跨类符号校验 | 0 处悬空 |
| SQL 逐条预演（改成 4 个白名单 fid） | 25 通过 / 0 失败 |
| 完整性断言（新增 6 条分类栏断言） | 46 通过 / 0 失败 |
| APK 产物取证（新增：四个显示名进 dex + versionName） | 23 通过 / 0 失败 |

数据库本身**未改动**（`db_version` 仍为 `2026091901`），所以不需要重出库、不用动清单。
版本：`versionCode 29` / `versionName "1.29"`，构建 run `35411024696`。


---

## 10. 三期（v1.30）—— 见独立文档

三期内容与二期不同源（不是继续改数据库结构，而是**加了一列 + 改了转存行为**），
单独成文：《DJYDXS3Nexio-三期-转存改造与置顶测试库.md》。

一句话索引：

| 需求 | 一句话结论 | 落在哪 |
|---|---|---|
| 测试库入库并置顶 | 加 `pin_top` 列，**不用伪造 `release_date`**（假日期会显示在详情页） | `movie.pin_top` + `v_movie_app` + `MovieStore.ORDER_BY_PIN` |
| 转存建片名文件夹 | 判据是**逐条看根条目的 `isdir`**，不是猜分享类型（混合分享必然漏） | `BaiduPan.transfer` 4 参重载 |
| 空间满了要提示 | **不能靠 errno 判**（各路说法互相矛盾）→ 用 `/api/quota` 真实容量做确定性判定 | `BaiduPan.quota()` + `MSG_NO_SPACE` |

数据库 `db_version` 升到 `2026091902`（`sha256=3dd45ca6…b814a`），
`4KSDR.Remux`（=「4K全景声」）由 89 部变为 **90 部**。

**从二期继承、并在三期被验证有效的两条约定**：

1. 「App 只读视图」——三期加列同样要在视图里暴露（`COALESCE(m.pin_top,0) AS pin_top`），
   而不是让 App 去 `LEFT JOIN movie`；
2. 「两条升级路径 DDL 必须对齐」——三期正是靠这条抓出了
   `db_manual_entries.py` 缺 `idx_movie_pin` 的不一致。

---

## 11. 元数据从哪来（跨期专题）—— 见独立文档

一~三期都在改**结构**和**行为**，但一直没写清「库里的结构化字段到底是怎么算出来的」。
这份专题补上：《DJYDXS3Nexio-元数据链路与库结构.md》。

它回答的问题与本文档不同源：

| 本文档（一~三期） | 元数据专题文档 |
|---|---|
| 表怎么设计、索引怎么建、App 怎么读 | `content` 里那段 `◎译 名…◎简 介…` 怎么变成 13 个字段 |
| 迁移怎么做才无损、幂等 | 标签映射、中英名拆分、ID 正则、主创切分的**逐条规则** |
| 升级走哪条路 | 三级兜底链（`◎` 段 → 模板 regex → 纯 title） |

专题里最值得单独一提的是一条**实测发现的坏数据**：

> `◎主 演` 段是**纯空格分隔**的，而 `split_names()` 只认 `/ 、` 等符号
> → **整段演员表被当成 1 个名字**（`actor` 恰好 1.00 条/部，平均长度 199 字）。
> 修法已验证：加一刀「拉丁字母→中日韩字符」边界，`actor` 313 → **3,202** 条，
> 对导演零回归。**App 目前还没读 `movie_credit`，所以是「埋着的雷」，接线前必须修。**

体检工具（本次一并沉淀，可当出包门禁）：

```bash
python tools/db_meta_report.py --db build/SuperMOV.db --src "C:/py/DJYDXSDB/DJYDXS..db"
# 退出码 1 = 发现坏数据（角色「条/部」低于合理阈值 / 名字长到不像名字）
```
