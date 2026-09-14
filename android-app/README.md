# Android 端 —— 无障碍方案

Android 原生应用（Kotlin + Jetpack Compose + Material 3），
用**系统无障碍服务**操作手机。不需要装任何额外的东西，重启后自动恢复。

---

## 一、为什么选无障碍（而不是 ADB / Shizuku）

一开始走的是 ADB 路线（靠 Shizuku 拿 shell 身份），后来换成了无障碍。原因是：

| | ADB / Shizuku | **无障碍** |
|---|---|---|
| 额外安装 | 要装 Shizuku | **不用** |
| **重启后** | ❌ **要重新用无线调试启动** | ✅ **自动恢复** |
| 截图 | ✅ 干净，能截安全窗口 | ⚠️ 有限流，安全窗口截不到 |
| 多指手势 | ❌ `input` 只支持单指 | ✅ **天然支持** |
| 中文输入 | ❌ 要装 ADBKeyboard 或走剪贴板 | ✅ **直接灌文本** |

**决定性的一条是"重启后自动恢复"。** Shizuku 在无 root 手机上每次重启都要
重新用无线调试启动一次，日常使用太折腾。无障碍授权一次就常驻。

代价是截不到安全窗口（银行密码键盘那种 `FLAG_SECURE` 页面），以及对一个
每步 3-8 秒的 AI 循环来说无所谓的截图限流。

### 定位策略：UI 树为主，截图辅助

这是本项目最关键的架构选择。

**纯视觉方案**（只看截图点像素）有个绕不开的问题：**模型算的坐标会偏**。
Roubao 的 issue 里坐标偏移是第二高频问题，作者甚至承认"所有横屏设备都无法使用"。

**读 UI 控件树能直接从系统拿到元素的精确 bounds** —— 坐标不需要模型猜，也就不会偏。
两者分工明确：

```
截图        →  理解语义（这一屏是什么界面、该做什么）
UI 控件树   →  精确定位（点哪个元素）
```

---

## 二、界面结构

应用名是**纸盒**（用户叫潘纸盒，所以图标就是一个开口的纸板箱）。

```
┌─────────────────────────────┐
│  ☰   操作手机             ⚙  │
├─────────────────────────────┤
│                             │
│           ╱╲                │
│          ╱  ╲    ← 纸盒 logo │  ← 正中央
│          ╲  ╱               │
│                             │
│       AI 操作手机.           │
│        By 潘纸盒             │
├─────────────────────────────┤
│  [  告诉 AI 下一步 ]   ➤    │
└─────────────────────────────┘
```

**侧边栏**（点左上角 ☰）：操作手机 / 敬请期待 / 设置

**中间区域**：默认是纸盒 logo；任务跑起来有日志之后切成日志流

> 这里**没有屏幕预览**。走无障碍通道时被操作的 App 就在用户眼前，
> 应用里再显示一份截图没有意义，还会和自己的界面打架。
> 预览区在改成无障碍方案时就已经删掉了。

**设置页**：三段

| 段 | 内容 |
|---|---|
| 模型 | 接口地址 / API Key（默认打码，点眼睛才明文）/ 模型 / 图片精度 |
| 操作授权 | 操作方式下拉 + 前往授权（带真实连接状态） |
| 开发者设置 | 红字警告 + 清空上下文 / 自动清空 / 记忆三项 / 日志导出 |

---

## 三、代码结构

```
app/src/main/
├── AndroidManifest.xml              注册无障碍服务 + FileProvider
├── res/xml/accessibility_service_config.xml   服务能力声明
├── res/xml/file_paths.xml           FileProvider 暴露的路径白名单
├── res/drawable/ic_box_logo.xml     ★ 纸盒图标（启动图标 + 界面 logo 共用一份）
│
└── java/com/aiphone/assistant/
    ├── MainActivity.kt              入口 + 状态中枢（串联 UI / 设置 / 日志）
    ├── ChannelController.kt         通道管理：探测、抓帧、读控件树
    │
    ├── a11y/
    │   ├── AutoService.kt           ★ 无障碍服务本体（眼睛 + 手）
    │   └── UiNode.kt                ★ UI 树解析与压缩
    │
    ├── channel/
    │   ├── DeviceChannel.kt         通道接口 + 能力声明
    │   └── AccessibilityChannel.kt  ★ 通道实现（动作分派）
    │
    ├── touch/
    │   ├── TouchMethods.kt          16 种触控方式清单
    │   └── GestureSpec.kt           统一手势模型（按下/移动/停顿/松手）
    │
    ├── data/
    │   ├── AppSettings.kt           全部配置项
    │   └── SettingsStore.kt         SharedPreferences 存取
    │
    ├── log/
    │   ├── AppLog.kt                ★ 每次运行一个日志目录
    │   └── LogExporter.kt           打包 zip + 系统分享
    │
    ├── memory/
    │   └── Memory.kt                上下文 + 用户洞察 md + 归纳接口
    │
    └── ui/
        ├── MainScreen.kt            主界面
        ├── SettingsScreen.kt        设置页
        ├── UiState.kt               状态模型
        └── theme/Theme.kt           Material 3 + 动态取色
```

三个核心文件：

| 文件 | 职责 |
|---|---|
| `AutoService.kt` | 系统绑定的服务。截图、读树、手势、灌文本全在这 |
| `UiNode.kt` | 把原始节点树压缩成给模型看的编号列表 |
| `AccessibilityChannel.kt` | 把动作翻译成具体的无障碍 API 调用 |

---

## 四、日志

**每次运行一个独立目录，永不覆盖**：

```
<应用私有目录>/纸盒/logs/runs/<时间>_<任务名>/
  run.log          完整文字记录，带时间戳
  report.json      结构化记录（每步动作 + AI 原始输出）
  screenshots/     每一步的截图
```

日志开头会自动记下环境信息（版本、机型、通道、模型、接口、精度）——
这些是排查时第一批要问的东西，写进日志就不用再问用户。

**导出只走系统分享面板**（打包成 zip，经 FileProvider）。
这一条是刻意的：Android 11 之后 `/Android/data` 对文件管理器和 USB
都不可见，把日志丢在私有目录里**用户根本拿不到**。
分享不需要任何存储权限，能发微信 / 发邮件 / 存网盘 / 存到本地。

应用内**不提供日志查看** —— 日志是给开发排查用的，不是给用户读的。

三个实现上的决定：

1. **每写一行就落盘。** 自动化工具最常见的失败是卡死或被系统杀掉，
   缓冲区里没刷出去的正是最后那几行 —— 也就是最关键的线索。
2. **同时打 logcat。** `adb logcat -s 纸盒` 能实时看，不用一边跑一边翻文件。
3. **截图和日志同一个目录。** 出问题时把整个 zip 发出来就够了。

---

## 五、几个关键实现细节

### 1. UI 树要压缩，不能原样丢给模型

无障碍给的原始节点树又深又吵，还有大量无意义的容器节点。直接丢给模型
既浪费 token 又干扰判断。`UiNode.kt` 的过滤规则：

- 只留可交互（可点/可滚/可编辑）或有文字/描述的
- 面积小于 24×24 的丢掉（装饰线、占位符）
- 完全在屏幕外的丢掉（列表滚动后的残留）
- 同样的位置 + 文字只留一个（去重）

压缩后渲染成这样，模型从里面选编号：

```
[1] Button "登录" @(540,2175) [可点]
[2] EditText "手机号" @(540,1200) [可点,可输入]
[3] TextView "忘记密码?" @(880,2300) [可点]
```

### 2. 按编号点击比按坐标准

节点对象**不能跨帧缓存** —— 界面一变就失效。所以每次操作都重新解析一遍树，
再定位到对应编号。

点击时先试节点级 `ACTION_CLICK`（最准），点不动才退回坐标手势
（有些自定义控件不响应节点点击）。

### 3. `ACTION_SET_TEXT` 是无障碍最大的红利

```kotlin
node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
```

**直接设字符串，任意 Unicode 都行，不需要任何额外依赖。**

对比 ADB 方案：`input text` 只支持 ASCII，中文要么装 ADBKeyboard、
要么走剪贴板 + keyevent 279，两种都很别扭。

优先级：当前焦点节点 → 树里第一个可编辑节点 → 提示用户先点输入框。

### 4. 截图要重试

无障碍截图会撞上平台限流（`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`，
API 30+）。代码里做了 3 次重试 + 递增等待。**这不是优化，是必需的容错。**

另外 `takeScreenshot` 返回的是硬件位图，要 `copy(ARGB_8888)` 转成软件位图，
否则后续 `getPixel` 或跨线程使用会出问题。

### 5. 单指手势：一个统一模型，七个动作

这是手势层的核心设计（`GestureSpec.kt`）。

**所有单指动作都是同一个三段式的参数变体**：

```
按下 ──┬── 移动 ──┬── 停顿 ── 松手
       │          │
       │      滑动有停顿，甩动没有
       └── 拖拽在这里加长按
```

| 动作 | 按下后停顿 | 移动 | 松手前停顿 |
|---|---|---|---|
| 单击 | — | 短 | — |
| 长按 | — | — | —（按住时间长） |
| 双击 | — | 短 ×2 | —（两次单击） |
| 滑动 | — | 有 | **有** |
| 甩动 | — | 有（快） | **无** |
| 拖拽 | **有**（超长按阈值） | 有 | — |

#### 为什么"松手前停顿"能区分滑动和甩动

惯性滑动靠的是**松手瞬间的速度**。系统根据手指抬起前的移动速度决定是否继续滚一段。

- 划完**停顿一下**再松 → 停顿期间速度归零 → 不触发惯性 → **滑动**
- 划完**立刻松** → 保持速度 → 继续滚一段 → **甩动**

一个参数就把两者分开了，不需要两套实现。

实现上停顿是"在路径终点再补一个重合点"：

```kotlin
if (spec.restBeforeUpMs > 0) {
    path.lineTo(x2, y2)   // 终点重合点 → 手指停住 → 速度归零
}
val stroke = StrokeDescription(
    path,
    spec.holdBeforeMoveMs,                 // 按下后等这么久才开始移动
    spec.moveMs + spec.restBeforeUpMs,     // 移动 + 停顿
)
```

#### 为什么拖拽头部要长按

**系统的拖拽判定看的是"按下后多久开始移动"** —— 必须超过长按阈值，
系统才进入拖拽模式。

之前的实现只是把移动过程拉长到 800ms、按下后立刻开始动，
那会被系统当成"慢速滑动"，移动图标、调滑块这类会失败。

现在用 `ViewConfiguration.getLongPressTimeout()` 拿**设备真实阈值**
（不同 ROM 可能不一样），再加 100ms 余量：

```kotlin
fun drag(longPressTimeout: Long, moveMs: Long = 500) = GestureSpec(
    holdBeforeMoveMs = longPressTimeout + 100,
    moveMs = moveMs,
)
```

#### 滚动：节点级优先

`smartScroll` 先找面积最大的可滚动节点走 `ACTION_SCROLL_FORWARD`
（不需要坐标、不会滚过头），失败才退回**甩动**手势
（回退用甩动而不是滑动 —— 滚动列表期待的就是惯性效果）。

### 6. 双指暂时搁置

双指捏合/张开在代码里能跑（`dispatchGesture` 支持多路径），
但按计划先只把单指做扎实。双指旋转、双指点击暂缓。

---

## 六、导入 Android Studio

1. `File` → `Open` → 选 **`android-app`** 目录（不是最外层的 `AI手机`）
2. 等 Gradle 同步（第一次要下载 Gradle 8.9 + 依赖）
3. `Tools` → `SDK Manager` → 装 **Android 15 (API 35)**
4. 连手机（开 USB 调试）→ 点 ▶

### 装到手机后必须先开无障碍

**无障碍服务不能通过代码申请**，必须手动去系统设置里开：

```
设置 → 无障碍 → 已安装的服务 → AI 手机助手 → 开启
```

各家 ROM 路径略有不同，应用里的「去开启无障碍服务」按钮会直接跳过去。

### 报错怎么办

| 报错 | 处理 |
|---|---|
| **`Could not install Gradle distribution ... Connection refused`** | 见下一节 |
| `Could not find GradleWrapperMain` | 跑 `bash setup_gradle.sh` 补 wrapper |
| JDK 版本问题 | 设置里把 Gradle JDK 改成 `jbr-17` |
| `SDK location not found` | 建 `local.properties` 写 `sdk.dir=...` |
| **`plugin classpath entry points to a non-existent location`** | 见下面「中文路径」一节 |

### 中文路径会把 Kotlin 编译搞坏（本项目踩过）

本项目路径里有中文（`/Users/panzhihe/DSH/AI手机`）。Kotlin 守护进程
起不来时 Gradle 会回退到「无守护进程」模式，而那个模式拼插件 classpath
时会把中文转义坏掉：

```
plugin classpath entry points to a non-existent location:
/Users/panzhihe/DSH/AIu624Bu673Au673A/android-app/...
```

注意 `AI手机` 变成了 `AIu624Bu673Au673A` —— `\u624B\u673A` 的反斜杠被吞了。
报错信息完全看不出真正原因，很容易被误判成代码问题。

`gradle.properties` 里已经加了这一行来绕开：

```properties
kotlin.compiler.execution.strategy=in-process
```

让编译器直接跑在 Gradle 守护进程里，不走那条会坏掉的路径。
**顺带快很多**：同一个工程从 17 分钟降到 19 秒（省掉了守护进程启动和 IPC）。

代价是要给 Gradle 多分点堆内存，所以 `org.gradle.jvmargs` 提到了 3G。

---

## 七、网络问题（国内必看）

`services.gradle.org` 会 307 跳转到 `downloads.gradle.org`，后者国内基本连不上，
报 `Connection refused`。**这不是配置错误，是网络问题。**

工程里已经换成腾讯云镜像：

```properties
distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
validateDistributionUrl=false
```

> `validateDistributionUrl=false` 是必须的 —— Gradle 默认会去官方源校验，
> 换成镜像后校验必然失败。

**改完还报同样的错**，说明缓存了失败记录：

```bash
rm -rf ~/.gradle/wrapper/dists/gradle-8.9-bin
```

然后 `File → Sync Project with Gradle Files`。

依赖包如果也慢，可以换阿里云镜像 —— 改 `settings.gradle.kts` 里的
`dependencyResolutionManagement`，把阿里云的三个仓库加到前面。
`gradle.properties` 里也已经配好了超时和重试参数。

---

## 八、现在能做什么、不能做什么

**已经实现**

- 无障碍服务：连接检测、生命周期管理
- 截图（含限流重试、硬件位图转换、错误码翻译）
- UI 控件树解析与压缩（过滤 + 去重 + 编号）
- 按编号点击 / 长按（节点级 `ACTION_CLICK`，点不动退回坐标手势）
- **单指坐标手势：点击 / 长按 / 双击 / 滑动 / 甩动 / 拖拽**（统一手势模型）
- 滚动（节点级优先，失败退回甩动手势）
- 双指缩放（代码能跑，按计划暂缓）
- 返回 / 主页 / 多任务
- **中文输入**（`ACTION_SET_TEXT`，不需要任何额外依赖）
- 打开应用
- 16 种触控方式清单（含能力标注）
- **纸盒品牌**：矢量图标，启动图标 / 主界面 / 侧边栏共用一份几何
- **主界面 / 设置页三段式**（模型 / 操作授权 / 开发者设置）
- **设置持久化**（SharedPreferences，重启不丢）
- **日志系统 + 导出**（每次运行独立目录，zip 走系统分享）
- **记忆接口**：上下文沉淀成用户洞察 md，落盘 / 读取都已可用

**还没做**

| 项 | 说明 |
|---|---|
| **AI 执行循环** | 通道和日志全通了，但还没接模型调用 |
| **提示词接入 UI 树** | `readUiTree()` 写好了，还没有消费它的 agent |
| 记忆的 AI 归纳 | `InsightDistiller` 是接口，当前实现只做原样转存 |
| 多模态截图送模型 | 截图已经能拿到并落盘，还没发给模型 |
| 通知栏 / 悬浮窗急停 | 要有前台服务才能做 |
| 自定义多指路径 | 接口预留了，还没暴露 |
| 「敬请期待」内容 | 还没定 |

> 发一条指令之后，目前走的是循环里**「看」**的那一半：
> 探通道 → 取分辨率 → 读控件树 → 截图，全部写进本次运行的日志。
> 之所以先把这半接上，是因为这条链路（截图 → 落盘 → 导出）是
> 后面一切的地基，而且它现在就是**可验证**的：
> 发一条指令、导出日志，能看到截图文件和控件树，说明地基是通的。

---

## 九、第一次跑建议这样验证

按顺序来，哪一步断了就停在那一步查：

```
1. 装上应用，打开 → 中间应该显示纸盒 logo
2. 点右上角 ⚙ → 设置页 → 「前往授权」
3. 在系统设置里开启「纸盒」的无障碍服务
4. 回到应用 → 操作授权那里应该变成「已授权」
5. 在底部输入框随便发一条，比如「测试」
6. 中间会出现日志：通道就绪 / 屏幕尺寸 / 控件树元素数 / 截图结果
7. 点「导出最近一次」→ 系统分享面板 → 存到本地或发出去
8. 解开 zip，应该看到 run.log + report.json + screenshots/step_01.png
```

**第 4 步和第 8 步是关键验证点。**
第 4 步不过说明无障碍没绑上；第 8 步不过说明日志导出链路有问题。

**截图失败是预期的可能结果**，三个原因，日志里会写清楚是哪一个：

- 无障碍服务没绑上（去设置里确认是「已授权」）
- 银行 / 支付类页面有 `FLAG_SECURE`，系统不允许截图，这是有意为之
- 提示「截图太频繁」= 平台限流，等一秒重试即可（代码里已经做了重试）
