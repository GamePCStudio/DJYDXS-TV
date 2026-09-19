# 操作文档：废弃 LAMPA 融合代码 + DJYDXS3NexioTMDB 原生 UI 改造

> 依据：`docs/LAMPA效果-DJYDXS3NexioTMDB-原生UI实现研究.md`（下称「研究文档」，其 §2/§3/§4/§5 为唯一实施基准；LAMPA_UI_构成_重构蓝本.md 的 §7 Compose 映射已判定作废）。
> 性质：**纯操作计划，未动任何代码/文件/分支/构建**。执行方式由用户定夺。
> 日期：2026-09-19

---

## 0. 一句话总结

**废弃 SuperMovLAMPA（lanba 融合仓，含全部 Crosswalk/web 桥代码）；在 `GamePCStudio/DJYDXS-TV` 的 `DJYDXS3NexioTMDB` 分支上，按现有「XML 骨架 + Java 填充 + 零第三方依赖」范式加做 LAMPA 五大效果。数据层唯一改动 = SuperMOV.db 走库版本迁移补 `backdrop` 列。共 9 步，分 3 个批次，每批次一个独立可验证的构建。**

## 1. 范围定义

**废弃（A 类，一次性清理）：**

| 对象 | 位置 | 处置 |
|---|---|---|
| 蓝光影库 融合 App | `GamePCStudio/SuperMovLAMPA` 仓（`c:\py\lampa\lanba\`）：Crosswalk 宿主、ExternalBridge、assets/www（index.html/css/app.css/bridge.js/app.js）、SuperMOVTMDB.db、build 工作流 | 停止维护；仓**保留封存**（私有仓，不删），readme 顶部加「已废弃」横幅 |
| lanba 的「桥+网页」UI 路线 | 同上 | 全部不迁移到 nexio |
| 蓝本 §7 Compose 映射 | `c:\py\lampa\LAMPA_UI_构成_重构蓝本.md` | 标注作废（研究文档 §1 已纠正：本地 nexio 工程 `com.supermov.tv` 是纯 Java 原生 UI，无 Kotlin/Compose 组件） |

**实现（B 类，在 `DJYDXS3NexioTMDB` 上）：**

基准 = 远端 `origin/DJYDXS3NexioTMDB`（`0bda2a2`，v1.30，比本地 `DJYDXS3Nexio`(aa5e667, v1.27) 领先 8 commit、24 文件，数据层 MovieDb/MovieStore/DbUpdater/PanRename + SuperMOV.db 已就位）。五大效果按研究文档 §3 顺序：① 全局背景交叉淡入 → ② 白描边焦点 → ③ 渐进淡入/骨架脉冲 → ④ 详情页背景大图+遮罩+评分徽章 → ⑤ feed 大卡行（方案B） → ⑥ 页面栈方向性动画（首帧位移法）。（研究文档 §3 原排序中"首页 feed 大卡"在详情页背景之后，本文沿用其优先级；"多行海报墙方案 A"暂不做，单独排期。）

**不做（明确排除）：** 研究文档 §2 的「方案 A 整页行化」、Compose 组件重写、Leanback/Fragment 引入、在线库清单部署（`800915.xyz` 维持不动）。

## 2. 已核实事实（动手前证据，2026-09-19）

1. 本地仓 `C:\Users\GamePC\WorkBuddy\2026-09-16-12-51-45\DJYDXS-TV` 当前在 `DJYDXS3Nexio` @ aa5e667；`git fetch origin DJYDXS3NexioTMDB` 成功，远端头 `0bda2a2`。
2. `origin/DJYDXS3NexioTMDB` 文件清单：`Site.java`/`WebLoginActivity.java` 已删，数据层 4 类 + `assets/SuperMOV.db`(2.5MB)+`SuperMOV.version` 已入内嵌。
3. **该分支 `MovieStore.LIST_COLS` 无 TMDB 字段**：`src_tid,uid,fid,forum_name,name,title_en,title_alt,year,release_date,runtime_min,region,genres,rating_douban,rating_imdb,poster,classification,pan_status,pan_url,pan_pwd,video_count,total_size`（`poster` 是库内旧海报列）→ **效果① 必须走数据层加列**（研究文档 §5 风险 4，走库版本迁移）。
4. 焦点监听现状（aa5e667 基准 `MovieAdapter` L124-131，TMDB 分支同构）：`setScaleX/Y(1.08)` + `setAlpha(0.75/1)` + 背景 `bg_movie_focus`（蓝描边）+ 标题变蓝 → **全部要按 LAMPA 白描边改**。
5. `activity_list.xml` 根→列表共 6 层 `clipChildren="false"` 已声明；`AndroidManifest` 各 Activity 带 `configChanges` 锁定 → 页面动画用**首帧位移法**（研究文档 §2 效果③ 方案 2），不碰窗口转场。
6. `ImageLoader` 为 73 行零依赖（6 线程池 + `ConcurrentHashMap` 无上限缓存）→ 补 LRU + `inSampleSize`。

## 3. 分步操作（9 步 / 3 批次）

> 批次划分 = 云端构建节奏：批次 1 完成即出构建 1 装机看效果，批次 2/3 同理。
> 每步给出：文件锚点（精确到方法/行号区域）、动作、验证点。锚点行号以 FETCH_HEAD(0bda2a2) 源码为准，动手时先 `git checkout` 再按实际行号核对。

### 批次 1：呼吸感（背景层 + 焦点质感）

**Step 1｜SuperMOV.db 走库版本迁移补 `backdrop` 列**
- 数据准备（本机离线，一次性）：用 `SuperMOVTMDB.db`（lanba 仓内，327 部、90.2% 有 TMDB `backdrop_path`）做映射源，把 TMDB `poster_path`/`backdrop_path` 两列回填进 `DJYDXS3NexioTMDB` 的 `SuperMOV.db`（按 `v_movie_app` 的 uid/名匹配；映射脚本放仓内 `tools/`，生成新 db 文件 + `SuperMOV.version` 版本号 +1 + 清单 sha256 重算）。
- 迁移机制：`MovieDb` 已有一套「assets 内嵌版本 vs 工作副本版本，取最大」逻辑（`MovieStore`/`MovieDb` 现有代码），**加列走版本迁移**：新 `SuperMOV.db`（含 `tmdb_poster`/`tmdb_backdrop` 列）+ 升 `SuperMOV.version`；`DbUpdater` 在线清单同步重算 sha256（清单本体不上线，只保证逻辑不断）。
- 文件：`app/src/main/assets/SuperMOV.db`、`SuperMOV.version`、`tools/enrich_backdrop.py`（新增）、`MovieDb.java`（迁移钩子，仅版本判定逻辑）。
- 验证：`verify_sql.py` 类脚本跑 `PRAGMA table_info(v_movie_app)` 含新列；`sqlite3 integrity_check=ok`；db 打开后查询回填行数 ≥ 295。

**Step 2｜全局背景交叉淡入层（效果①）**
- `res/layout/activity_list.xml`：根 LinearLayout 最底层加 1 个全屏 `ImageView`（`@+id/bgLayer`，`match_parent`，`alpha=0.5f`，置于 RecyclerView 之下，`background=#1d1f20`）。
- `MainActivity`：拿到聚焦条目后调 `ImageLoader.load(backdropUrl, bgLayer)`；`backdropUrl = "https://image.tmdb.org/t/p/w780/" + tmdb_backdrop`（为空回退 `poster`，再空则显示纯色底）。
- `MovieAdapter` 焦点监听器（现有 L124 区）：`has==true` 时把该条目 `backdropUrl` 回调给 Activity（新增 1 个 `OnFocusMovie` 回调接口）。
- 交叉淡入（研究文档 §4 简版即可）：单 ImageView + `ViewPropertyAnimator.alpha(0f,100ms)` → setBitmap → `alpha(0.5f)`；双 View 交叉版留待批次 2 视效果升级。
- `ImageLoader`：`ConcurrentHashMap` 改 **LRU**（`LruCache<String,Bitmap>`，上限按 `Runtime.maxMemory()/16`）+ 背景图 `inSampleSize` 降采样（w780 → 目标 1280px 宽以内）。
- 验证：构建装机后，遥控器左右移焦点 → 背景 0.2s 内淡入对应片横图；快速横扫不 OOM（logcat 无 GC 风暴）。

**Step 3｜白描边焦点（效果②）**
- 新 `res/drawable/bg_poster_lampa_focus.xml`：layer-list = 实底 `#FF22262B` 圆角 + 白描边 `#FFFFFFFF` 3dp 圆角 8dp（复刻 `::after` 语义；外扩靠 margin 空隙，见下）。
- `res/layout/item_movie.xml`：FrameLayout `margin 4dp → 6dp`（描边外扩 0.5em 的空隙）；**逐层复查 6 层 `clipChildren="false"` 链路不断**（Step 事实 5）。
- `MovieAdapter` 焦点监听：**删 `setScaleX/Y`**，保留「换背景 + 标题变色」；标题色 `#FF42A5F5 → #FFFFFFFF`（LAMPA 白）；入场加 `alpha(0→1,150ms)` 或瞬切（先瞬切，批次 2 再调）。
- 验证：聚焦卡片只出现白圈，**整行不跳动**（对比改前 scale 效果）。

### 批次 2：呈现细节（淡入/骨架 + 详情页）

**Step 4｜渐进淡入 + 骨架脉冲（效果⑤前）**
- `ImageLoader`：目标 View 初始 `alpha=0`（占位灰底 `bg_movie_normal` 持续显示）→ 到位后 `animate().alpha(1f).setDuration(200)`；LRU 命中时跳过动画。
- `activity_list.xml` 的 `tvEmpty`（"加载中…"）：`ValueAnimator` alpha 0.4↔1 循环（约 8 行）。
- 验证：弱网（拔线重连）场景下占位→淡入节奏自然，无闪跳。

**Step 5｜详情页背景大图 + 水平遮罩 + 评分徽章（效果⑤后）**
- `res/layout/activity_detail.xml`：加全屏 `ImageView`（backdrop w1280，与 Step 2 同构）+ 上层 `GradientDrawable` 水平遮罩（`#00000000→#FF1d1f20`，向右淡出保留左侧内容区可读）；`.loaded` 后 1 次 200ms 淡入。
- 评分徽章：`rating_douban`/`rating_imdb` 渲染成 LAMPA 风格小徽章（圆角方块：大数字 + 小来源名），复用现有 `DetailActivity` 文本填充处。
- 验证：详情页背景随片变化，左侧文字区被遮罩压暗可读。

### 批次 3：结构与节奏（feed 大卡 + 页面动画）

**Step 6｜首页 feed 大卡行（效果④ 方案 B）**
- `activity_list.xml` 顶部（分类/过滤器行之下、网格墙之上）加 1 个横向 `RecyclerView`（复用 `OptionAdapter` 横滚模式）。
- 新 item 布局 `item_feed_card.xml`：feed-item 大卡（label + 标题 + 简介两行 + 右侧海报缩略图 + 播放/详情小按钮）；新 `FeedCardAdapter`（数据 = 当前分类下 `pin_top>0` + 最近更新，`MovieStore` 已有排序方法可复用）。
- 验证：首页第一屏出现"大信息卡 + 呼吸背景 + 白描边"，即 LAMPA 首页记忆点 ~70%。

**Step 7｜页面栈方向性动画（效果③，首帧位移法）**
- 首页→详情：`DetailActivity.onCreate` 根 View `translationY=屏高` → `post { animate().translationY(0).setDuration(220) }`；返回反向：`DetailActivity.onBackPressed` 位移到底再 `finish()`。
- 与 `PlayerActivity` 现有 `fadeIn/fadeOut`（180/280ms 属性动画 + `postDelayed`）同构；**不碰 `overridePendingTransition`**（部分 TV 机型无动画），不碰 `configChanges`。
- 验证：进详情从底部 220ms 抬起、返回反向，无闪烁。

**Step 8｜全局背景升级（可选，视批次 1 实测效果）**
- 单 ImageView 简版若切图有"闪一下"，升级**双 ImageView 交叉**（A 显旧 / B 加载新 → `alpha` 0→1 200ms 交换角色，约 15 行）。
- 决策点：装机后由用户拍板是否需要。

**Step 9｜收尾与封存**
- `SuperMovLAMPA` 仓 readme 加「已废弃」横幅（1 commit）；本仓（`DJYDXS3NexioTMDB`）文档：把本操作文档 + 研究文档纳入 `docs/`，README 说明 UI 路线。
- 构建链：批次 1/2/3 各出一轮云端构建（`build.yml` 现成），APK 由用户装机验证。

## 4. 分支与提交策略

- 工作分支：新建 `DJYDXS3NexioTMDB-lampa-ui`（从 `origin/DJYDXS3NexioTMDB` 切出），**不直接推 `DJYDXS3NexioTMDB`**（保留数据层分支干净）；批次 1/2/3 各一 commit 一构建，合流由用户决定。
- 本地操作：`git fetch origin DJYDXS3NexioTMDB && git checkout -b DJYDXS3NexioTMDB-lampa-ui FETCH_HEAD`；本地当前 `DJYDXS3Nexio`(aa5e667) 工作区不动。
- 数据回填脚本（Step 1）只生成 db 产物，脚本与产物一起入仓（`tools/` + `assets/`）。
- 每次 commit 走现有六道质量门（precheck / verify_types / verify_symbols / verify_edits / verify_sql / 构建后 verify_apk），与 `DJYDXS-TV` 仓既有规范一致。

## 5. 风险与红线（执行时强制遵守）

1. **数据层只走版本迁移**：`SuperMOV.db` 加列后必须升 `SuperMOV.version` 并保证 `DbUpdater` 清单逻辑（三层版本取最大 + sha256）不断；直接改 schema 会让在线更新校验断（研究文档 §5 风险 4）。
2. **clipChildren 链路**：改 `item_movie` margin 时逐层复查 6 层 `clipChildren="false"`，漏一层描边被裁。
3. **ImageLoader LRU 是 OOM 红线**：背景图 w780 比海报大一个量级，不加 LRU/降采样前不得上背景层（研究文档 §5 风险 1）。
4. **页面动画只用首帧位移法**：不碰 `overridePendingTransition`/`windowContentTransitions`，保护 `configChanges` 锁定（研究文档 §5 风险 3）。
5. **盒子/真机操作等用户指示**（长期红线）：构建、commit、push、产物可自主；**装机、logcat、按键模拟一律由用户发起**。
6. 蓝本 §7 的"Compose 调样式"假设**不得**再作为任何实施依据（研究文档 §5 风险 5）。

## 6. 决策点清单（请用户逐条拍板）

| # | 决策 | 默认建议 |
|---|---|---|
| D1 | `SuperMovLAMPA` 仓处置 | 保留封存 + readme 横幅（不删仓不删代码） |
| D2 | UI 改造工作分支 | 新建 `DJYDXS3NexioTMDB-lampa-ui`（不动数据层分支） |
| D3 | 效果⑤"方案 B（顶部 feed 大卡行）"是否纳入本轮 | 纳入（批次 3 的 Step 6） |
| D4 | 效果⑤"方案 A（整页多行行化）" | 本轮不做，单独排期 |
| D5 | 页面动画 | 首帧位移法（研究文档方案 2） |
| D6 | 批次 1 背景层用简版（单 ImageView）还是直接双 ImageView 交叉 | 简版起步，实测后升级（Step 8） |
| D7 | backdrop 数据源 | 从 `SuperMOVTMDB.db` 回填 TMDB poster/backdrop 进 `SuperMOV.db`（唯一动数据层点） |
| D8 | 各批次构建后是否由用户装机验证再继续下一批次 | 是（每批次一个构建 + 用户验证 + 点头才进下一批） |

## 7. 每批次交付物

| 批次 | 步骤 | 交付 | 验证方式 |
|---|---|---|---|
| 1 | Step 1-3 | commit + 云端构建 APK | 用户装机：背景呼吸 + 白描边焦点 |
| 2 | Step 4-5 | commit + 云端构建 APK | 用户装机：海报淡入 + 详情页背景 |
| 3 | Step 6-7(+8) | commit + 云端构建 APK | 用户装机：feed 大卡 + 页面动画 |
| 收尾 | Step 9 | SuperMovLAMPA 封存横幅 + 本仓文档归位 | 目视 |

---
*本文档仅为操作计划。未创建任何分支、未修改任何文件、未触发任何构建。执行起点 = 用户对 §6 决策点拍板。*
