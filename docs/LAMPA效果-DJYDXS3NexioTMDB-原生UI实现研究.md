# LAMPA 效果在 DJYDXS3NexioTMDB 上的原生 UI 实现研究报告

> 目标：以 DJYDXS3Nexio 现有的「轻量 XML 骨架 + Java 代码动态填充 + 零 UI 框架」生成方式，
> 实现 LAMPA 蓝本中「海报页面」等部分的视觉效果。
>
> 依据：
> - `docs/DJYDXS3Nexio-UI实现研究报告.md`（现有 UI 生成方式，基准 aa5e667 @ v1.27）
> - `C:\py\lampa\LAMPA_UI_构成_重构蓝本.md`（LAMPA 官方 CSS/JS 提取效果）
> - `GamePCStudio/DJYDXS-TV` 分支 `DJYDXS3NexioTMDB`（v1.30，8 commit 领先 aa5e667，纯数据层改造）
>
> 性质：**纯研究，不动代码**。
> 报告日期：2026-09-19

---

## 0. 结论先说

1. **LAMPA 的 5 个标志性效果全部可以用现有原生方式实现**——纯 Java + XML 骨架 + 零新依赖，
   不需要引入 Compose / ViewBinding / 任何 UI 库。
2. **LAMPA 蓝本第 7 节（"Compose TV 映射"）的前提不成立，须废弃**：
   它假设 nexio 已有 `com.nexio.tv.ui.components` 的 Compose 组件
   （ContentCard / CatalogRowSection / HeroCarousel / SidebarNavigation / NexioTopBar / Skeletons 等）。
   实锤核实（见 §1）：本地 `DJYDXS-TV` 工程包名是 `com.supermov.tv`，27 个 Java 类、零 Kotlin、零 Compose；
   GitHub 8 个分支全部如此；`DJYDXS3NexioTMDB` 分支只动了数据层（`SuperMOV.db` 内嵌 +
   `MovieDb`/`MovieStore`/`DbUpdater`），**UI 代码与 `DJYDXS3Nexio` 完全一致**。
   → 蓝本中"调样式"的假设（"大半是调样式不是从零写"）不成立，实际是"在现有原生代码上按现有范式做增量"。
3. **优先级最高的两项是「全局背景交叉淡入」+「白描边焦点」**，二者合在一起就是 LAMPA
   "呼吸感"的全部核心。改动面最小（各 ~30–50 行），收益最大（页面从"死板"变"活"）。
4. 唯一需要动数据层的是效果①的前置：`SuperMOV.db` 补 `backdrop` 横图字段（且要按库版本迁移
   流程加列，不能直接改 schema，否则 `DbUpdater` 的 sha256 校验会断——见 §5 风险 4）。
5. 首页结构对齐（LAMPA 多行横向海报）成本最高：可先做"方案 B"（保留 7 列网格 + 顶部加一行
   feed 大卡）达到 ~70% 视觉相似度，方案 A（整页行化）单独排期。

---

## 1. 分支事实核实（纠正蓝本前提）

### 1.1 `DJYDXS3NexioTMDB` 相对 `DJYDXS3Nexio`(aa5e667) 的 diff

领先 8 commit / 0 落后，改 24 个文件，全部是数据层与构建：

| 类别 | 文件 |
|---|---|
| 新增数据层 | `MovieDb.java` / `MovieStore.java` / `DbUpdater.java` / `PanRename.java` |
| 新增资源 | `assets/SuperMOV.db` + `assets/SuperMOV.version`（内嵌库 + 版本清单） |
| 删除 | `Site.java`（论坛抓取）、`WebLoginActivity.java` |
| 修改 | `BaiduPan` / `CookieStore` / `Http` / `ImageLoader` / `MainActivity` / `MovieAdapter` / `DetailActivity` / `PlayerActivity` / `SettingsActivity` / `AndroidManifest` / `app/build.gradle` / `build.yml` |
| 新增文档 | `DJYDXS3Nexio-数据库重构方案.md`、`-二期.md`、`-元数据链路与库结构.md`、`-三期-转存改造与置顶测试库.md` |

commit 轨迹：`v1.28 内容源改内嵌库` → `v1.28 修 MovieStore 编译错` → `v1.29 分类栏固定四栏目白名单`
→ `v1.30 置顶机制 + 转存建片名文件夹 + 空间不足判定`（+ 3 个 docs 修正）。

### 1.2 UI 生成方式在两个分支间**没有任何变化**

- `MainActivity` 仍是：分类横滚胶囊行 + 过滤器横滚行 + `GridLayoutManager(7)` 海报墙 + 两段式加载；
- `MovieAdapter` 仍是：`item_movie` 骨架 + 焦点 `scale 1.08 + 换背景 + 标题变色`；
- `ImageLoader` 仍是：73 行零依赖（6 线程池 + `ConcurrentHashMap` 内存缓存 + tag 防错位）；
- 无 Compose、无 ViewBinding、无 Leanback、无 Fragment。

→ **本报告的落地基准 = 现有原生 Java 首页，而非蓝本第 7 节的 Compose 组件树。**

---

## 2. LAMPA 5 效果 → 现有 UI 生成方式 逐项映射

### 效果① 全局背景交叉淡入层（★最核心）

**LAMPA 原样**：`position:fixed` 全屏背景，`opacity:0.5`，焦点移到哪张卡就把该片的
backdrop（w780/w1280）画上去，旧图淡出 / 新图淡入（0.2s），叠在 `#1d1f20` 深灰上。

**现有方式映射**（纯原生）：

| 环节 | 现有机制 | 需补 |
|---|---|---|
| 全屏背景层 | 现在只有主题 `windowBackground = bg_wallpaper.webp`（16KB 静态壁纸） | `activity_list.xml` 根布局最底层加 1 个 `ImageView`（match_parent，`alpha=0.5f`），置于 RecyclerView 之下 |
| 图源 | `ImageLoader` 已能加载任意 URL，tag 防错位现成 | 焦点切换时 `ImageLoader.load(backdropUrl, bgView)`，直接复用 |
| 交叉淡入 | 现无过渡（到位即 `setBitmap`） | 双 ImageView 交叉（A 显旧图 / B 加载新图 → `alpha` 属性动画 0→1，200ms 后交换角色）；或更简：单 ImageView + `ViewPropertyAnimator.alpha(0f).setDuration(100)` → setBitmap → `alpha(0.5f)`（约 15 行） |
| 焦点联动 | `MovieAdapter.onBindViewHolder` 已有 `setOnFocusChangeListener` | 监听器里加 1 行回调到 Activity，把聚焦条目的 backdrop 推给背景层 |

**数据前置**：`SuperMOV.db` 需补 `backdrop` 列（TMDB `backdrop_path`），`ImageLoader` 前拼
`https://image.tmdb.org/t/p/w780/{backdrop_path}`。这是**唯一需要动数据层**的点。

**可行性**：★最优先。`ImageLoader` + `setOnFocusChangeListener` 两条现成机制拼起来即可。

---

### 效果② 焦点白描边圈（★必做，替代现有 scale 缩放）

**LAMPA 原样**：`.card.focus .card__view::after` —— 外扩 0.5em、0.3em 白色圆角描边、
`border-radius:1.4em`，**不缩放、不位移、不挤布局**，带 `animation-card-focus` 入场。

**现有焦点反馈**（要替换的）：`item_movie` 的 `scale 1→1.08` + `alpha 0.75→1` +
背景换 `bg_movie_focus`（layer-list 三层：实底 + 7dp 半透明光晕 + 3dp 蓝描边 `#FF42A5F5`）。
蓝本第 6 节原话："把焦点做成了 scale(1.085)，一缩放整行卡片跳动，正是廉价感的直接来源"。

**改造点**（纯 XML + 监听器）：

1. 新 drawable `bg_poster_lampa_focus.xml`（layer-list，复刻 `::after` 语义）：
   - 实底层 `#FF22262B` 圆角（保留）；
   - **外扩描边**：layer-list 的负 inset 不能真正画到 View 自身边界外，正解是
     `item_movie.xml` 的 FrameLayout 四周 margin 从 4dp 改 6dp，描边画在这个空隙里
     （视觉外扩 0.5em，不动兄弟布局）；
   - 描边色 `#FFFFFFFF`（LAMPA 白）替换 `#FF42A5F5`（蓝），宽 3dp（≈0.3em，10sp 基准），
     圆角 8dp（≈1.4em）。
2. `MovieAdapter` 焦点监听器：删 `setScaleX/Y`，保留换背景 + 标题变色；入场用
   `ViewPropertyAnimator.alpha(0→1, 150ms)` 或瞬切。
3. **保留 `clipChildren=false` 全链路**——描边外扩同样依赖它，改 margin 时逐层复查。

**可行性**：★必做、工作量最小（1 drawable + 1 段监听器 + 1 处 margin，约 30 行）。
与效果①合起来 = LAMPA"呼吸感"的全部两半。

---

### 效果③ 页面栈切换方向性动画

**LAMPA 原样**：页面带 `animation-activity`（水平滑入）/ `animation-from-below`（底部抬起）
方向动画，返回反向。

**现有方式**：8 个 Activity 裸 `startActivity` / `finish()`，默认转场 TV 上基本无感。

**三选一（均不引入新依赖）**：

1. `overridePendingTransition`：`startActivity` 后 + `finish()` 后各调一次，4 个 5 行 XML
   动画资源（`slide_in_right`/`hold`、`slide_in_bottom` 等）。首页→详情走 from-below。
2. **首帧位移（推荐）**：`onCreate` 后根 View `translationY = 屏高` → 属性动画回 0（200~250ms）；
   返回反向再 `finish`。与 PlayerActivity 现有 `fadeIn/fadeOut`（180/280ms 属性动画 + postDelayed）
   同构，不碰系统窗口转场，**不破坏 `configChanges` 锁定**，符合"自写动效、不用 transition 框架"基调。
3. `windowContentTransitions`（场景转场）：更官方但 TV 上表现不稳定，**不推荐**（与"一切自控"相悖）。

**可行性**：方案 2 最稳；方案 1 在部分 TV 机型可能"无动画"，需真机验证。

---

### 效果④ 首页结构对齐（feed 大条目 + 多行海报 + 更多卡）

**LAMPA 原样**：首页第一屏 = 信息流大卡（`feed-item`：标题 + 简介 + 操作按钮 + 右侧海报缩略图），
下面是若干横向海报行（`items-line`，行尾"更多"占位卡）。

**现状差异最大**：当前首页是"两段式"——顶部分类/过滤器两行横滚胶囊 + 下方一个
**7 列网格海报墙**（`GridLayoutManager(7)` + 无限翻页）。LAMPA 是"多行"——纵向列表、
每行一个横向滚动海报行。

两种落地（均在现有 XML+RecyclerView 范式内）：

- **方案 A（行化，贴近 LAMPA，工作量最大）**：
  - `activity_list.xml` 海报区从单 `RecyclerView(Grid,7)` 改 `RecyclerView(LinearLayout,纵向)`，
    每个 item 内一个横向 `RecyclerView`（行 = items-line）；
  - 数据按栏目分组（热门/新片/继续观看/收藏/feed）——TMDB 分支 v1.29 已改"固定四栏目白名单"，
    数据侧基本够用，缺 feed 条目与"更多卡"两种新 ViewType；
  - `MovieAdapter` 扩 3 种 viewType（feed-item 大卡 / 海报行 / 行尾"更多"卡），复用
    `PosterView`（2:3 锁比例）+ `ImageLoader`。
- **方案 B（保留网格墙，只补视觉层，改动小，推荐起步）**：
  - 首页维持 7 列网格不变，顶部加一个 **feed-item 横滚行**（复用现有 `OptionAdapter` 的横滚
    RecyclerView 模式，item 换成"大卡"布局）+ 全局背景层 + 白描边焦点；
  - 视觉"像 LAMPA"约 70%（背景呼吸 + 焦点质感 + 第一屏大卡），浏览结构仍是网格。

**可行性**：目标若只是"部分效果（如海报页面）"，**建议方案 B 起步**——feed 大卡行一行横滚即可
复刻 LAMPA 首页第一屏记忆点（1 个 item 布局 + 1 个 adapter）；方案 A 作为后续整页重构单独排期。

---

### 效果⑤ 渐进式图片呈现（占位灰底 → 加载完淡入 → 详情页大图 mask 渐变）

**现有方式映射**：

- **占位灰底**：`item_movie` 的 `ivPic` 已有 `background="@drawable/bg_movie_normal"`
  （`#FF22262B` 圆角实底），等价 LAMPA 的 `#3E3E3E`，调色值即可（**已具备**）；
- **加载完淡入**：`ImageLoader` 现到位即 `setImageBitmap`（瞬切），加 ~10 行：
  初始 `alpha=0`（占位灰底持续显示）→ 到位后 `iv.animate().alpha(1f).setDuration(200)`；
- **骨架脉冲**：现无。LAMPA `mediaLoadingPulse` 用 `ValueAnimator` 对 `tvEmpty`
  （"加载中…"占位 View）做 alpha 0.4↔1 循环替代，约 8 行；
- **详情页大图 mask 渐变**：`activity_detail.xml` 现"一行两栏"（150×212dp 海报 + 文字），
  无背景大图层。照效果①同构：加全屏 ImageView（backdrop w1280）+ `GradientDrawable`
  （水平 linear `#00000000→#FF1d1f20`）叠上做向左淡出遮罩；`.loaded` 后 1 次 200ms 淡入。
  **与效果①同构，复用同一套背景层组件。**

**可行性**：全是现有机制内小增量；详情页项与效果①共用代码。

---

## 3. 实施优先级（原生 Java 基准修正版）

| 顺序 | 项目 | 改动面 | 工作量 | 前置 |
|---|---|---|---|---|
| 1 | 全局背景交叉淡入层（①） | `activity_list.xml` +1 ImageView、`MovieAdapter` 焦点监听 +1 行、`ImageLoader` 加 LRU/inSampleSize | 小（~50 行） | **数据层补 `backdrop` 列** |
| 2 | 白描边焦点（②） | 新 drawable + 删 scale + margin 微调 | 最小（~30 行） | 无 |
| 3 | 渐进淡入 + 骨架脉冲（⑤前） | `ImageLoader` + `tvEmpty` 动画 | 小（~20 行） | 无 |
| 4 | 详情页背景大图 + 遮罩 + 评分徽章（⑤后） | `activity_detail.xml` + `DetailActivity` | 中 | 依赖 1 的背景层组件 |
| 5 | 首页 feed 大卡行（④方案 B） | 新 item 布局 + 新 adapter | 中 | 依赖 `SuperMOV.db` 栏目分组 |
| 6 | 页面栈方向性动画（③） | 4 个 anim 资源 + 各 Activity 钩子（或首帧位移） | 中 | 无 |
| 7 | （可选）多行横向海报墙（④方案 A） | `activity_list` 结构改 + `MovieAdapter` 扩 3 viewType | 大 | 依赖 5 的行结构 |

**全部落在"XML 骨架 + Java 填充 + 零第三方依赖"现有范式内**；唯一数据层改动 = `backdrop` 字段
（效果①前置）。

---

## 4. 关键代码锚点（后续动手时直接改这些位置）

| 效果 | 文件 | 位置 | 现状 → 目标 |
|---|---|---|---|
| ① | `res/layout/activity_list.xml` | 根 LinearLayout 最底层 | 加 1 个全屏 `ImageView`（alpha 0.5f） |
| ① | `MovieAdapter.onBindViewHolder` | `setOnFocusChangeListener`（现 L125–131） | 加 1 行回调聚焦条目的 backdrop |
| ① | `ImageLoader` | `CACHE`（L19，无上限 `ConcurrentHashMap`） | 加 `LruCache` 上限 + 背景图 `inSampleSize` 降采样 |
| ①⑤ | 数据层 `MovieDb`/`MovieStore` | `SuperMOV.db` | 补 `backdrop` 列（**按库版本迁移加列，勿直接改 schema**） |
| ② | `res/drawable/bg_movie_focus.xml` | 三层 layer-list（现描边 `#FF42A5F5` 3dp） | 描边改 `#FFFFFFFF`、圆角 8dp；外扩靠 margin 空隙 |
| ② | `res/layout/item_movie.xml` | FrameLayout `margin=4dp`（L13） | 改 6dp（描边外扩 0.5em 的空隙） |
| ② | `MovieAdapter` 焦点监听 | `setScaleX/Y`（L126–127） | 删除，保留换背景 + 标题变色 |
| ③ | 各 Activity | `startActivity`/`finish` 钩子 | 首帧位移（`translationY`）或 `overridePendingTransition` |
| ④ | `res/layout/activity_list.xml` | 顶部 | 加 1 个 feed-item 横滚 `RecyclerView`（方案 B） |
| ⑤ | `ImageLoader.load` | 到位即 set（L31–41） | 初始 alpha 0 → `animate().alpha(1,200)` |
| ⑤ | `tvEmpty`（`activity_list.xml` L73–82） | "加载中…" 占位 | `ValueAnimator` 脉冲 alpha 0.4↔1 |
| ⑤ | `res/layout/activity_detail.xml` + `DetailActivity` | 一行两栏 | 加全屏 backdrop + `GradientDrawable` 水平遮罩 |

---

## 5. 风险与注意点（不动手前提下的研究判断）

1. **效果①对 `ImageLoader` 是最大单点**：背景横图（w780）远大于海报（w342 级），
   无 LRU 的 `ConcurrentHashMap` 在焦点快速左右移动时会累积 OOM。
   必须配套 `LruCache` + `inSampleSize`（UI 报告"改进建议第 4 条"也指向这里）。
2. **焦点外扩描边依赖 `clipChildren=false` 完整性**：改 margin 时若某层漏声明，描边会被格子边界裁掉。
   现有首页布局"从根到列表全部声明"的链路要逐层复查。
3. **页面栈动画与 `configChanges` 锁定共存**：方案 2（首帧位移）不碰窗口转场，安全；
   方案 1（`overridePendingTransition`）在部分 TV 机型表现为"无动画"，需真机验证。
4. **`SuperMOV.db` 是 assets 内嵌 + `DbUpdater` 在线增量更新**（三层版本号取最大 + JSON 清单 +
   sha256 校验）：补 `backdrop` 列必须走**版本迁移**（加列后升版本号、重算清单），
   直接改 schema 会让在线更新校验断掉（`apk-embedded-sqlite-db` 经验）。
5. **蓝本第 7 节整体作废**：后续所有实施讨论以本报告 §2–§4 的"原生 Java 基准"为准，
   不要再按"Compose 组件调样式"的假设推进。

---

## 6. 附：LAMPA 效果与现有机制对应总表

| LAMPA 概念 | 现有原生机制 | 增量 |
|---|---|---|
| 全局背景画布（★） | 主题 `windowBackground` 静态壁纸 + `ImageLoader` | 加全屏动态 ImageView + 交叉淡入 |
| 卡片白描边焦点（★） | `MovieAdapter` 焦点监听 + `bg_movie_focus` layer-list | 改描边色/半径 + margin 外扩 + 删 scale |
| 卡片 2:3 比例 | `PosterView`（19 行，`onMeasure` 锁 2:3） | 无（已具备） |
| 占位灰底 | `ivPic` 的 `bg_movie_normal` | 调色值 |
| 加载淡入 / 骨架脉冲 | 无 | `ViewPropertyAnimator` / `ValueAnimator` 各 ~10 行 |
| 详情页背景大图 + 遮罩 | 无 | 与效果①同构，复用背景层组件 |
| feed 大条目 | `OptionAdapter` 横滚模式 | 新增 1 个 item 布局 + adapter |
| 页面栈方向动画 | 自写 `fadeIn/fadeOut` 属性动画（PlayerActivity） | 复用同风格，首帧位移 |
| 遥控焦点引擎 | `dispatchKeyEvent` 接管 + `clipChildren=false` + 焦点兜底补位 | 无（已具备，TV 焦点四铁律见 UI 报告 §4.1） |

**总原则**（LAMPA 官方 298 处 transition 的提炼）：缓动无处不在（0.15s~0.35s），
但**位移量都很小**（描边外扩 0.5em、卡片不缩放）——"克制"是 LAMPA 质感的来源，
不要做 LAMPA 没做的放大/位移效果。
