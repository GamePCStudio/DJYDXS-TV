# DJYDXS3Nexio · 元数据链路与库结构

> 回答三个问题：**这些资料（干净中文名 / 英文名 / 年份 / 豆瓣 ID / IMDb ID / 类型 / 产地 / 语言 / 上映日期 / 主创 / 简介）是怎么从原始数据库走到新数据库的？规则是什么？新库长什么样？**
>
> 所有数字都是从真实库跑出来的，不是推演。取数脚本已合并为**一个可直接复用的体检工具**：
>
> ```bash
> python tools/db_meta_report.py --db build/SuperMOV.db --src "C:\py\DJYDXSDB\DJYDXS..db" --simulate-actor-split
> ```
>
> 不加参数则只查新库；`--src` 加看源库标签出现率（覆盖率天花板）；`--simulate-actor-split` 只模拟不写库。
> **退出码 1 = 发现坏数据**，可直接串进出包流水线当门禁。
>
> 前序：《数据库重构方案》（一期）、《数据库重构方案-二期》（v1.28 / v1.29）、《三期-转存改造与置顶测试库》（v1.30）

---

## 0. 结论先说

| 问题 | 结论 |
|---|---|
| 元数据从哪来 | **全部来自论坛帖子的正文 `thread.content`**，没有任何外部 API 参与。所谓"新库里的结构化字段"其实是**把 `content` 里那段 `◎译 名 … ◎简 介 …` 的文本块解析出来的** |
| 为什么会有这段文本 | Discuz 发帖人套用了固定的「影片信息模板」，`◎` 是字段分隔符。**不是论坛提供的结构化数据**，是帖子正文的一部分 |
| 转移发生在哪一步 | **不在同步脚本里，在 `db_upgrade_v2.py` 这一步**。`supermov_sync.py` 只做「筛选 + 探测 + 原样搬运」，把 `content` 整段抄过来；结构化解析是第二段独立做的事 |
| 为什么分两段 | 同步要跑 10~14 小时（15k 个百度链接逐个探测），解析只要几秒。**把"慢的网络活"和"快的文本活"解耦**，改解析规则可以随时重跑，不必重新探测 |
| 最大的规则特征 | **不认字段名，认内容**。发帖人会把 `◎译 名` 和 `◎片 名` 写反，所以两个字段合并成候选池，再按「有没有中日韩字符」分类 |
| 最大的一次性损耗 | `content` 被论坛硬截断在 **600 字符**（17,395 帖里 14,268 帖正好 600）。越靠后的字段越容易丢 —— 简介、主演、编剧首当其冲 |
| **一条需要立刻修的规则** | `◎主 演` 段是**纯空格分隔**的，而 `split_names()` 只认 `/ 、` 等符号 → **整段演员表被当成 1 个名字**（`actor` 恰好 1.00 条/部）。修法已验证，见 §2.8b / §8.1 |

### 三段链路

```
① 原始库（论坛爬虫镜像）
   C:\py\DJYDXSDB\DJYDXS..db          53.70 MB
   thread 17,395 / link 32,135（其中 baidu 15,304）/ forum 14
        │
        │  supermov_sync.py   筛选 + 逐条真探 + 原样搬运（元数据不解析）
        ▼
② 同步库（v1 结构，论坛字段原样落地）
   C:\py\SuperMOVDB\SuperMOV.db
   movie 27 列 → 后来扩到 31 列（新增 imdb_id/douban_id/tmdb_id/tmdb_type）
        │
        │  db_upgrade_v2.py   schema v1→v2 + 元数据解析回填（本文重点）
        ▼
③ 出包库（v2 结构，App 直接读）
   build\SuperMOV.db                  2.41 MB
   movie 42 列 / movie_ext_id 625 / movie_credit 1,493 / v_movie_app / v_episode
        │
        │  db_manual_entries.py（手工条目 + 置顶）→ build_manifest.py（清单）
        ▼
④ APK 内嵌
   DJYDXS-TV/app/src/main/assets/SuperMOV.db
```

**注意 ② 和 ③ 是两个不同的库，角色不同**：② 是「发布侧的中间产物」，可以随时重跑同步覆盖；③ 是「已解析、已加契约、可直接发出去的成品」。

---

## 1. 第一段：原始库 → 同步库

### 1.1 原始库是什么

`C:\py\DJYDXSDB\DJYDXS..db` —— `https://4kzimu.top`（DJYDXS 论坛，Discuz X5.0）的本地镜像，由 `DJYDXS.py` + `db_sync.py` 抓取。

| 表 | 行数 | 用途 |
|---|---|---|
| `thread` | **17,395** | 影片主表（一条 = 一个帖子） |
| `link` | **32,135** | 网盘链接（`type` 区分 baidu/quark/115/ed2k/magnet/thunder…） |
| `forum` | 14 | 栏目字典 |
| `thread_class` | 20,084 | 影片-标签（分类归一化） |
| `crawl_run` / `change_log` / `forum_filter` / `link_type` | — | 日志与字典 |

`thread` 的 20 列里，**用于元数据的只有 5 列**：

| 列 | 在元数据链路里的作用 |
|---|---|
| `title` | 论坛原始标题（带技术垃圾）—— 兜底用 |
| **`content`** | **★ 元数据的唯一来源**（`◎译 名 … ◎简 介 …` 那段） |
| `pic` | 封面图 URL → 新库 `pic`，最终通过视图变成 `poster` |
| `runtime` | 论坛侧的片长原文（如 `126分钟`）→ 只作参考，新库用的是从 content 解析的 `runtime_min` |
| `classification` / `tags` / `is_restricted` / `is_erotic` | 筛选与展示 |

### 1.2 同步脚本做了哪些「规则」

`supermov_sync.py` 做的事**都是筛选与探测，没有元数据解析**：

```
① 取「含 type='baidu' 链接」的帖子           →  14,852 部候选
② 逐条真实 HTTP 探测每个百度分享            →  15,304 条链接
③ 必须真列到 .mkv / .mp4 / .xs 才判有效     →  规则同 v1，不变
④ 至少一条有效链接的才写 movie 表
⑤ 原样搬运：title / content / pic / runtime / classification / tags
   + 从 content 正则初提 imdb_id / douban_id（零外部请求）
```

- **只认百度盘**：115 / ed2k / magnet / thunder 等类型完全不进目标库。
- **`content` 原样抄**：600 字的截断是**源库就有的**，同步这一步不加工、不扩写。
- **ID 初提是个"顺手抄"**：`IMDB_RE = imdb\.com/title/(tt\d+)`、`DOUBAN_RE = douban\.com/subject/(\d+)`。注意它**只提 ID，不解析任何其它字段**。
- **限制级在探测之前就拦掉**：`is_restricted=1 OR is_erotic=1` 直接进 `reject(reason='restricted')`，**不做网络探测**（省请求）。本次全量排除 197 部。

> ⚠️ 这里有一个**需要留意的重复语义**：同步侧新加了 `movie.imdb_id / douban_id / tmdb_id / tmdb_type` 四列，而 v2 出包库里同一份信息存在 **`movie_ext_id` 表**。两套并存，见 §8.2。

---

## 2. 第二段：同步库 → 出包库（**核心**）

`tools/db_upgrade_v2.py`。它做三件事：**加列** → **建表建视图** → **从 `content` 解析回填**。

### 2.1 先看全貌：一次解析要产出 13 个字段 + 2 张关联表

```
movie.content（一整行、无换行的文本块）
        │
        ├── parse_content()      按 ◎ 切段 + 标签归一  →  segs{} 字典
        │
        ├── build_names()        ──► title_cn / title_en / title_alt
        ├── split_names()        ──► genres[]
        ├── to_int()             ──► year / runtime_min
        ├── DATE_RE              ──► release_date
        ├── to_float()           ──► rating_douban / rating_imdb
        ├── segs 直取            ──► region / language / synopsis
        ├── parse_ext_ids()      ──► movie_ext_id 表（douban / imdb）
        └── split_names + split_cn_en ──► movie_credit 表（director / writer / actor）
```

### 2.2 第一步：`parse_content` —— 按 `◎` 切段

**为什么不能用换行当分隔符**：`content` 被压缩成了**一整行**（无 `\n`）。所以只能以 `◎` 为界：

```python
def parse_content(content):
    segs = {}
    for chunk in content.split("◎")[1:]:        # 第一个 ◎ 之前是发帖前缀，丢掉
        chunk = chunk.strip()
        for lab in LABELS_SORTED:               # 长标签优先（"译 名" 比 "译" 长）
            if chunk.startswith(lab):
                key = CANON.get(lab)
                val = clean_ws(chunk[len(lab):].strip(" \u3000:："))
                if key and val and key not in segs:   # ★ 首次出现优先，不覆盖
                    segs[key] = val
                break
```

三个不显眼但必要的细节：

1. **长标签优先**：`LABELS_SORTED = sorted(LABELS, key=len, reverse=True)`。否则 `译名` 会先被 `译` 匹配掉，值里带出半个标签。
2. **全角/半角都认**：标签表里同时列了 `"译 名"`（带内部空格，论坛模板的写法）和 `"译名"`。论坛模板用 `◎译 名`，但有人手打 `◎译名`。
3. **首次出现优先**：`if key not in segs`。有的帖子把同一段落写两遍（编辑残留），取第一遍。

**标签归一表（`CANON`，完整）**：

| 出现的标签（含空格变体） | 规范键 | 落到哪个字段 |
|---|---|---|
| `译 名` / 别名 / `又 名` | `alias` | → 候选池 → `title_alt` |
| `片 名` | `original` | → 候选池 → `title_en` |
| `年 代` | `year` | `movie.year` |
| `产 地` | `region` | `movie.region` |
| `类 别` | `genres` | `movie.genres`（JSON 数组） |
| `语 言` | `language` | `movie.language` |
| `片 长` | `runtime` | `movie.runtime_min`（转成整数分钟） |
| `上映日期` | `release_date` | `movie.release_date` |
| `IMDb评分` | `rating_imdb` | `movie.rating_imdb` |
| `IMDb链接` / `IMDb` | `url_imdb` | → `movie_ext_id`（imdb） |
| `豆瓣评分` | `rating_douban` | `movie.rating_douban` |
| `豆瓣链接` / `豆瓣` | `url_douban` | → `movie_ext_id`（douban） |
| `简 介` | `synopsis` | `movie.synopsis` |
| `主 演` | `actors` | → `movie_credit`（actor） |
| `导 演` | `directors` | → `movie_credit`（director） |
| `编 剧` | `writers` | → `movie_credit`（writer） |
| `标 签` / `官方网站` | `tags` / `site` | 未使用（保留键位） |

另外还有一个前置清洗 `clean_ws()`：**剥掉零宽字符与双向控制符**（`\u200b-\u200f`、`\u202a-\u202e`、`\ufeff`、`\u00ad` 等）。这些是从网页复制时混进来的隐形字符，会导致「搜不到」「排序错乱」而且肉眼看不出来。

### 2.3 干净中文名 / 英文名 —— 最核心的一条规则

**`build_names()` 的原则：不认字段名，认内容。**

实测发现发帖人会把两个字段**写反**：

```
实测样例（id=18）：
  ◎译 名 Pegasus 3          ← 这里本该是中文名，写成了英文名
  ◎片 名 飞驰人生3           ← 这里本该是原名（英文），写成了中文名
```

如果按字段名取（"`alias` 就是中文名"），这部片的 `title_cn` 会变成 `Pegasus 3`，`title_en` 会变成 `飞驰人生3` —— 界面上中英两栏直接对调。

所以规则改成：**两段合并成候选池，再按字符类型分类**：

```python
pool = split_names(segs["alias"]) + split_names(segs["original"])   # 去重保序
cn = [n for n in pool if has_cjk(n)]                      # 含中日韩字符 → 中文名
en = [n for n in pool if not has_cjk(n) and ascii_ratio(n) > 0.8]   # 纯 ASCII → 英文名

title_cn  = cn[0]  or  兜底
title_en  = en[0]  or  兜底
title_alt = pool 里除了上述两个之外的全部（JSON 数组）
```

配套的四条细则：

| 规则 | 说明 |
|---|---|
| **`split_names`** 按 `/ ｜ \| 、 , ，` 切分 | `千年魔界 / Bewitched Area Of Thousand Years` → 两个名字 |
| 去掉尾部注解括号 | `泰勒·斯威夫特：时代之旅(2023)` → `泰勒·斯威夫特：时代之旅` |
| 去掉首尾的 `·` 与全角空格 | 演员名常有 `·` 前缀 |
| **中英互换纠错** | 若最终 `title_cn` 为空、而 `title_en` 含中文，把它挪回 `title_cn` —— 不让 UI 出现「中文片名显示在英文栏」 |
| **最终兜底** | 两个都空时 `title_cn = 原始 title`（保证 `title_cn` 100% 非空） |

**候选项洗不掉技术垃圾时，走 `parse_title_fallback()`**（从 `title` 里抠）：

```python
# 1) 《...》里的就是中文名（324/327 部影片的 title 都带《》）
groups = re.findall(r"《([^》]+)》", title)

# 2) 英文名：剥掉《》段和 [...] 段后，按 . 和空白切段，逐段丢弃：
#    - 技术词（TECH_TOKENS：4k/1080p/web-dl/x265/dts/atmos/djydxs/网盘/高码版…，约 90 个）
#    - 体积/集数尾巴（UNIT_TAIL：15.4G / 700MB / 24集）
#    - 纯数字（PURE_NUM）
#    - 4 位年份
#    - 任何含中文的段
# 3) 最后两道保险：剩下的串里至少要有 3 个连续字母才算英文名；
#    整条 title 里压根没有拉丁字母，就不硬造英文名
```

> **设计取向很明确：宁可返回空，也不吐一个带技术垃圾的"英文名"。** 实测结果支持这个取向 ——
> - `title_cn` 与原始终标逐字相同的只有 **2 / 327**（都是「极简帖」，原始标题本身就干净）
> - `title_en` 里含中文的行数 = **0**（中英互换纠错确实生效）

### 2.4 年份 / 上映日期

| 字段 | 规则 | 样例 |
|---|---|---|
| `year` | `YEAR_RE = (19\d{2}\|20\d{2})`，取第一个四位年份 → `INTEGER` | `◎年 代 1991` → `1991` |
| `release_date` | 优先取 ISO 形态 `(\d{4}-\d{1,2}-\d{1,2})`；取不到就**保留原文** | `1991-06-27(中国香港)` → **`1991-06-27`**（地区后缀被当噪点剥掉） |

`release_date` 保底的「保留原文」是有意的：宁可存 `2023-10-13(美国)` 这种带尾巴的串，也不要因为格式不标准就丢掉整条日期。

### 2.5 类型（genres）

- 用和名字同一套 `split_names()` 切分（分隔符 `/ ｜ | 、 , ，`）→ **JSON 数组**存 `movie.genres`。

```
◎类 别 惊悚 / 恐怖 / 鬼怪   →  ["惊悚", "恐怖", "鬼怪"]
```

取值的实际分布（top）：剧情 172 / 动作 102 / 喜剧 87 / 犯罪 73 / 惊悚 69 / 科幻 42…

### 2.6 产地 / 语言 —— **故意不拆**

```
◎产 地 中国大陆/中国香港     →  movie.region  = "中国大陆/中国香港"   ← 原样保留
◎语 言 粤语/汉语普通话       →  movie.language = "粤语/汉语普通话"      ← 原样保留
```

**为什么不拆成多值？** 因为这两栏在 UI 上只是「详情页资料区」的一行展示文本，拆开还要在 App 侧拼回去。而 `genres` 不一样 —— 它要当**题材过滤器**用（`filtersFor(fid)` 要从里面做 `GROUP BY`），所以必须拆成数组。

取值分布印证了这一点：`region` top 是 美国 97 / 中国大陆 50 / 韩国 30 / 中国香港 22；但已经是 `中国大陆/中国香港` 这种**合成值**的有 9 部。也就是说这两栏本来就允许「一条目多值」，拆了反而要额外定义排序。

### 2.7 豆瓣 ID / IMDb ID → `movie_ext_id` 表

**关键决定：外部 ID 不占用 `movie` 的列，放进独立的 ID 表。**

```
movie_ext_id(movie_id, source, id, subtype, season, episode, url, confidence, origin, …)
PRIMARY KEY (movie_id, source, id, subtype)
```

理由（二期已定）：豆瓣/IMDb/TMDB/TVDB/Bangumi… 每加一个来源都是一个 DDL 变更；用通用表则**加新 ID 空间零 DDL**，还能记置信度与来源。

提取规则：

```python
DOUBAN_ID = re.compile(r"douban\.com/subject/(\d+)")
IMDB_ID   = re.compile(r"imdb\.com/title/(tt\d+)")

douban → ("douban", "5675910", subtype="movie", url="https://movie.douban.com/subject/5675910/", confidence=90, origin="content")
imdb   → ("imdb",   "tt28814949", subtype="movie", url="https://www.imdb.com/title/tt28814949/", confidence=90, origin="content")
```

- `confidence = 90`：**不是 100**。因为这个 ID 是从用户发帖的正文里正则抠的，没有经过任何权威校验（TMDB find 接口匹配成功才配 100）。
- `origin = 'content'`：标记来源。将来接了 TMDB 自动匹配或人工补录，必须能分清「这条是抓来的还是人填的」。
- `INSERT OR IGNORE`：靠主键去重，重跑幂等。

**这里的两个真实损耗**：

1. **IMDb 链接会被 600 字截断切坏**。实测 `id=1`（千年魔界）的正文里是：
   ```
   ◎IMDb链接 https://www.imdb.com/title//      ← tt 号被截掉了
   ```
   正则 `(tt\d+)` 匹配不到 → 这部片的 imdb 记录丢失，只剩 douban。源库全量里这种坏链接有 **3 条**。
2. **豆瓣记录比 IMDb 多，不是解析偏心，是源帖本身如此**。源库侧统计：含 `douban.com/subject/` 的帖子 **15,339** 条，含 `imdb.com/title/tt` 的只有 **9,637** 条 —— 发帖人贴豆瓣链接的比例本来就高得多。出包库里 douban 322 vs imdb 303 的差距与这个比例一致。（**不要**把差距归因于「豆瓣链接在模板里更靠前」：实测位置中位数是 `◎IMDb链接`=157、`◎豆瓣链接`=231，豆瓣反而更靠后。）

### 2.8 主创 → `movie_credit` 表

```
movie_credit(movie_id, role, name, name_en, ord, origin)
PRIMARY KEY (movie_id, role, name)
role ∈ { director, writer, actor }
```

解析链（三个字段合用一个函数）：

```python
for role, key in (("director","directors"), ("writer","writers"), ("actor","actors")):
    for i, raw in enumerate(split_names(segs.get(key, ""))):
        nm = re.sub(r"\((?:id|ID)\s*[:：]\s*\d+\)", "", raw).strip(" ()（）")  # ① 剥 (id:1322797)
        nm_cn, nm_en = split_cn_en(nm)                                      # ② 中英拆开
        INSERT OR IGNORE ... (mid, role, nm_cn or nm, nm_en or None, i, "content")
```

| 步骤 | 规则 | 样例 |
|---|---|---|
| ① 剥离 ID 尾巴 | 论坛模板里人名后面挂豆瓣影人 ID：`(id:1322797)` | `陈晓冰 Hsiao-Bing Chen (id:1322797` → `陈晓冰 Hsiao-Bing Chen` |
| ② **中英拆开** | 正则：开头是连续的中日韩字符 → 中文名；后面跟的拉丁部分是 `name_en` | `陈晓冰 Hsiao-Bing Chen` → `name=陈晓冰`, `name_en=Hsiao-Bing Chen` |
| ③ `ord` | 在第 N 个位置上（0 起），保留主创排名的先后 | 导演列表第一个 `ord=0` |
| ④ 主键去重 | `(movie_id, role, name)` → 同一部片里同名同角色不重复 | — |

**实测结果**：

| role | 条数 | 有该段的部数 | 条/部 |
|---|---|---|---|
| `director` 导演 | **414** | 306 | 1.35 |
| `writer` 编剧 | **766** | 303 | 2.53 |
| `actor` 演员 | **313** | 313 | **1.00** ⚠️ |
| **合计** | **1,493** | 321（覆盖影片数） | — |

导演 1.35 条/部、编剧 2.53 条/部**是合理的**（一部片通常 1~2 个导演、多个编剧）。

### 2.8b ⚠️ 但是 `actor` 数据是坏的 —— 每个 "名字" 其实是整段演员表

**`actor` 恰好是 1.00 条/部**，这个整数本身就是信号。查下去发现：

```
movie_credit 里 id=3 的 actor 行：
  name   = '泰勒·斯威夫特 Taylor Swift 阿曼达·巴伦 Amanda Balen 泰勒·班克斯 Taylor Banks
            凯伦·张 Karen Chuang 奥黛丽·道格拉斯 Audrey Douglass …'   ← 长度 242 字符
  name_en = NULL
```

**姓名全空格的整段演员表被当成了「一个名字」。**

**根因**：三种角色用的分隔符**不一样**，而 `split_names()` 只认 `[/｜|、,，]`：

| 段 | 分隔符（实测该分隔符出现的部数） | 旧规则是否切得开 |
|---|---|---|
| `◎导 演` | 空格 306 部、`/` 59 部 | ✅ 切得开（1.35 条/部，正常） |
| `◎编 剧` | 空格 303 部、`/` 195 部 | ✅ 切得开（2.53 条/部，正常） |
| **`◎主 演`** | **空格 313 部（100%）** | ❌ **一个都切不开** |

演员表用的是「中文名 英文名 中文名 英文名 …」**纯空格分隔**，`split_names` 找不到任何分隔符 → 整段返回 1 个"名字"。连带后果：

- `actor` 313 条 / 313 部（平均数 = 中位数 = 最大数 = **1**）；
- 313 条里 **310 条含空格**，`name` 平均长度 **199 字符**，最长 **397**；
- `name_en` 因为 `split_cn_en` 的正则匹配失败而**全是 NULL**；
- 只有 3 条（1.0%）能正确拆出中英双名。

**这条规则漏了空格分隔。修法已验证**：在「拉丁字母 / 数字 之后紧跟空白、再跟中日韩字符」处**先切一刀**，再走原来的 `[/｜|、,，]` 切分：

```python
BOUND = re.compile(r"(?<=[A-Za-z0-9.'’])[ \u3000]+(?=[\u3400-\u4dbf\u4e00-\u9fff])")
# "泰勒·斯威夫特 Taylor Swift 阿曼达·巴伦 Amanda Balen"
#   → ['泰勒·斯威夫特 Taylor Swift', '阿曼达·巴伦 Amanda Balen']   ← 此时 split_cn_en 才拆得动
```

在现有 327 部的库上跑了对照实验：

| 规则 | actor 条数 | 条/部 | 中位数 | 能拆出中英双名 |
|---|---|---|---|---|
| **现状（旧规则）** | 313 | 1.00 | 1 | **1.0%** |
| 只加拉丁→中文边界 | **3,202** | 10.23 | 10 | **93.4%** |
| 再加「中文→中文」边界 | 3,240 | 10.35 | 10 | 92.3%（另多 38 条纯中文名，同样有效） |

- **对导演零回归**（414 → 414），**对编剧还有 +9 条修正**（766 → 775，修的是两个编剧被空格粘在一起的情况）。
- 残留问题：约 40 条（1.2%）会切出 `'保'`、`'戴'`、`'尼'` 这种单字碎片 —— 那些是**没写英文名的演员**，边界判据（依赖拉丁字母）失效导致切在名字中间。数量小，可接受，后续再细化。
- **这条修了之后 `movie_credit` 会从 1,493 行长到约 4,400 行**（actor 313 → 3,202），库体积与插入开销要重估。

> **当前有没有影响？没有。** App 侧**还没有读 `movie_credit`**（`v_movie_app` 和 `v_episode` 都没暴露它），所以坏数据现在只是躺在库里。
> 但**一旦详情页要显示「主演」，就会把一整段 199 字的演员表塞进一行** —— 必须在接线之前修掉。

### 2.9 简介（synopsis）

- 规则最简单：`segs.get("synopsis")` **原样取**，不做任何清洗或截断补全。
- **覆盖率只有 29.7%（97/327）** —— 这是整张表里最低的一项，原因是**双重的**：

| 原因 | 证据 |
|---|---|
| 源帖本身就不一定有 `◎简 介` 段 | 源库全库 `◎简 介` 出现率 **33.3%**（5,785/17,395）；候选集里 34.5%（5,127/14,852） |
| 有也可能被 600 字截断切掉 | `◎简 介` 在模板里几乎排最后，最容易被切 |

> 这个字段**不可能在现有链路上提升**。要提升只有两条路：① 源库去掉 600 字截断（回源站重抓）；② 接 TMDB 按 `movie_ext_id` 补简介。

### 2.10 时长与评分

| 字段 | 规则 | 正则 | 覆盖率 |
|---|---|---|---|
| `runtime_min` | 从 `◎片 长 90分钟` 抠数字 → 整数分钟 | `MIN_RE = (\d{1,4})\s*分` | 82.9%（271/327） |
| `rating_douban` | 从 `◎豆瓣评分 5.0/10 (114人评价)` 抠 → `REAL` | `RATING_RE = ([\d.]+)\s*/\s*10` | 65.1%（213/327） |
| `rating_imdb` | 从 `◎IMDb评分` 段同理 | 同上 | 43.4%（142/327） |

**`rating_imdb` 比 `rating_douban` 低 22 个百分点，真实原因不是被截断**（这一点我一开始猜错了，用源库数据核实后纠正）：

| 说法 | 核实结果 |
|---|---|
| ~~IMDb 评分段位置靠后、更容易被截~~ | ❌ **不成立**。实测位置中位数 `◎IMDb评分`=135、`◎豆瓣评分`=203 —— IMDb 评分段**更靠前**，被截断的概率更低 |
| 截断本身 | ❌ 也不是主因。两个标签所在帖子被截断的比例几乎一样（`◎IMDb评分` 97.3%，`◎豆瓣评分` 95.8%），差距只有 1.5 个点 |
| **源帖就少写 IMDb 评分** | ✅ **主因**。全库 `◎IMDb评分` 段只出现 **7,024** 次（40.4%），而 `◎豆瓣评分` 出现 **9,610** 次（55.2%） |

另外，`RATING_RE` 要求 `/10` 前面必须有数字，所以它会**正确地把占位符判成"无评分"**：

```
◎豆瓣评分 /10 from 0 users      →  匹配失败 → rating_douban = NULL   ← 914 条，0 人评分就是没评分
◎豆瓣评分 8.0 分 / （总分10分）   →  匹配 `8.0 ... /10`… 实测 1 条漏掉
◎IMDb评分 N/A                  →  匹配失败 → NULL                   ← 2 条
```

`◎豆瓣评分` 段能成功抠出评分的比例是 **90.4%**（8,684/9,610），`◎IMDb评分` 是 **99.9%**（7,020/7,024）—— 也就是说**只要能读到这一段，格式几乎不会认错**；覆盖率低纯粹是「帖子里没写」。

### 2.11 `uid` —— 稳定主键，规则写在注释里

```python
# ★ 不用片名派生（片名会改），也不用自增 id（重建库会变）。
# 现阶段用 't{tid}'；将来若库给出更权威的锚点，再切 'd{douban_id}'。
uid = "t%d" % tid
```

实测：`uid like 't%'` = 326 条，`uid like 'manual-%'` = 1 条（三期的手工条目）。**发布后永不更换** —— 用户播放进度、收藏都挂在它上面。

### 2.12 `poster_url` —— 故意留空

实测 `poster_url` 覆盖率 **0 / 327**。这不是 bug：

- 视图里是 `COALESCE(NULLIF(m.poster_url, ''), m.pic) AS poster` —— **留空时会自动回落到论坛封面 `pic`**，所以 App 侧看得到图。
- 留这一列是为了将来把海报**镜像到自有 OSS** 后填进来（论坛附件域名 `djydxs.oss-cn-chengdu.aliyuncs.com` 一旦变化，全库海报会裂 —— 二期文档已记过这个风险）。

---

## 3. 第三段：出包库 → APK

这一段不产生元数据，只做「加手工条目 + 收紧文件形态 + 出清单」：

```
build/SuperMOV.db
  ↓ tools/db_manual_entries.py   手工条目 upsert（src_tid 用负数）+ pin_top + VACUUM + journal=DELETE
  ↓ tools/build_manifest.py      产 SuperMOV.json（在线更新清单）+ assets/SuperMOV.version
  ↓ cp                           → DJYDXS-TV/app/src/main/assets/SuperMOV.db
```

细节见三期文档。与元数据链路相关的只有一点：**手工条目的元数据是直接写列的**（`title_cn` / `year` / `genres` / `synopsis` / `pan_url` …），因为手工条目没有 `content` 可解析。

---

## 4. 新库（v2 / 出包库）完整结构

`meta.schema_version = 2`，体积 2.41 MB，`db_version = 2026091902`。

### 4.1 `movie`（42 列）

| 分组 | 列 | 来源 |
|---|---|---|
| **身份** | `id`（自增，仅内部用）<br>`uid`（**跨端稳定锚点**，`t{tid}`） | 迁移生成 |
| **来源锚点** | `src_tid`（UNIQUE）、`fid`、`forum_name`、`src_first_seen`、`src_last_seen`、`src_changed`、`src_deleted`、`src_fingerprint` | 同步原样 |
| **★ 解析出的元数据** | `title_cn`、`title_en`、`title_alt`、`year`、`release_date`、`runtime_min`、`region`、`language`、`genres`、`rating_douban`、`rating_imdb`、`synopsis`、`poster_url` | **`db_upgrade_v2` 从 content 解析** |
| **原始帖子** | `title`、`pic`、`content`（600 字截断）、`post_date`、`lastpost_date`、`runtime`、`classification`、`tags`、`classes` | 同步原样 |
| **筛选** | `is_restricted`、`is_erotic`（两者任一为 1 的影片在同步侧就被排除） | 同步原样 |
| **网盘（冗余）** | `pan_status`、`pan_url`、`pan_pwd`、`video_count`、`total_size` | 同步原样 |
| **运营** | `pin_top`（0/1，非 0 排该栏目首位） | 三期新增 |
| **时间** | `created_at`、`updated_at` | — |

> 注意：**没有 `imdb_id` / `douban_id` / `tmdb_id` 列**。它们被搬进了 `movie_ext_id` 表。这一点已用实验证实 —— `db_upgrade_v2.py` 是**只加列**的迁移（输入 31 列 → 输出 46 列，丢掉的原列 = 无），所以出包库只有 42 列，说明**当时的输入库是 27 列**（42 − 15 个新增列），那时同步脚本还没加那 4 个 ID 列。

### 4.2 `movie_ext_id`（625 行）

```sql
CREATE TABLE movie_ext_id (
    movie_id   INTEGER NOT NULL,
    source     TEXT    NOT NULL,     -- douban / imdb / tmdb / tvdb / bangumi / ...
    id         TEXT    NOT NULL,
    subtype    TEXT,                 -- movie / tv / season / episode
    season     INTEGER,              -- 仅 subtype=season
    episode    INTEGER,              -- 仅 subtype=episode
    url        TEXT,
    confidence INTEGER NOT NULL DEFAULT 100,
    origin     TEXT,                 -- content / manual / api
    created_at TEXT,  updated_at TEXT,
    PRIMARY KEY (movie_id, source, id, subtype)
)
```

| source | 行数 |
|---|---|
| `douban` | 322 |
| `imdb` | 303 |
| **合计** | **625**（覆盖 322 部影片） |

`subtype` 必须用起来：TMDB 的 `tv/12345` 是剧、`tv/12345/season/2` 是季 —— **别把季的 ID 塞进 `movie` 行里**。

### 4.3 `movie_credit`（1,493 行）

```sql
CREATE TABLE movie_credit (
    movie_id INTEGER NOT NULL,
    role     TEXT    NOT NULL,       -- director / writer / actor
    name     TEXT    NOT NULL,       -- 中文名（或原名）
    name_en  TEXT,                   -- 拆出来的英文名，可为 NULL
    ord      INTEGER NOT NULL DEFAULT 0,
    origin   TEXT,
    PRIMARY KEY (movie_id, role, name)
)
```

### 4.4 `rename_rule`（7 行）

转存后更名的规则表（`scope='ext'` 换后缀 / `scope='filename_regex'` 正则改名）。**目前 App 侧未接线**（`PanRename` 已就绪但无调用点），等真机验证 `filemanager?opera=rename` 的 errno 语义。

### 4.5 `pan_link` / `pan_video`

| 表 | 行数 | 关键列 |
|---|---|---|
| `pan_link` | 499（**其中 327 条 `status='ok'`**） | `src_link_id`、`url`、`pwd`、`status`、**`shareid`**、**`share_uk`**、`dir_count`/`file_count`/`video_count`/`total_size`、`detail`、`checked_at` |
| `pan_video` | **2,525** | `pan_link_id`、`path`、`filename`、`ext`、`size`、`season`、`episode`<br>v2 新增：`fs_id`、`rename_to`、`rename_state`、`rename_updated_at` |

- `pan_video` 是**网盘内 mkv/mp4 的全量清单** —— 这是本次重构最大的红利：详情页可以**零网络请求**秒出剧集表。
- `season` / `episode` 只认明确标记（`S01E05`、`第5集`、`XX.EP05`），**不做裸数字猜测**（早期版本会把 `H.264` 猜成第 264 集，已修）。猜不到就是 NULL，所以**不能靠它们排序**，要用自然序兜底。
- `pan_video` **没有 `fs_id`** 时无法「不转存直接播分享」—— v2 已补上这一列。

### 4.6 两个只读视图（App 的唯一数据契约）

```sql
CREATE VIEW v_movie_app AS
SELECT m.uid, m.id, m.src_tid, m.fid, m.forum_name,
       COALESCE(NULLIF(m.title_cn,''), m.title) AS name,     -- ★ 片名回退链
       m.title_en, m.title_alt, m.year, m.release_date, m.runtime_min,
       m.region, m.language, m.genres, m.rating_douban, m.rating_imdb, m.synopsis,
       COALESCE(NULLIF(m.poster_url,''), m.pic)  AS poster,   -- ★ 海报回退链
       m.classification, m.tags, m.pan_status, m.pan_url, m.pan_pwd,
       m.video_count, m.total_size, m.updated_at,
       COALESCE(m.pin_top,0) AS pin_top,
       (SELECT l.id       FROM pan_link l WHERE l.movie_id=m.id AND l.status='ok'
         ORDER BY l.video_count DESC LIMIT 1) AS pan_link_id,
       (…shareid…) (…share_uk…),
       (SELECT count(*) FROM movie_ext_id e WHERE e.movie_id=m.id) AS ext_id_count
FROM movie m
WHERE COALESCE(m.src_deleted,0) = 0;
```

`v_episode` 把 `movie × pan_link × pan_video` 三表 join 成「一集一行」（2,525 行），供详情页选集。

**为什么必须走视图**：将来运营把基表拆成三张、把字段换成 JSON，**只要视图列名不变，App 一行都不用改**。配套纪律是 App 侧**禁止 SELECT 基表**。

### 4.7 索引（16 个，已与真实库逐条核对）

| 表 | 索引 | 列 |
|---|---|---|
| `movie` | `idx_movie_year` | `year` |
| | `idx_movie_titlecn` | `title_cn` |
| | `idx_movie_uid` | `uid` |
| | `idx_movie_pin` | `pin_top` ← 三期新增 |
| | `idx_movie_fid` | `fid` |
| | `idx_movie_class` | `classification` |
| | `idx_movie_status` | `pan_status` |
| `movie_ext_id` | `idx_extid_source` | `source, id` |
| `movie_credit` | `idx_credit_movie` | `movie_id, role` |
| `pan_link` | `idx_panlink_tid` | `src_tid` |
| | `idx_panlink_status` | `status` |
| | `idx_panlink_url` | `url` |
| `pan_video` | `idx_panvideo_link` | `pan_link_id` |
| | `idx_panvideo_tid` | `src_tid` |
| | `idx_panvideo_ren` | `rename_state` |
| `reject` | `idx_reject_reason` | `reason` |

`idx_movie_pin` 是**两条升级路径 DDL 一致性**的一个实例：`db_upgrade_v2.py`（在线升级）建了它，
而 `db_manual_entries.py`（离线出库）一开始漏了 —— 已补 `ensure_indexes()` 第 3b 步，
并在两个脚本各加断言（详见三期文档 §5）。

---

## 5. 实测覆盖率（从出包库导出）

| 字段 | 覆盖 | 占比 | 天花板在哪 |
|---|---|---|---|
| `uid` | 327 / 327 | **100%** | — |
| `title_cn` | 327 / 327 | **100%** | 有最终兜底（退回原始 title） |
| `year` | 323 / 327 | 98.8% | 4 帖无 `◎年 代` |
| `region` | 322 / 327 | 98.5% | — |
| `language` | 322 / 327 | 98.5% | — |
| `release_date` | 321 / 327 | 98.2% | — |
| `genres` | 320 / 327 | 97.9% | — |
| `title_en` | 310 / 327 | 94.8% | **17 部片本来就没有官方英文名**（华语片常见） |
| `title_alt` | 271 / 327 | 82.9% | 只有一个名字的帖子自然没有别名 |
| `runtime_min` | 271 / 327 | 82.9% | — |
| `rating_douban` | 213 / 327 | 65.1% | 帖子里不写评分 |
| `rating_imdb` | 142 / 327 | 43.4% | **源帖就少写 IMDb 评分**（全库该段只出现 40.4%，见 §2.10） |
| **`synopsis`** | **97 / 327** | **29.7%** | **源帖 33.3% 就没有 `◎简 介` —— 硬天花板** |
| `poster_url` | 0 / 327 | 0% | 有意留空（视图回落到 `pic`） |
| `pin_top` | 1 / 327 | 0.3% | 三期的测试库条目 |

关联表：`movie_ext_id` 625 行覆盖 322 部；`movie_credit` 1,493 行覆盖 321 部 —— **其中 `actor` 313 条是坏的**（每部恰好 1 条，整段演员表当一个名字），见 §2.8b。修正后 `actor` 应为 **3,202** 条。

---

## 6. 一个完整样例（真实数据，id=1）

**输入**（`movie.title` + `movie.content`）：

```
title   = 《千年魔界》1991.DVDRip 700MB

content = 本帖最后由 乔巴 于 2022-11-8 14:27 编辑 ◎译 名 千年魔界 / Bewitched Area Of
          Thousand Years ◎年 代 1991 ◎产 地 中国香港 ◎类 别 惊悚 / 恐怖 / 鬼怪
          ◎语 言 汉语普通话 ◎上映日期 1991-06-27(中国香港)
          ◎IMDb链接 https://www.imdb.com/title//            ← ★ tt 号被截断
          ◎豆瓣评分 5.0/10 (114人评价) ◎豆瓣链接 https://movie.douban.com/subject/5675910/
          ◎片 长 90分钟 ◎编 剧 陈晓冰 Hsiao-Bing Chen (id:1322797   ← ★ 被截断
          …（600 字处硬切）
```

**输出**：

| 目标 | 值 | 用到的规则 |
|---|---|---|
| `title_cn` | `千年魔界` | 别名池 + 含 CJK 判定 |
| `title_en` | `Bewitched Area Of Thousand Years` | 别名池 + 纯 ASCII 判定（**没有**误取 `DVDRip`） |
| `title_alt` | （空） | 池里只有这两个名字 |
| `year` | `1991` | `YEAR_RE` |
| `release_date` | `1991-06-27` | `DATE_RE` —— **地区后缀 `(中国香港)` 被剥掉** |
| `runtime_min` | `90` | `MIN_RE` |
| `region` | `中国香港` | 直取 |
| `language` | `汉语普通话` | 直取 |
| `genres` | `["惊悚", "恐怖", "鬼怪"]` | `split_names` → JSON |
| `rating_douban` | `5.0` | `RATING_RE` |
| `rating_imdb` | `NULL` | **IMDb 链接被截断成 `title//`，正则匹配失败** |
| `synopsis` | `一对情侣须眉和少杰在深山里踏青…` | 直取（这段恰好没被截断） |
| `movie_ext_id` | `douban = 5675910`（conf 90, origin content） | `DOUBAN_ID` |
| `movie_credit` | `writer / 陈晓冰 / Hsiao-Bing Chen / ord=0` | `split_names` → 剥 `(id:…)` → `split_cn_en` |

**对照组（id=2，极简帖）**：

```
title   = 志愿军：雄兵出击
content = 链接：https://pan.baidu.com/s/1nB8HaMU2Yrd91w4MwZkeWA?pwd=6vjj 提取码：6vjj
```

→ `segs` 为空（整篇没有 `◎`）→ 走 `parse_title_fallback` → title 里没有《》也没有拉丁字母 → `title_cn = 志愿军：雄兵出击`（最终兜底），其余字段全空。

**这解释了为什么「标题里带《》」是全库的绝大多数（324/327）** —— 带《》的帖子基本都是套了信息模板的，也就能解析出完整元数据。

---

## 7. 已知损耗清单（都是实测，不是推测）

| # | 损耗 | 证据 | 能不能修 |
|---|---|---|---|
| 1 | **`content` 硬截断 600 字** | 源库 17,395 帖中 **14,268 帖正好 600 字**（82%）；最长 = 600，超过的 = 0 | ❌ 需回源站重抓（改源库抓取上限） |
| 2 | **简介只有 29.7%** | 源帖本身 33.3% 无 `◎简 介`；有也可能被截断 | ⚠️ 只能靠 TMDB 按 ID 补 |
| 3 | **IMDb 链接被切成 `title//`** | 3 条坏链接；`id=1` 就是实例 | ⚠️ 需回源重抓 |
| 4 | **主创覆盖 321/327（导演 306 部有段、编剧 303 部、演员 313 部）** | 源帖本身有段的比例就不高（`◎导 演` 58.5%、`◎主 演` 41.2%） | ⚠️ 同上 |
| 4b | **★ `actor` 数据是坏的：整段演员表被当成 1 个名字** | `actor` 恰好 **1.00 条/部**（313 条 / 313 部）；310 条含空格、`name` 平均 199 字、`name_en` 全 NULL | ✅ **修法已验证**（`◎主 演` 用空格分隔，规则漏了；见 §2.8b） |
| 5 | **`◎译 名` / `◎片 名` 被写反** | `id=18`：译名段是 `Pegasus 3`、片名段是 `飞驰人生3` | ✅ **已用「认内容不认字段名」解决** |
| 6 | **隐形字符污染** | 网页复制带进的零宽/双向控制符 | ✅ `clean_ws()` 剥掉 |
| 7 | **`region` / `language` 不拆多值** | `中国大陆/中国香港` 原样保留 | ✅ 有意设计（只做展示） |
| 8 | **`poster_url` 全空** | 0/327 | ✅ 有意留空，视图回落到 `pic` |

---

## 8. 四个需要你拍板的问题

### 8.1 ★★ 先把 `◎主 演` 的空格分隔补上（优先级最高）

见 §2.8b。这条是**唯一一个「数据当前就是错的」**的问题，其他都是「可以更好」。

- **改动量**：`db_upgrade_v2.py` 里 `split_names()` 前面加一刀正则，约 5 行。
- **代价**：对导演零回归（414→414）、编剧小修（766→775）、演员从 313 → **3,202**（真正可用）。
- **不做的后果**：详情页一旦显示「主演」，会把一整段 199 字的演员表塞进一行。
- **建议顺序**：先改这条，再跑全量（否则 9,000 部片会产出 9,000 行坏数据，事后还得清）。
- **残留**：约 1.2% 会切出单字碎片（没写英文名的演员触发不了边界），可接受，后续再迭代。

### 8.2 ★ 同步侧新增的 4 个 ID 列 与 `movie_ext_id` 表重复了

今天的同步改造给 v1 库加了 `imdb_id` / `douban_id` / `tmdb_id` / `tmdb_type` 四列（`新库全量同步方案.md` §一）。但：

- v2 出包库里**没有**这四列，外部 ID 权威地存在 `movie_ext_id` 表里；
- `db_upgrade_v2.py` 的 `parse_ext_ids()` **只从 `content` 解析，根本不读这四列**。

所以那四列如果不动，下场是：**一路原样穿透到 v2 出包库**（迁移是只加列、不删列），成为一份**没人读、也不会自动更新的死数据**，而且和 `movie_ext_id` 形成两个真相源 —— 将来有人改了一边忘了另一边，就会出现「同一个库、两个不同的 douban id」。

三个选项：

| 选项 | 做法 | 评价 |
|---|---|---|
| **A（推荐）** | 保留四列，但**明确它的定位是「同步阶段的原样缓存」**，并在 `db_upgrade_v2.py` 里**优先读列、列空才解析 content** —— 让 `movie_ext_id` 继续当唯一权威 | 少一次正则、能吃到同步侧将来接 TMDB 的成果；代价是迁移脚本要改一小段 |
| B | 迁移时把四列的内容也灌进 `movie_ext_id`，然后**删列**（需要 `ALTER TABLE DROP COLUMN`） | 最干净，但破坏「无损迁移」原则，且 SQLite 的 DROP COLUMN 有限制 |
| C | 什么都不做 | 简单，但埋了「双真相源」的雷 |

### 8.3 全量重跑后元数据要不要一起升级

本次全量是 14,852 部候选（旧库只有 489 部），`content` 的标签出现率是 60% 上下（`◎译 名` 60.5%、`◎类 别` 60.7%）。按这个比例推算，解析后大致能有 **9,000 部拿到完整元数据**，比现在 326 部的量级完全不是一个时代。

这带来两个新问题：

1. **`movie_credit` 会涨到几万行**（现在 1,493 行 / 326 部 ≈ 4.6 条/部 → 9,000 部 ≈ 4 万行；**若先修 §8.1 的演员分隔，头部数据会更准但总行数会到 10 万量级**）。`PRIMARY KEY (movie_id, role, name)` 的插入开销和库体积都要重估。
2. **解析耗时**：现在是几秒级（327 行），涨到 1.5 万行仍是分钟级，可接受；但**如果每次全量同步都重跑解析**，就要保证它就是幂等的（现在靠 `INSERT OR IGNORE` + 直接 `UPDATE` 覆盖，是幂等的）。

### 8.4 两套流程要合并成一条命令

现在改一次元数据要手工走三段：

```bash
python supermov_sync.py --full-rescan          # ① 同步（10~14h）
python tools/db_upgrade_v2.py --in ... --out ...  # ② 解析迁移
python tools/db_manual_entries.py --db ...      # ③ 手工条目 + 置顶
python tools/build_manifest.py ...              # ④ 出清单
```

建议包一个 `build_all.py` 串起来（②③④ 加起来不到一分钟，只有 ① 是长的）。这样「改了解析规则」就只需重跑一次，不用记四条命令的参数。

---

## 9. 操作手册

**只改了解析规则，想重新解析（不重新探测）**

```bash
# 从同步库重新生成出包库
# ★ --db-version 必须比线上大（当前 2026091902），否则设备端会拒绝这次的库
python tools/db_upgrade_v2.py \
    --in  C:\py\SuperMOVDB\SuperMOV.db \
    --out build\SuperMOV.db \
    --db-version 2026091903
# 再叠加手工条目 + 出清单
python tools/db_manual_entries.py --db build/SuperMOV.db
python tools/build_manifest.py --db build/SuperMOV.db --out SuperMOV.json \
    --url https://800915.xyz/db/SuperMOV.db \
    --version-file DJYDXS-TV/app/src/main/assets/SuperMOV.version
cp build/SuperMOV.db DJYDXS-TV/app/src/main/assets/SuperMOV.db
```

**只体检不改库**

```bash
# 元数据覆盖率 + 坏数据体检（退出码 1 = 有坏数据）
python tools/db_meta_report.py --db build/SuperMOV.db --src "C:\py\DJYDXSDB\DJYDXS..db"
python tools/db_manual_entries.py --db build/SuperMOV.db --check-only
python tools/verify_sql.py        # 会把 MovieStore.java 的 SQL 在真实库上逐条跑
```

**想单独试解析规则**（不碰任何库）：直接 import `db_upgrade_v2` 里的 `parse_content` / `build_names`：

```python
import importlib.util, sys
spec = importlib.util.spec_from_file_location("u2", r"tools/db_upgrade_v2.py")
u2 = importlib.util.module_from_spec(spec); spec.loader.exec_module(u2)
segs = u2.parse_content("◎译 名 A / B ◎年 代 1991 ◎类 别 惊悚 / 恐怖")
print(segs, u2.build_names(segs, "《X》1991.1080p"))
```

**改标签映射**：只改 `db_upgrade_v2.py` 顶部的 `LABELS` / `CANON` 两张表，然后重跑第二段。
**改英文名清洗**：只改 `TECH_TOKENS`（技术词黑名单）。
