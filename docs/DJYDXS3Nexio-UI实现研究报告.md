# DJYDXS3Nexio（超级影库 / SupeMov）UI 实现方法研究报告

> 分析对象：`GamePCStudio/DJYDXS-TV` 分支 `DJYDXS3Nexio` @ `aa5e667`（v1.27）
> 分析范围：`app/src/main` 下全部 28 个 Java 类、10 个 layout、13 个 drawable、values 资源、AndroidManifest
> 报告日期：2026-09-19

---

## 一、结论先说

这套 UI 的实现可以概括为一句话：

> **「轻量 XML 骨架 + Java 代码动态填充 + 深度 TV 遥控器定制」的自绘式 Android TV UI，
> 刻意零第三方 UI 库、零 UI 框架，全部用原生控件手搓。**

三个关键判断：

1. **不是 Leanback 框架应用**。虽然 manifest 同时声明了 `LEANBACK_LAUNCHER` 和 `LAUNCHER`
   （手机/TV 双入口），但 UI 层完全没用 `Leanback` 库（无 BrowseFragment / RowsSupportFragment / GuidedStepFragment），
   就是一个普通 `Activity` + `RecyclerView` 手搭的遥控器交互。`leanback` feature 声明为 `required=false`，
   纯粹是为了让应用出现在电视桌面。
2. **「XML 定骨架、代码做血肉」的混合模式**。页面容器结构（ScrollView / RecyclerView / overlay 层级）
   写在 XML，但所有需要动态性的部分——设置页全部条目、列表条目内容、焦点反馈、对话框——都在 Java 里
   代码构建或改写。最典型的 `SettingsActivity`：XML 只有一页壳（标题 + 一个空 LinearLayout），
   20 多条设置项全部 `new TextView(this)` 动态生成。
3. **交互逻辑高度集中在两个 Activity**。`PlayerActivity`（78KB，全项目最大类）自绘了整套播控：
   关掉 media3 控制条，用 `dispatchKeyEvent` 接管遥控器，自己画快进/快退覆盖层、轨道选择器、
   自动隐藏的顶栏底栏。这是整个 UI 最复杂、也最有特色的部分。

---

## 二、技术选型与依赖

UI 相关依赖极简（`app/build.gradle`）：

| 依赖 | 版本 | 用途 |
|---|---|---|
| `androidx.appcompat:appcompat` | 1.6.1 | 唯一 UI 框架依赖，主题基座 `Theme.AppCompat.NoActionBar` |
| `androidx.recyclerview:recyclerview` | 1.3.2 | 全部列表：分类行、过滤器行、海报墙、下载队列 |
| `androidx.media3:media3-ui` | 1.10.0（fork 源码替换） | 仅用 `PlayerView` + `SubtitleView` 两个组件 |
| gson | 2.10.1 | 数据解析（非 UI） |

**没有**：Compose、ViewBinding/DataBinding、ButterKnife、Glide/Coil、Leanback 库、
ConstraintLayout、Material Components、Fragment 导航库、ViewPager。

连图片加载都是 73 行的手写 `ImageLoader`（见第八节）。这是刻意的工程取舍——
代码注释里写明了理由：引入 okhttp 会连带 kotlin-stdlib 使 APK 变大；这些选择让 APK 只有约 5MB。

### 页面构成（8 Activity + 1 Service）

```
GateActivity（授权门卫，LAUNCHER + LEANBACK_LAUNCHER 双入口）
   └─ MainActivity（首页：分类行 + 过滤器行 + 7 列海报墙）
        ├─ DetailActivity（详情：海报 + 简介 + 播放/下载/转存）
        │    ├─ PlayerActivity（播放器，singleTop）
        │    └─ DownloadActivity（下载队列）
        ├─ SettingsActivity（设置页，动态生成）
        │    ├─ QrActivity（百度扫码授权）
        │    └─ WebLoginActivity（论坛登录 WebView）
        └─（长按海报 → 转存确认 AlertDialog）
DlService（下载前台服务，通知栏进度，非 UI）
```

全部 Activity 均以 `configChanges` 锁定不重建（orientation/screenSize/keyboardHidden），
PlayerActivity 额外锁 `screenLayout|smallestScreenSize|uiMode` —— TV 上防止任何配置变化打断播放。

---

## 三、UI 构建方式：XML 骨架 + 代码填充

### 3.1 布局文件的角色

10 个 layout 的分工：

| 布局 | 角色 | 特点 |
|---|---|---|
| `activity_list` | 首页容器 | 三段式：分类行(横滚 RV + 搜索按钮) / 分隔线 / 过滤器行 + 海报墙(FrameLayout 内 RV + 居中空态) |
| `activity_detail` | 详情页 | ScrollView 包一行两栏（150×212dp 海报 + 文字区）+ 三个 TextView 伪装的按钮 |
| `activity_player` | 播放器 | 纯 FrameLayout 五层叠放（见第六节） |
| `activity_settings` | 设置页**壳** | 只有标题 TextView + 空 LinearLayout，内容全靠代码填充 |
| `activity_qr` | 扫码页 | 居中列：320dp 二维码 + 状态 + 帮助 + 重试按钮 |
| `activity_gate` | 授权门卫 | 居中列：标题 + 状态 + MAC + 两个按钮（默认 gone） |
| `activity_downloads` | 下载队列 | 标题 + 汇总 + 4 个批量按钮 + RV + 空态 |
| `item_movie` | 海报条目 | FrameLayout(PosterView + 渐变角标) + 标题 |
| `item_option` | 胶囊选项条目 | 根可聚焦 + 内部 TextView `duplicateParentState` |
| `item_download` | 下载条目 | 标题/状态/进度条/路径 + 三个小按钮 |

值得注意的模式：**按钮一律用 `TextView` + 背景 selector，不用 Button/ImageButton**。
好处是文字里可以直接放 emoji/符号（`▶ 在线播放`、`⬇ 下载`、`🔍 搜索`）当图标用，
省掉整套切图资源；整个项目只有 1 张位图（壁纸 `bg_wallpaper.webp`，16KB）+ 1 个 launcher 图标。

### 3.2 颜色与字号系统

没有主题级设计 token，但在代码与 XML 里实际遵守了一套约定：

| 角色 | 色值 | 出现位置 |
|---|---|---|
| 主色（蓝） | `#FF1E88E5`（浅态 `#FF42A5F5`） | 分类胶囊、按钮、进度条、节标题、高亮文字 |
| 背景基调 | 壁纸 + `#FF101418`（状态栏）/ `#FF1A1F26`（胶囊常态）/ `#FF22262B`（卡片底） | 深灰蓝系 |
| 正文 | `#FFFFFFFF`（标题）/ `#FFEEEEEE`（条目）/ `#FFC6CBD2`（次级） | 三级灰阶 |
| 弱化 | `#FF9AA0A6`（提示）/ `#FF6E747B`（帮助文字） | 空态、脚注 |
| 语义色 | `#FF4CAF50/#FF2E7D32`（下载绿）、`#FF78909C/#FF455A64`（转存灰蓝）、`#FFFF8A80`（危险红）、`#FFFFB74D`（门卫橙） | 按操作类型区分按钮颜色 |
| 覆盖层 | `#B3000000` 面板、`#99000000` 状态条、`#CC000000→#00000000` 角标渐变 | 半透明黑系 |

字号从 8sp（海报角标）到 30sp（门卫标题），常用档：10/10.5/13/15/16/20/24sp ——
明显是针对电视远距离观看手工调出来的，不是排版系统。

### 3.3 Drawable 体系：selector + shape 的教科书用法

13 个 drawable 全部是 XML 定义，零位图，三种模式：

1. **焦点态 selector**（`bg_action_play` 等 6 个）：`state_focused` → 提亮实底 + 2~3dp 白/蓝描边；
   默认 → 深色实底。这是 TV 上「看得见焦点在哪」的基本手段。
2. **layer-list 叠加**（`bg_movie_focus`）：实底 + 7dp 半透明光晕圈 + 3dp 实描边三层叠加，
   做出「发光」效果；`dl_progress` / `player_seek_bar` 用标准 background/secondary/progress 三层 clip 做进度条。
3. **纯 shape**：圆角卡片底、渐变角标衬底。

主题只有一个：`Theme.SupeMov`（parent `Theme.AppCompat.NoActionBar`），
三项配置——壁纸做窗口背景、`colorPrimary` 蓝、状态栏深色。没有夜间/日间分叉（本来就只有深色）。

---

## 四、TV 交互适配（本项目 UI 的核心）

### 4.1 焦点管理的四条铁律

代码注释里把这些坑写得很透，是多个真机版本迭代出来的：

1. **「焦点与点击必须落在同一个 View 上」**。`item_movie` / `item_option` 根布局显式
   `android:focusable="true"` + `clickable` + `descendantFocusability="blocksDescendants"`，
   内部 TextView 再显式 `focusable=false`。注释原话：Android 8.0(API 26) 之前没有 `FOCUSABLE_AUTO`
   （「可点击即自动可聚焦」），只把监听器挂容器上，7.1.2 机器上条目根本拿不到焦点。
2. **焦点丢失要有人接**。列表为空时 `tvEmpty` 自己 `focusable=true` 兜底拿焦点；
   「加载中」隐藏时若它正拿着焦点，立刻 `rvList.requestFocus()` 补位；
   后台探测摘条目可能正好摘掉聚焦海报，摘完检查 `rvList.findFocus()==null` 再补。
3. **对话框焦点主动落位**。所有确认型 AlertDialog 弹出后 `dlg.getButton(BUTTON_POSITIVE).requestFocus()`；
   详情页加载完成 `btnPlay.post(() -> btnPlay.requestFocus())`——「满屏静态文字看不出能按哪儿」。
4. **`clipChildren=false` 全链路**。首页布局从根到列表全部声明，让聚焦放大的海报可以溢出格子边界，
   这是海报墙聚焦动效（1.08 倍放大）不被裁剪的前提。

### 4.2 焦点视觉反馈

统一采用「属性动画三件套」，不用 Leanback 的聚焦高亮组件：

- 海报条目：`scaleX/scaleY 1→1.08` + `alpha 0.75→1` + 换背景 `bg_movie_normal→bg_movie_focus`（光晕描边）+ 标题变蓝
- 胶囊选项：`scale 1→1.14`（更大，因为元素小）
- 搜索按钮：`scale 1→1.1` + `alpha 0.85→1`
- 普通选项（设置页）：仅换背景色 `0x1A1F2600→0xFF2A323C`

### 4.3 遥控器按键协议

播放器把整套按键收进 `dispatchKeyEvent`，形成一份明确的协议：

| 键 | 行为 |
|---|---|
| 左 / 遥控快退键 | 快退 15s / 30s，中央覆盖层提示 |
| 右 / 遥控快进键 | 快进 20s / 30s |
| 按住左/右 >450ms | 升级为连续拖动（100ms/tick，>900ms 提速到 40ms/tick ≈ ×2.5），松手才真正 seek |
| OK / ENTER / 播放暂停键 | 播放/暂停切换 |
| 上 | 字幕轨选择器 |
| 下 | 音轨选择器 |
| 其它键 | 透传，顺带点亮顶栏底栏 3 秒 |
| 返回 | 退出播放 |

时间参数全部有注释交代为什么是这个值：连按合并窗口 1.2s、seek 延迟提交 500ms
（「否则每按一下都发一次 seek，LocalProxy 直链会被反复重连，画面疯狂缓冲」）、
拖满整片约 24s（提速后 10s）、chrome 隐藏 3s、覆盖层停留 2.5s。

### 4.4 自动隐藏 chrome

顶栏（文件名/状态）与底栏（按键帮助）在播放就绪后 3 秒淡出，动遥控器再亮。
实现是自写的一对 `fadeIn`/`fadeOut`（180ms/280ms 属性动画 + `postDelayed` 收尾），
不用 transition 框架。细节：`chromeArmable` 保证取流阶段的「正在定位影片…」不受自动隐藏影响；
STATE_READY 与每次 OK 暂停/播放后调 `showTitleBar()` 把顶栏复位成文件名，
避免「顶栏停在过期话术」。

---

## 五、播放器自绘播控（PlayerActivity，最复杂的一块）

### 5.1 视图层级（activity_player.xml）

```
FrameLayout（纯黑底）
 ├─ PlayerView（media3，useController=false）
 ├─ ProgressBar 居中转圈（缓冲指示）
 ├─ LinearLayout 中央覆盖层 boxPlayerSeek：提示文字 + 440dp 进度条 + 当前/总时长（默认 gone）
 ├─ TextView 顶栏 tvPlayerStatus（左上，半透明黑底）
 └─ TextView 底栏 tvPlayerHint（左下，整段遥控器操作说明）
```

关掉 `setUseController(false)` 的理由写在注释里：media3 自带控制条在 TV 上会和左右键抢事件
（方向键变成移动焦点而不是快进），关掉后按键行为完全自控，**交互对齐 VLC**。

### 5.2 轨道选择器（上/下键）

不用「按一下切一条」而弹列表选择，理由：轨道顺序由 TrackGroup 排列决定，用户不知道要按几下。
实现要点：
- 摊平 `getCurrentTracks()` 里该类型的受支持轨道为平行数组，列出名字 + `● 当前` 标记，字幕多一条「关闭字幕」；
- 缓冲中轨道表未解析时给出「正在解析轨道」而非误报「没有字幕」；
- **TV 对话框调优**：`tuneDialog()` 把窗口宽度放大 1.3 倍（上限屏宽 94%）、条目字号统一压到 13sp
  并挂 `OnHierarchyChangeListener` 处理回收复用的条目——「TV 上对话框默认又窄、字又大，一屏放不下几条轨道」。

### 5.3 字幕 UI

字幕渲染复用 `PlayerView` 内置的 `SubtitleView`（id `exo_subtitles`），不做自绘：
- 颜色/描边 → `CaptionStyleCompat` 四档（白字黑描边缺省 / 黄字黑描边 / 黑底白字 / 白字无描边）；
- 字号 → `setFractionalTextSize`（按屏高比例，缺省 6.6%，注释明确「电视上远看偏小」）；
- 位置 → `setBottomPaddingFraction` 三档映射底/中/顶，对 ASS 的 `\pos` 同样有效；
- 外挂字幕 → `MediaItem.SubtitleConfiguration`：手动指定路径优先，其次同目录同名（6 种扩展名，先精确后前缀模糊）。
- 注释里诚实标注了局限：全局样式会盖掉 ASS 逐行 `\c&Hxx` 颜色，想保留原色选「白字无描边」。

### 5.4 取流过程的 UI 叙事

顶栏 `status()` 把整条取流链路一步步讲给用户：「正在定位影片…→ 正在转存…→ 正在获取原画直链…→
原画播放：xxx → 原画播放中断，正在重新获取原画…」，失败用 `fail()`（✘ 前缀 + Toast 双通道）。
重试序号 `originSeq` 与额度计数 `originTries` 刻意分离，避免「播满 30 秒额度重置后文案倒退」的怪话——
UI 文案的正确性被当作状态机问题来对待。

---

## 六、列表与数据驱动的 UI 模式

### 6.1 首页海报墙的两段式加载

`loadPage()` 的设计（注释写明是性能重构的产物）：

1. **先出画**：列表解析完立即 `setItems` 画海报（约 1s 可见）；
2. **后筛选**：独立 `probePool` 逐帖探测百度链接，摘掉没有分享的条目。

配套的三个正确性细节：
- `loadGen` 请求代号：每次点击 +1，旧请求回来对不上号就丢弃（「最新一次点击说了算」），
  且不会把新点击整个丢掉（旧实现 `if (loading) return;` 会卡死）；
- `dropTids` 传「要删的黑名单」而非「要留的白名单」——翻页时 items 里有前几页条目，
  白名单会把上一页清空；逐条 `notifyItemRemoved` 保住电视焦点与滚动位置；
- 探测是原地改对象（补海报/角标），所以补一次 `refreshAll()`（`notifyItemRangeChanged`，不破坏焦点）。

无限翻页：滚动监听里 `last >= total - posterSpans * 2` 触发下一页（提前量 = 两行），
`total > 0` 防空列表连轴转。

### 6.2 列表组件三件套

- `PosterView`：19 行自定义 ImageView，`onMeasure` 强制宽:高 = 2:3——「杜绝回收复用后高度不一致」。
  海报墙 7 列 GridLayoutManager，条目宽度由布局算出，高度按比例锁死。
- `ImageLoader`：73 行零依赖图片加载——6 线程池 + `ConcurrentHashMap` 内存缓存 + `setTag(url)`
  防错位（回主线程后校验 tag 才设置图片）+ 失败自动重试一次（图床偶发抽风）+ 图床专用 Referer 头。
  没有磁盘缓存、没有占位图——对海报墙够用。
- `OptionAdapter`：一个适配器同时服务分类行和过滤器行（首页两个横滚列表），
  `Option.title/sub/highlight` 三字段 + `bg_cat` selector 表达「选中态（深蓝）/焦点态（蓝底白描边）」。

### 6.3 设置页的「rebuild() 模式」

`SettingsActivity` 没有用 PreferenceFragment，而是：

```
onCreate / onResume → rebuild()
    group.removeAllViews()
    按 分区标题(sectionLabel) + 条目(option) 顺序 addView
    每条 option = 标题行 + 换行 + 灰色小字说明（两行内容塞进同一个 TextView）
```

特点：
- **说明文字写得很长、很诚实**（每条 2~3 行），把取舍直接告诉用户（如「最大声道数」条目里写明
  v1.25 曾把缺省压到 6 导致 7.1 直通失败的教训）——设置页同时承担了文档职能；
- 修改后一律 `rebuild()` 整页重建（条目总量 <30，无性能压力），换取实现简单、状态永远与 Settings 一致；
- 选择交互全部用「列表对话框 + ●/○ 前缀标记当前项」，注释说明不用滑动条的原因：
  「电视上遥控器左右划不准，列表点选更省事」；
- 授权状态卡片异步校验（起线程查 `BaiduPan.sessionValid()` 后回主线程改文字）；
- 一次性 API 兼容处理内联在代码里：自声明 `IntPick` 接口替代 `IntConsumer`（后者 API 24 才有，minSdk 23）。

---

## 七、对话框的使用方式

全项目对话框只用系统 `AlertDialog`（主题统一 `Theme_DeviceDefault_Dialog`），四类用法：

| 用法 | 实例 |
|---|---|
| 文本输入 | 搜索、转存目录、下载目录手动输入、限速手动输入、手动字幕 |
| 单选列表 | 音频输出、声道数、语言、分辨率、帧率、解码器、DV、字幕模式/颜色/位置、字号 |
| 多选列表 | 直通编码勾选（即时落盘，不依赖「完成」键） |
| 确认/信息 | 转存确认、清除直通记录、连接诊断、音频诊断、权限引导 |

每个 AlertDialog 都做了 TV 调焦（确认键 `requestFocus()`）或调宽调字号（PlayerActivity 的轨道选择器）。

---

## 八、资源与主题组织

- **资源总量极小**：strings.xml 5 条（应用名等硬需求）、colors.xml 2 条（仅 launcher 图标用）——
  **界面文字全部硬编码在 Java/XML 里，没有做国际化**（单语中文 TV 应用，可接受但值得知晓）；
- 壁纸 `bg_wallpaper.webp`（16KB）作为 `windowBackground` 全局铺底，这是「深色界面统一感」的来源；
- 两个 launcher 入口共存：`LAUNCHER` + `LEANBACK_LAUNCHER` 挂在 GateActivity 上，
  `touchscreen`/`leanback` feature 均 `required=false`，同一 APK 手机/电视都能装。

---

## 九、踩坑沉淀（注释里固化的经验）

这份代码一个显著优点：**每个不直观的写法都附了根因注释**，相当于内置了踩坑数据库。
摘录最 UI 相关的：

1. 设置页 v1.25 曾没有 ScrollView：条目超屏后「焦点还能往下移，屏幕上永远看不到」→ 现在根布局
   ScrollView + `fillViewport` + 底部 72dp 余量，XML 注释完整记录事故。
2. API 26 前无 `FOCUSABLE_AUTO` → 条目根显式 focusable（针对 7.1.2 真机）。
3. 内部 TextView 抢焦点吞确认键 → `duplicateParentState` + 显式不可聚焦。
4. 空列表翻页死循环（`last==-1`）→ `total > 0` 判断。
5. 白名单删条目清掉前几页 → 改黑名单 `dropTids`。
6. seek 连按反复重连直链 → 500ms 延迟合并提交。
7. 对话框被吃 UP 事件导致拖动空转 → 弹轨道选择器前先 `endDrag`。
8. `Theme_DeviceDefault_Dialog` 在 TV 上窄且字大 → 调宽 + 缩字号。
9. 手动 `notifyDataSetChanged` 丢焦点 → 能用 `notifyItemRemoved/RangeChanged` 就不用全量刷新。
10. 焦点转移时机：`hadEmptyFocus`/`hadListFocus` 先记录再处理，隐藏/摘除后主动补位。

---

## 十、评价与改进建议

### 做得好的
- **复杂度守住了**：零 UI 框架却实现了完整的 TV 焦点/播控/动效体系，且 APK 仅 ~5MB；
- **焦点这个 TV 最大的坑被系统性处理**（兜底、补位、落位、防抖、保护），注释即文档；
- **设置页的 rebuild 模式**与状态一致性极好，说明文字承担用户教育，返工率低；
- 播控时间参数全部有量纲与理由，后人可调。

### 明显的短板
1. **界面文字硬编码**：所有中文文案散落在 Java/XML，strings.xml 只有 5 条——做任何多语言或文案
   批量调整都要翻全部源码；设置页长文案尤其难维护。
2. **无设计 token**：颜色 `#FF1E88E5` 等以字面量重复出现（XML 与 Java 各一份，如 `0xFF1E88E5`），
   改主题色要全局搜索替换；建议沉淀到 colors.xml 并用 `ContextCompat.getColor`。
3. **无 ViewBinding**：全项目手写 `findViewById`（PlayerActivity 一个类 9 个字段），新增控件易漏接。
4. **字号/边距无刻度系统**：10.5sp、13f、14dp 这类「调出来」的值散落各处。
5. **ImageLoader 无磁盘缓存**、无生命周期绑定（退出页面后下载的 bitmap 仍会入缓存），海报量大时
   内存可能压力上升（ConcurrentHashMap 无上限、无 LRU）。
6. **设置页 rebuild 重建整页**：条目再增多后焦点会回到页顶，长列表体验会劣化（当前规模尚可）。
7. `MainActivity` 的 `kwEquals()` 已无调用点（死代码）；DetailActivity 50KB 偏大，文件选择/下载/
   转存三块逻辑可拆。

### 若继续演进的建议优先级
1. 高：把 UI 文案抽进 strings.xml（哪怕只做集中管理不做多语言）；
2. 高：颜色收敛进 colors.xml（XML 与 Java 双处引用同一来源）；
3. 中：开启 ViewBinding 消灭 findViewById 字符串风险；
4. 中：ImageLoader 加 LRU 上限（`LruCache` 一行可得）；
5. 低：设置页条目多到一屏装不下时，考虑把「播放·音频/视频/字幕」拆成二级页，避免 rebuild 后焦点跳顶。

---

## 附录：关键文件索引

| 文件 | 大小 | UI 职能 |
|---|---|---|
| `PlayerActivity.java` | 78KB | 自绘播控、按键协议、轨道选择器、字幕样式、chrome 动效 |
| `DetailActivity.java` | 50KB | 详情页、文件选择对话框、下载/转存交互 |
| `SettingsActivity.java` | 40KB | 动态生成设置页、全部选择对话框 |
| `Settings.java` | 40KB | 全部偏好存储（UI 的状态源） |
| `MainActivity.java` | 22KB | 首页三列表、两段式加载、导航历史栈 |
| `DownloadActivity.java` / `DownloadAdapter.java` | 14/4KB | 下载队列 UI |
| `MovieAdapter.java` / `OptionAdapter.java` | 6/3KB | 海报墙/胶囊列表 |
| `PosterView.java` | 0.7KB | 2:3 比例自绘控件 |
| `ImageLoader.java` | 2.6KB | 零依赖图片加载 |
| `QrActivity.java` | 6KB | 扫码页（双线程池 + 世代号） |
| `GateActivity.java` / `WebLoginActivity.java` | 3/2KB | 授权门卫 / 论坛登录 WebView |
| `res/layout/*.xml`（10 个） | ~31KB | 页面骨架 |
| `res/drawable/*.xml`（13 个） | ~9KB | selector/shape/layer-list |
| `values/themes.xml` 等 | ~0.9KB | 单主题、5 条 strings |
