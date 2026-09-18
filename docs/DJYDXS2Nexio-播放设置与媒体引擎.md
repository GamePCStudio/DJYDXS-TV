# DJYDXS2Nexio · 媒体引擎与播放设置说明

- 分支：`GamePCStudio/DJYDXS-TV` → **`DJYDXS2Nexio`**（由 `main` `311881e4` 派生，不触碰 `main`）
- 引擎：`johnneerdael/media`（`androidx/media` 的 fork，NEXIO 专用）→ **media3 1.10.0 基线**
- 接入方式：**方案 A —— 源码 composite build**
- 编写日期：2026-09-18

---

## 一、这次改了什么

### 1. 构建侧（方案 A）

| 文件 | 改动 |
|---|---|
| `.gitmodules`（新增） | 声明子模块 `media` → `https://github.com/johnneerdael/media.git`（branch main） |
| `media`（新增 gitlink） | 指向 `e9297c15f39947c1ae8972fa191cbd64914e81cb`（fork `main` 当前 tip） |
| `settings.gradle` | 新增 `includeBuild('media')` + 9 条 `dependencySubstitution`；缺失子模块时给出可照抄的提示 |
| `app/build.gradle` | `def media3Version` `1.11.0` → `1.10.0`（**坐标名一个没改**） |
| `build.gradle` | AGP `8.9.1` → `8.13.2`（对齐 fork） |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle `8.11.1` → `8.13`（AGP 8.13.2 的硬性下限） |
| `gradle.properties` | `-Xmx2048m` → `-Xmx5120m`，新增 `kotlin.daemon.jvmargs`、`org.gradle.caching` |
| `.github/workflows/build.yml` | 触发分支加 `DJYDXS2Nexio`；按 **pin 住的 sha** 单 commit 浅拉取子模块；artifact 名带分支名；`timeout-minutes: 90` |

**app 侧依赖写法完全没变**，仍是：

```groovy
def media3Version = "1.10.0"
implementation "androidx.media3:media3-exoplayer:$media3Version"
implementation "androidx.media3:media3-exoplayer-hls:$media3Version"
implementation "androidx.media3:media3-ui:$media3Version"
```

`dependencySubstitution` 会在解析期把它们换成 fork 的 `:lib-exoplayer` / `:lib-exoplayer-hls` / `:lib-ui`
源码工程。这也是 nexio 官方默认的接法（`USE_MEDIA3_SOURCE=true`）。

### 2. 设置侧（本次新增）

新增 `app/src/main/java/com/supermov/tv/PlaybackEngine.java`，把设置翻译成 media3 构造参数；
`Settings.java` 存值、`SettingsActivity.java` 出界面、`PlayerActivity.java` 消费。

---

## 二、新增设置项

### 播放 · 音频

| 设置项 | 取值 | 缺省 | 说明 |
|---|---|---|---|
| **音频输出** | 自动 / **源码直通** / 强制解码 PCM | 自动 | 直通 = 位流原样送 HDMI 由功放解；解码 = 盒子自己解成 PCM |
| **源码直通编码** | AC-3 / E-AC-3 / E-AC-3 JOC / DTS / DTS-HD / TrueHD / AC-4（多选） | AC-3·E-AC-3·JOC·DTS | 只在「源码直通」模式下生效 |
| **最大声道数** | 2 / 6 / 8 | 8 | 按功放实际声道数选 |
| **直通失败自动回退** | 开 / 关 | 开 | 直通建不出音频轨时自动换解码重播 |
| **首选音轨语言** | 不指定 / 中文 / 英语 / 日语 / 粤语 | 不指定 | 多音轨时优先挑该语种 |

### 播放 · 视频

| 设置项 | 取值 | 缺省 | 说明 |
|---|---|---|---|
| **画面比例** | 适应屏幕 / 拉伸填满 / 裁剪填满 | 适应屏幕 | 映射到 `AspectRatioFrameLayout` 的 `RESIZE_MODE_FIT / FILL / ZOOM` |
| **在线清晰度上限** | 原画优先 / ≤1080p / ≤720p | 原画优先 | 选转码档会跳过原画直链，直接走 `M3U8_AUTO_1080/720` |
| **硬解失败回退软解** | 开 / 关 | 开 | `DefaultRenderersFactory.setEnableDecoderFallback` |
| **字幕字号** | 小 / 中 / 大 / 特大 | 大 | 占屏高 0.040 / 0.0533 / 0.066 / 0.080 |
| **记住播放进度** | 开 / 关 | 开 | 关掉后不再自动续播（已有记录不删） |

---

## 三、音频直通的实现要点（踩过的坑，别改回去）

media3 判断「这条压缩音轨能不能直通」的唯一依据是 sink 手里
`AudioCapabilities.supportsEncoding(int)` 的返回值。国产盒子 / TV 的 Audio HAL 大量**不上报**
环绕声编码 —— 不干预时 `supportsEncoding(ENCODING_AC3)` 就是 `false`，sink 于是走 PCM 模式
把 5.1 **静默降混成 2.0**，不报错也不抛异常。所以要看功放点亮 DD/DTS，必须把能力表改掉。

**关键陷阱（1.10.0 与老版本不同）**：`DefaultAudioSink.Builder.build()` 里写的是

```java
new AudioTrackAudioOutputProvider.Builder(context)
    .setAudioCapabilities(context != null ? null : audioCapabilities)
```

也就是说 —— **只要构造 Builder 时给了 Context，自定义能力表就被丢掉**，改用
`AudioCapabilitiesReceiver` 读到的设备真实能力表；而
`AudioTrackAudioOutputProvider.Builder.setAudioCapabilities` 本身是 package-private，
外部也塞不进去。

**结论：唯一还能自定义能力表的入口，就是已 deprecated 的无参 `new DefaultAudioSink.Builder()`。**
`PlaybackEngine.EngineRenderersFactory#buildAudioSink` 就是按这个走的，并挂了
`@SuppressWarnings("deprecation")`。网上流传的「带 Context 的 Builder + setAudioCapabilities」
在这个版本上是死代码。

另外两条：

- `AudioAttributes` 的 `contentType` **必须设成 `C.CONTENT_TYPE_MOVIE`**（设成 MUSIC/SPEECH 时
  部分 HAL 直接拒绝建直通轨）。
- 无 Context 时 `AudioCapabilitiesReceiver` 不会创建，因此 **HDMI 热插拔不会自动刷新能力表**。
  直通模式下这反而符合预期（能力表要按用户设置固定住），换线后重进播放页即可。

### 直通失败的回退链

`ERROR_CODE_AUDIO_TRACK_*` 抛出 → `rebuildWithDecode()` 把整个 player 换掉（能力表是建 sink
时的快照，改不掉），按 `curUrl` 回到原位置用 PCM 解码重播。用户只看到一句
「音频直通失败，已自动改用解码输出重播」，不会一播就崩。

---

## 四、风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| Gradle 8.13 + AGP 8.13.2 | 可能引入本工程自身的编译差异 | 单独一次 commit 只做升级 |
| composite build 首次编译 media3 源码 | CI 时间 / 内存上升 | `-Xmx5120m` + Gradle 缓存 + `timeout-minutes: 90` |
| 子模块「单 commit 浅拉取」依赖 GitHub 支持按 sha fetch | 拉取失败则构建中止 | 步骤里 `ls media/settings.gradle` 做 fail-fast |
| 1.10.0 相对 1.11.0 的修复缺失 | 潜在回归 | 重点回归：网盘**原画**播放、**M3U8 降级**、字幕/音轨切换、续播 |
| 强制直通在真不支持的设备上建轨失败 | 无声 / 报错 | 缺省开启自动回退；也可把「音频输出」改回「自动」 |
| 本地 `git status` 会显示 `D media` | 容易误删子模块 | **不要用 `git commit -a` / `git add -A`**；先 `git submodule update --init --depth 1 media` |

**回滚**：本方案不触碰 `main`。删掉该分支即可：
`git push origin --delete DJYDXS2Nexio`，或把分支 reset 回 `311881e4`。

---

## 五、本地怎么跑

```bash
git fetch origin DJYDXS2Nexio
git checkout -b DJYDXS2Nexio origin/DJYDXS2Nexio

# 子模块（约 1 个 commit 的浅拉取，仍有一定体积）
git submodule update --init --depth 1 media

# 验证替换是否生效：media3-exoplayer 应解析成 project :lib-exoplayer
./gradlew :app:dependencies --configuration releaseRuntimeClasspath

./gradlew assembleRelease
```

CI：推送到 `DJYDXS2Nexio` 会自动触发（`build.yml` 已加该分支），也可手动
`gh workflow run build.yml --ref DJYDXS2Nexio -R GamePCStudio/DJYDXS-TV`。

---

## 六、验收清单

- [x] CI 里 `Fetch media3 fork submodule` 步骤通过，打印出 fork URL + sha
- [x] `assembleRelease` 成功产出 APK（run 35326634505，BUILD SUCCESSFUL in 2m 40s）
- [x] 产物中确认走的是 **fork 源码**而非 Maven AAR（见第八节 dex 取证）
- [ ] 设置页出现「播放 · 音频」「播放 · 视频」两个分区，所有项可点、可存、重进仍在
- [ ] 音频输出 = 自动：与改前行为一致（不回归）
- [ ] 音频输出 = 源码直通：接功放时面板显示 DD / DD+ / DTS（不是 PCM / STEREO）
- [ ] 音频输出 = 强制解码：功放显示 PCM，画面声音正常
- [ ] 直通失败（勾 TrueHD 等不支持项）→ 自动回退，能出声
- [ ] 字幕字号 / 画面比例改动即时生效（重进播放页）
- [ ] 原画播放、M3U8 降级、字幕轨/音轨切换、续播进度均正常

---

## 七、后续可选（本次未做）

fork 的真正「杀手锏」都还没接，属于第二步增量：

1. **Kodi 原生 IEC61937 直通**（`lib-exoplayer-kodi-cpp-audiosink`）——
   真正的 DTS-HD / TrueHD / DTS:X 直通，但需要 NDK/CMake 编译 native 模块，
   且官方文档明确标注**实验性、无静默回退**。本次先用标准 `DefaultAudioSink` + 能力表伪造，
   覆盖 AC-3 / E-AC-3 / JOC / DTS 这几类高命中率格式。
2. **Dolby Vision 7 → 8.1 实时转换**（`libdovi` + 提取器钩子）。
3. **FFmpeg 软解**（`lib-decoder-ffmpeg`：VC-1 / AV1 兜底）。
4. **HDMI 路由变化重建 Player**（`AudioManager.registerAudioDeviceCallback` +
   `ACTION_HDMI_AUDIO_PLUG`），解决「先开盒子后开功放导致降混」的经典问题。

---

## 八、构建验证（2026-09-18 实测）

### 8.1 先修了一个「假成功」的构建隐患

第一次推送后 CI（run 35325474634）失败。根因**不在** Gradle/AGP 版本号写错，而在仓库自带的
`gradlew` 是个自定义 shim：

```sh
# 旧 gradlew（问题版）
APP_HOME=$(cd "$(dirname "$0")/.." && pwd)      # ← 多了一层 ..，目录定位本身就是错的
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"   # ← 无 Main-Class，也会失败
else
  exec gradle "$@"                               # ← jar 从未提交，于是静默用了 runner 预装的 gradle
fi
```

- `gradle/wrapper/` 下**只有 `.properties`，`gradle-wrapper.jar` 从来没进过仓库**；
- 于是走 `else` 分支 `exec gradle`，静默使用 ubuntu-latest 预装的 **Gradle 9.7.1**；
- 而 **AGP 8.13.2 在 Gradle ≥ 9.6 上会因 `InternalProblems` 内部 API 被移除而失败**
  （AGP 8.13+ 尚未适配 Gradle 9.6+）。main 分支此前用 AGP 8.9.1 不碰这个 API，
  所以在同一个 runner 上「同样没有 wrapper jar」却一直是绿的 —— 这就是它一直没被发现的原因。

修复（commit `28d4e8f`）：

1. 提交官方 `gradle-wrapper.jar`（Gradle 8.13.0，43705 字节）；
2. `gradlew` / `gradlew.bat` 改为调用 `org.gradle.wrapper.GradleWrapperMain`，
   并且用 **`-classpath`** 而不是 `java -jar`（官方 wrapper jar 的 MANIFEST **没有 `Main-Class`**，
   `java -jar` 必然报 "no main manifest attribute"）；
3. 修正 `APP_HOME`（sh 版去掉多余的 `..`；bat 版用 `%~dp0` 去掉结尾反斜杠）；
4. 保留「jar 缺失时回退系统 gradle」的兜底分支，但只作为最后手段。

交叉验证：fork 自己的 `gradle/wrapper/gradle-wrapper.properties` 用的是
**`gradle-8.13-all.zip`**，其 `build.gradle` 用 **AGP 8.13.2 + Kotlin 2.3.0** ——
与本次选定的组合完全一致，是 fork 官方验证过的搭配。

### 8.2 本次构建结果（run 35326634505）

| 项目 | 结果 |
| --- | --- |
| 实际使用 Gradle | **8.13**（日志：`Downloading gradle-8.13-bin.zip` / `Welcome to Gradle 8.13!`） |
| 结果 | `BUILD SUCCESSFUL in 2m 40s`，360 actionable tasks（305 executed / 55 from cache） |
| 错误 | 0（只有 `Note: ... deprecated API`，来自 `AudioCapabilities` 兼容构造等预期项） |
| 产物 | `DJYDXS-TV-release-apk-DJYDXS2Nexio.zip` → `app-release.apk`，5,596,783 字节，3 个 dex，无 native so |
| 手动触发 | `gh workflow run build.yml --ref DJYDXS2Nexio -R GamePCStudio/DJYDXS-TV` |

### 8.3 参与源码编译的 fork 模块（证明 composite 替换真的生效）

`:media:` 前缀下实际执行了编译的模块共 9 个，与 `settings.gradle` 的 `dependencySubstitution`
清单一一对应：

```
lib-common  lib-container  lib-datasource  lib-decoder  lib-extractor
lib-database  lib-exoplayer(+compileReleaseKotlin)  lib-exoplayer-hls  lib-ui
```

（`lib-exoplayer` 的依赖闭包已经核对过：全部是 `project(modulePrefix + 'lib-*')` 写法，
都在替换表内，不存在「漏替换某个模块 → 源码与 1.11.0 AAR 混编」的风险。）

### 8.4 产物取证：APK 里确实是 fork 的代码

只看「构建成功」不够——如果替换没命中，Gradle 会安静地去 google() 下 1.10.0 AAR，
构建照样绿。所以做了一次类名级取证：拿上游 `androidx/media@1.10.0` 与 fork 的
`libraries/exoplayer` 源文件列表做差集，得到 **5 个 fork 独有类**，然后在 APK 的 dex 里搜类描述符：

| 类（fork 独有） | 在 APK 中 |
| --- | --- |
| `androidx.media3.exoplayer.audio.DolbyPassthroughAudioTrack` | ✅ classes.dex |
| `androidx.media3.exoplayer.audio.FireOsStreamInfo` | ✅ classes.dex |
| `androidx.media3.exoplayer.audio.PassthroughAudioDiagnostics` | ✅ classes.dex |
| `androidx.media3.exoplayer.audio.RendererClockAwareAudioSink` | ✅ classes.dex |
| `androidx.media3.exoplayer.text.CueGroupSubtitleTranslator` | ✅ classes.dex |
| 本次新写的 `com.supermov.tv.PlaybackEngine` / `Settings` | ✅ classes3.dex |

这 5 个类在上游 1.10.0 中**不存在**，只可能来自 fork 源码 → 方案 A 的替换已确凿生效。

### 8.5 fork 独有类给出的实现线索（对后续直通很有用）

- `PassthroughAudioDiagnostics` —— fork 自带的直通诊断，排查「为什么没直通」时应优先接它，
  比自己在 `AudioSink` 外面猜要准得多。
- `RendererClockAwareAudioSink` —— 直通（尤其是高码率 TrueHD/DTS-HD）时的时间轴对齐问题，
  fork 用感知 renderer clock 的 sink 解决，说明「直通后音画不同步」不应再靠外部补偿。
- `DolbyPassthroughAudioTrack` —— 杜比系直通走的是自定义 AudioTrack 实现，
  这正是「伪造 AudioCapabilities 让 ExoPlayer 选择直通」能成立的前提。
- `FireOsStreamInfo` —— Fire OS 设备上的音频流信息读取修正。

---

## 九、真机问题归因与修复（2026-09-18 18:12–18:15 · 设备 192.168.11.136）

用户实测反馈 4 个问题，logcat（`C:\美影固件\小米Debug\23136`，3236 行）逐条对上了证据。

### 9.1 证据链

| # | 现象 | 日志证据 | 结论 |
| --- | --- | --- | --- |
| 1 | 设置页能往下移，但最下面的选项看不到 | `activity_settings.xml` 根布局是 `layout_height="wrap_content"` 的 `LinearLayout`，**根本没有 ScrollView** | 条目超出屏高后被直接裁掉：焦点能移过去（View 确实存在），屏幕永远不显示。v1.25 新增 9 条后必然触发 |
| 2 | 直通全勾 7 项后，TrueHD / DTS-HD 影片从「能播」变成「提示转码 → 播不了」 | `AudioTrackAudioOutputProvider.getAudioTrackMinBufferSize:521` 抛 `IllegalStateException`（`checkState`），media3 包成 `ERROR_CODE_FAILED_RUNTIME_CHECK`（`Unexpected runtime error`） | 错误码名里**既没有 AUDIO_TRACK 也看不出是音频** → `isAudioTrackFailure` 判 false → 被当成「原画播放失败」丢给百度云端转码；而转码对 mkv 不可用 → 彻底没路 |
| 3 | 勾 DTS-HD 后 DTS-HD 影片播不了 | `AudioFlinger could not create track, status: -12`；`AudioTrack init failed 0 Config(48000, 252, 8, 2250000) Format(... DTS-HD MA 5.1, audio/vnd.dts.hd ...)`；HAL 侧 `adev_open_output_stream(... ch=0x003f ...)` + `for raw audio output, force alsa stereo output` | 声明 8 声道后 media3 去建 8ch 轨，被 AudioFlinger 拒（-12）；HAL 只肯开 6ch 且把 raw output 强制成 stereo。**这台设备吃不下 8 声道** |
| 4 | Auto / PCM 模式下 TrueHD 影片「有画面、没声音」 | 18:13:40→18:13:52 整段只出现 `OMX.amlogic.avc.decoder.awesome`（视频解码器），**没有任何音频解码器**，也没有 `onPlayerError`；同期 `AudioCapabilities: Unsupported mime audio/ac3 / eac3 / truehd / dtshd`（`audio/dts` 不在列表里，所以 DTS 能通） | 设备既直通不了、系统里又没有对应解码器 → `DefaultTrackSelector` **静默丢弃这条音轨**：画面照播、不报错、没声音。这正是「片源没问题但没声」的真相 |
| 5 | 转码兜底也失败（`转码流也不可用`） | `api/streaming?type=M3U8_AUTO_720` 返回 **200 且 bodyLen=109737**，但 `streamingUrl` 既没命中 4 个 key、**也没打 errno 日志** | 说明 `new JSONObject(body)` 直接抛异常被 `catch (Throwable)` 静默吞掉（原代码 catch 里没有日志），失败原因完全丢失 |

补充：`MediaCodecAudioRenderer` 在 18:14:34 / 18:14:49 两次报
`AudioSink$InitializationException: ... Cannot create AudioTrack`
（`AudioOutputProvider$InitializationException` ← `UnsupportedOperationException` ←
`AudioTrack$Builder.build()`），与第 3 条同源。

### 9.2 本轮修复

| 文件 | 改动 | 为什么 |
| --- | --- | --- |
| `res/layout/activity_settings.xml` | 按 `activity_detail.xml` 的既有写法**包一层 ScrollView**（`fillViewport`），底部留 72dp | 直接解决「滚到底也看不到」。底部留白保证最后一条能完整滚进可视区 |
| `PlayerActivity.isAudioTrackFailure` | 除错误码名含 `AUDIO_TRACK` 外，再**顺着 cause 链扫堆栈**，命中 `androidx.media3.exoplayer.audio.` 或消息含 `Cannot create AudioTrack` 即判定为音频失败 | 让第 2 条的 `ERROR_CODE_FAILED_RUNTIME_CHECK` 也能走进「改用解码重播」，不再误跳转码死路。该判定不依赖 media3 版本 |
| `PlayerActivity.handlePlaybackError` | 音频失败统一记入诊断；回退被关掉时提示「音频输出失败」而不是「原画播放失败」 | 提示不再把人引到网络/清晰度方向 |
| `Settings.AUDIO_MAX_CH_DEFAULT` | 8 → **6** | 第 3 条的 -12 就是 8 声道请求引出来的；缺省 6 才开得出声 |
| `SettingsActivity` | 新增**「音频诊断」**入口；声道数提示改成实测口径 | 一键看「设备真实直通能力 / 系统音频解码器 / 上次音频失败原因」，第 4 条那种「静默丢轨」不用再抓 logcat |
| `PlaybackEngine.deviceCapsReport()` / `noteAudioError()` | 新诊断报告：当前设置 + `AudioCapabilities.supportsEncoding()` 逐项 + `MediaCodecList` 枚举音频解码器 + 上次失败 | 把「为什么没声」的两个前提条件摊开 |
| `BaiduPan.streamingUrl` | JSON 解析失败不再静默：打出异常/bodyLen/body 开头；再加**正则兜底**在原始响应里捞含 `m3u8` 的 http 地址 | 第 5 条：先让失败可见，同时多一条取流路径 |

### 9.3 给这台盒子（小米 / Amlogic，Android 7.1）的建议组合

- 音频输出 = **源码直通**
- 源码直通编码：**只勾 AC-3 与 DTS**；E-AC-3 / E-AC-3 JOC / DTS-HD / TrueHD / AC-4 先不勾
  （设备上报这三类既不支持直通，也没有解码器；勾了只会让「能播」变成「不能播」）
- 最大声道数 = **6**
- 直通失败自动回退 = 开
- 拿不准就先点**「音频诊断」**，看设备上报的能力表与解码器清单再决定勾什么


---

## 十、v1.26：五个真机问题的归因与修复（2026-09-18 20:00–23:30）

用户报了五条，其中第 5 条是**我们自己在 v1.25 引入的回归**，必须单独认领。

| # | 现象 | 真正的原因 | 结论 |
| --- | --- | --- | --- |
| 1 | 设置页「刷新二维码」按了不出码 | `QrActivity` 只有**一个**单线程池：`startPolling()` 一次占住它最多 60×2.5s=150s，刷新按钮的 `startQr()` 排在后面永远轮不上；而且 `startQr()` 把 `stopPoll` 置回 false，把上一轮轮询又救活了 | 双线程池 + 世代号（见 10.1） |
| 2 | 设置里没有视频相关选项，尤其 DV | 确实没有 —— 之前只做了音频组。但**杜比视界能不能点亮不是应用能决定的**（显示端 + 芯片），应用只能影响「挑哪条轨」 | 补齐视频组，并把 DV 的真实边界写清楚（见 10.2） |
| 3 | 要永远原画，拿掉转码相关设置 | 「在线清晰度上限」与整个转码链路（`startTranscode` / `fallbackM3u8`）还在 | 整条删除（见 10.3） |
| 4 | ASS / SSA 字幕不支持 | **内嵌 ASS/SSA 本来就是支持的**（fork 的 `SsaParser` 能解文字/颜色/`\an`/`\pos`/字号）；真正缺的是**外挂字幕完全没有入口** —— 片子和字幕是两个文件时无人加载 | 补外挂字幕链路（见 10.4） |
| 5 | 上个版本 TrueHD / DTS-HD 能源码直通的设备，改完不能了 | **v1.25 把 `AUDIO_MAX_CH_DEFAULT` 从 8 改成 6**，这是一次「一刀切」的全局收紧：`AudioCapabilities` 的 `maxChannelCount=6` 会让 `AudioProfile.supportsChannelCount(8)` 返回 false，media3 于是把 7.1 的 TrueHD / DTS-HD 整条判成不可直通 —— 所有设备一起中招 | 改回 8，并给出**不按机型打补丁**的正解（见 10.5） |

### 10.1 扫码刷新不出来：单线程池把自己堵死了

`QrActivity` 原实现把「取二维码」和「轮询扫码结果」放在**同一个** `newSingleThreadExecutor()` 上：

```
startQr()  ->  fetchPool 排队
startPolling() -> 同一个 executor 里 while(!stopPoll){ sleep(2500); 查一次 }   // 最多 150s 不出来
```

轮询一开始，刷新按钮的 `startQr()` 就只能排在那 150s 后面；就算排到了，`startQr()`
里那句 `stopPoll = false` 还会把**上一轮**轮询复活，两轮抢着刷新同一个 ImageView。

修法（`_p1_qr.py`）：

- **两个线程池**：`fetchPool`（取码）与 `pollPool`（轮询）互不阻塞；
- **世代号 `gen`**：每次 `startQr()` 执行 `++gen`，所有 UI 回调先校验 `gen` 是不是自己这一代，
  不是就直接丢掉 —— 旧轮询即使还在跑也污染不了界面；
- `onDestroy()` 里 `++gen` 再 `shutdownNow()` 两个池。

### 10.2 视频相关选项：哪些真有用，哪些是骗人的

新加的「播放 · 视频」组一共六项，都能落到 media3 的真实 API 上：

| 设置项 | 落到哪 | 有效场景 |
| --- | --- | --- |
| 画面比例（原有） | `PlayerView` resizeMode | 黑边 / 变形 / 裁边 |
| 最大分辨率 ≤720p / ≤1080p / ≤4K / 不限 | `TrackSelectionParameters.setMaxVideoSize` | 电视只到 1080p 却硬啃 4K → 掉帧、发烫、解码器崩 |
| 最大帧率 ≤24 / ≤30 / ≤60 / 不限 | `setMaxVideoFrameRate` | 老盒子播 60fps 卡顿 |
| 解码器优先 自动 / 硬解 / 软解 | `DefaultRenderersFactory.setMediaCodecSelector` | 硬解颜色/HDR 更准；软解最稳但 4K 烫 |
| 硬解失败回退软解（原有） | `setEnableDecoderFallback` | 排查不支持编码 |
| 隧道模式 | `DefaultTrackSelector.Parameters.setTunnelingEnabled` | 音画同步更好；部分固件黑屏 |
| 杜比视界处理 跟随片源 / 优先非 DV 轨 | `setPreferredVideoMimeTypes` | **DV 片源全黑 / 发紫时的唯一应用层手段** |

**关于杜比视界，必须说实话**：能不能点亮由「显示端 + 芯片解码器」决定，应用改不了：

- media3 只在 **API 26+ 且屏幕不支持 DV** 时才自动改用 H.264/H.265 基础层解码器
  （`MediaCodecVideoRenderer.Api26.doesDisplaySupportDolbyVision()`）；
- API < 26 这段逻辑**根本不会执行** —— 这也是为什么 Android 7 的盒子放纯 DV 片会黑屏；
- 所以应用层能做的只有一件事：**在同一部片里改挑哪条轨**（`setPreferredVideoMimeTypes`
  把非 DV 的 mime 排前面）。这就是「杜比视界处理」这一项的全部作用，不多不少。
- 想确认真实能力，看设置页 → **媒体诊断**，里面会打出 `Display.getHdrCapabilities()`
  支持的 HDR 类型清单和系统里有没有 `video/dolby-vision` 解码器。

### 10.3 永远原画：整条转码链路删除

删掉的东西（不只是藏起来）：

| 位置 | 删除内容 |
| --- | --- |
| `Settings` | `K_QUALITY_CAP` / `QUALITY_ORIGINAL` / `QUALITY_1080` / `QUALITY_720` / `qualityCap()` / `setQualityCap()` |
| `SettingsActivity` | 「在线清晰度上限」整行 + `showQualityDialog()` + `qualityName()` |
| `PlayerActivity` | `startTranscode()` / `transcodeCode()` / `codeLabel()` / `fallbackM3u8()` / 字段 `usingM3u8`；`startPlay(url, pos, m3u8)` 去掉第三个参数 |
| `PlayerActivity` | `prepareAndPlay()` 里两处「限了上限就直接走转码流」的分支 |

现在的取流只有一条路：**原画直链；（v1.27 起）失败就把整条原画流程重跑，最多 3 次**。

之所以连兜底也删掉：百度 `api/streaming` 对 **mkv 多半返回空地址**（v1.25 的真机日志里
`M3U8_AUTO_720` 返回 200 但 body 解析不出地址），留着只是一条会误导人的假退路。

`BaiduPan.streamingUrl()` 这个公开方法保留但不再被播放器调用（不删是为了不扩大改动面）。

### 10.4 字幕：内嵌本来就支持，缺的是外挂

先把结论说清楚 —— **「ASS / SSA 字幕没支持」不是设置问题，也不是解析器不支持**：

- `MatroskaExtractor` 把 `S_TEXT/ASS` / `S_TEXT/SSA` 映射成 `MimeTypes.TEXT_SSA`；
- `DefaultSubtitleParserFactory` 支持 `TEXT_SSA`；fork 的 `SsaParser` 能解析**文字、颜色、
  `\an` 对齐、`\pos` 位置、字号**（通过 `SsaStyle`）；
- 未实现的是 `\move` / `\k` 卡拉OK / `\clip` / `\t` 动画 / `\p` 绘图 —— 这些属于特效级，
  电视上极少见，也不影响「能不能看到字幕」。

**真正的缺口是：应用从来没有加载过外挂字幕。** 用户下的片子（`.mkv`）和字幕（`.ass`）
是两个文件时，谁都没把它们凑到一起，表现就像「不支持 ASS」。

本版补上的链路（`PlayerActivity`）：

1. 新增字幕设置组：**字幕模式**（跟随片源 / 总是打开 / 一律关闭）、
   **首选字幕语言**、**字幕字号**、**字幕颜色**（白字黑描边 / 黄字黑描边 / 黑底白字 / 白字无描边）、
   **字幕位置**（下 / 中 / 顶）、**外挂字幕自动挂载**（缺省开）、**手动字幕文件**（路径或 URL）。
2. `startPlay()` 组装 `MediaItem.SubtitleConfiguration`：
   - ① 「手动字幕文件」填了就用它（本地路径 `Uri.fromFile` / http(s) 用 `Uri.parse`）；
   - ② 否则播本地文件时，在**同目录找同名**字幕：`.ass / .ssa / .srt / .vtt / .ttml / .sub`，
     先精确同名，再放宽成「以片名开头 + 字幕后缀」（覆盖 `影片.zh.ass` 这种命名）；
   - mime 由 `Settings.subMimeFor()` 按后缀给出，认不出就留空让 media3 自己嗅探。
3. 外观：`SubtitleView.setStyle(...)` 用 `CaptionStyleCompat`，位置档位换算成
   `setBottomPaddingFraction()`（下 0.10 / 中 0.45 / 上 0.80）。

**一个必须知道的取舍**：`CaptionStyleCompat` 是**全局**样式，会盖在每条 cue 自带样式之上，
所以 ASS 里逐行的 `\c&Hxxxxxx` 颜色会被统一掉。想尽量保留片源原色，就选「白字无描边」——
此时 caption 的透明度不为 1，cue 自身的颜色才有机会透出来。

### 10.5 源码输出被改坏：根因、以及「设备差异」到底该怎么处理

**先认领**：这是 v1.25 我们自己改出来的，不是设备差异。

v1.25 为了让那台小米盒子别再撞 `AudioFlinger ... status: -12`，把
`AUDIO_MAX_CH_DEFAULT` 从 8 改成了 6。但 `AudioCapabilities` 的 `maxChannelCount`
不是「保守一点更安全」的旋钮，它是**判定闸门**：

```
getAudioProfiles(encodings, maxChannelCount)  ->  AudioProfile(encoding, maxChannelCount)
AudioProfile.supportsChannelCount(8)          ->  8 <= 6  ==  false
getPassthroughConfigForFormat()               ->  null      // 不支持 -> 不走直通
AudioTrackAudioOutputProvider.getFormatSupportLevel()  ->  FORMAT_UNSUPPORTED
```

于是**所有 7.1 的 TrueHD / DTS-HD 一起失去直通能力** —— 本来能直通的功放设备也被误伤。
这就是「后处理问题 2/3/4」之后没料到的副作用。

**再回答用户的问题：要不要按机型一个个打补丁？**

不要。按机型打补丁是三个坑：

1. 机型无穷、固件版本还会变（同一型号不同 OTA 行为都不一样），补丁永远追不上；
2. 把「结论」写死在常量里，下一个人的设备又被误伤 —— 这正是 v1.25 的翻版；
3. 用户手里到底接没接功放、走的哪路 HDMI，应用看不出来，猜不准。

**正解是「宽进 + 运行时降道 + 记住结论」**，把判断权交给设备自己：

```
设置里「最大声道数上限」缺省 = 8          // 宽进：不预先假设设备不行
        ↓ 播放，建不出音频轨 -> 出错
onAudioFailure()：降道重试 8 → 6 → 2        // 让设备自己把话说完
        ↓ 哪一档真的出声了
STATE_READY 把该档位写进 Settings.setPtOkCh() // 记住结论（带设备指纹）
        ↓ 下次开局
effectiveMaxAudioCh() = min(设置上限, 实测值) // 直接用，不再逐级试错
```

实现要点：

| 位置 | 内容 |
| --- | --- |
| `Settings.AUDIO_MAX_CH_DEFAULT` | **改回 8**（语义是「上限」，不是「写死」） |
| `Settings.ptOkCh()` / `setPtOkCh()` / `clearPtOkCh()` | 本机实测可用的声道数；换机器/固件自动作废 |
| `Settings.ptDeviceKey()` | 设备指纹 = 机型 + `Build.DISPLAY` + SDK + HDMI 输出名 |
| `Settings.ptEffectiveCap()` | `min(设置上限, 实测值)` |
| `PlaybackEngine.effectiveMaxAudioCh()` | 优先级：临时覆盖 > 实测值 > 设置上限 |
| `PlaybackEngine.setChannelCapOverride()` | 降道重试时的一次性覆盖（换片时清零） |
| `PlayerActivity.onAudioFailure()` / `nextLowerCap()` | 8 → 6 → 2 逐级；都不行才退回 PCM 解码 |
| `PlayerActivity.rebuildWithChannelCap()` | 用新的声道上限重建播放器并 seek 回原位置 |
| 设置页「本机直通实测」 | 显示实测结论 + 设备指纹，可一键清除重测 |

这样「只能 6 声道的盒子」和「能 8 声道的功放」用**同一个包**，各得其所；
换功放 / 换 HDMI 线 / 刷固件之后，用户在设置页点一下「清除记录」即可重测。

> 顺带修掉的旧逻辑：v1.25 是「音频一失败就直接降级 PCM」，代价是本来能直通 5.1 的盒子
> 被白白降成 PCM。现在先把声道降下来再试直通，能保住的音质就保住。

### 10.6 本版改动清单

| 文件 | 改动 |
| --- | --- |
| `QrActivity.java` | 双线程池 + 世代号 `gen`；扫码/轮询互不阻塞，旧轮询无法污染 UI |
| `Settings.java` | `AUDIO_MAX_CH_DEFAULT` 6→**8**；删 `qualityCap` 一组；新增视频组（最大分辨率/帧率/解码器优先/隧道/DV）、字幕组（模式/语言/颜色/位置/外挂自动/手动）、直通实测记忆（`ptOkCh`/`ptDeviceKey`/`ptEffectiveCap`/`clearPtOkCh`） |
| `PlaybackEngine.java` | 新增 `trackSelector()`（隧道模式）、`withVideoPreferences()`、`withSubtitlePreferences()`、`setChannelCapOverride()`、`effectiveMaxAudioCh()`、`avReport()`（显示端 HDR/DV + 视频解码器 + 字幕支持） |
| `PlayerActivity.java` | 删整条转码链路；`startPlay()` 挂外挂字幕；字幕外观跟随设置；音频失败改为「降道重试 8→6→2 → 最后 PCM」；`STATE_READY` 记录实测声道 |
| `SettingsActivity.java` | 删「在线清晰度上限」；新增视频组 5 项、字幕组 6 项、「本机直通实测」行；「音频诊断」升级为「媒体诊断」 |
| `app/build.gradle` | versionCode 26 / versionName 1.26 |

### 10.7 验收清单（v1.26）

1. 设置页 → 扫码页：连点几次「刷新二维码」，每次都在 1 秒内出新码（不再等 150s）。
2. 设置页能看到「播放 · 视频」6 项、「播放 · 字幕」7 项、「本机直通实测」1 项。
3. 设置页里**找不到**「在线清晰度上限」或任何「1080p / 720p 转码」字样。
4. 在线播一部 7.1 TrueHD / DTS-HD 片，功放面板应点亮对应格式（默认上限已回到 8）。
5. 若某台盒子 8 声道建不出轨：应在日志里看到自动降到 6 后继续播；再到设置页看
   「本机直通实测」是否已记成 `6 ch 可用`，下一次播放直接按 6 走。
6. 本地文件旁放一个同名 `.ass`，播放后字幕应自动出现；换成 `.srt` 同样有效。
7. 「媒体诊断」里能看到显示端 HDR/DV 能力、系统视频解码器清单、外挂字幕状态。
## 十一、v1.27：原画失败 = 重新尝试原画

### 11.1 取消了什么

| 原先行为 | 现在的行为 |
| --- | --- |
| 播放中断 → 「换一条新直链」重试**一次**，然后放弃 | 重跑**整条原画取流**（重新定位文件 → 重新转存 → 重新取 dlink → 重新挂代理） |
| 取流阶段拿不到 dlink → 直接报错结束 | 同样进入重试，且仍然是原画 |
| 重试次数无额度概念（就一次） | 最多 3 次；连续播满 30 秒则额度归还 |
| 云端转码兜底（v1.26 已删） | 不存在 |

一句话：**没有第二个源，也没有第二个清晰度。**

### 11.2 为什么不是「换链」而是「重跑整条」

「换一条新直链」只治一种病：**直链过期**（dlink 只有 8 小时）。而真机上更常见的是
风控、接口瞬时抖动、定位环节出错 —— 这时候换链拿到的东西和上一次完全一样，用户看到的
只有一句「换新直链也失败了」，等于白试一次。

重跑整条流程把这些情况全覆盖，而且语义上永远是「原画」。这与「不做机型补丁」是同一个
思路：把不确定性收敛到**重试**，而不是收敛到**降级**。

### 11.3 额度：3 次 + 播满 30 秒归还

- `ORIGIN_MAX_TRIES = 3`：连续 3 次都没取到可播地址就如实报错（附最后一次原因）。
- `ORIGIN_BUDGET_REFILL_MS = 30000`：连续播满 30 秒 = 这一档原画确实通了，
  `originTries` 清零。看了一小时被风控掐断时不会只剩一次机会。
- 「归还」必须先真的播出满 30 秒，所以**不存在**「失败 → 重试」死循环。

### 11.4 真机上能看到的状态栏文案

| 时机 | 文案 |
| --- | --- |
| 首次 | 正在定位影片… → 正在获取原画直链…（片名） → 原画播放：片名 |
| 重试时 | 原画获取失败，正在重新尝试原画（第 2 次）…（序号是本次播放里累计的重试次数，**不随额度重置**） |
| 重试成功 | 已重新取到原画直链，继续播放 |
| 3 次都失败 | ✘ 原画获取失败：连续 3 次都没取到可播地址（…），请稍后再试 |

播放**中断**（已经播起来了才断）也走同一条路，不再有单独的「换链」分支。

### 11.5 改动清单（v1.27）

| 文件 | 改动 |
| --- | --- |
| `PlayerActivity.java` | 新增 `acquireOriginal()`（唯一取流入口）、`locateOriginalFile()`、`originLabel()`、`refillOriginBudgetIfHealthy()`；删字段 `retriedDlink` 与「换新直链」整段；`prepareAndPlay()` 只做鉴权检查后转交 `acquireOriginal()` |
| `Settings.java` | 注释同步（该位置只有说明，无偏好项） |
| `app/build.gradle` | versionCode 27 / versionName 1.27（**按用户要求只推源码，不触发云端编译**） |

### 11.6 验收清单（v1.27）

1. 在线播放正常时，状态栏依次出现「正在定位影片…」「正在获取原画直链…」「原画播放：…」。
2. 拔网线 / 断到拿不到 dlink 时，应看到「原画获取失败，正在重新尝试原画（第 2 次）…」，
   而不是任何「换直链」「转码」「换源」字样。
3. 连续 3 次失败后应看到 `✘ 原画获取失败：连续 3 次都没取到可播地址（…），请稍后再试`。
4. 播放中拔网线 → 恢复网络后应自动重取原画并**从原位置续播**。
5. 全局搜索代码，`retriedDlink` / 换新直链 关键词应为 0 命中。

