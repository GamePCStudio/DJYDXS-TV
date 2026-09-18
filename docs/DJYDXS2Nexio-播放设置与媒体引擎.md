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

