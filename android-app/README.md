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

```
┌─────────────────────────────┐
│  ☰   AI 手机助手          ⚙  │
├───┬─────────────────────────┤
│   │                         │
│侧 │   屏幕预览               │  ← 中间这块
│边 │   （或 AI 执行日志）      │
│栏 │                         │
├───┴─────────────────────────┤
│  [   文本输入      ] [发送]  │
└─────────────────────────────┘
```

**侧边栏**（点左上角 ☰）：控制手机 / 敬请期待 / 设置

**中间区域**：没日志时显示屏幕预览，有日志时显示 AI 的思考与动作

**设置页**：模型配置 + 触控方式清单

---

## 三、代码结构

```
app/src/main/
├── AndroidManifest.xml              注册无障碍服务
├── res/xml/accessibility_service_config.xml   服务能力声明
│
└── java/com/aiphone/assistant/
    ├── MainActivity.kt              入口；引导用户去系统设置开无障碍
    ├── ChannelController.kt         通道管理：连接、抓帧、读控件树
    │
    ├── a11y/
    │   ├── AutoService.kt           ★ 无障碍服务本体（眼睛 + 手）
    │   └── UiNode.kt                ★ UI 树解析与压缩
    │
    ├── channel/
    │   ├── DeviceChannel.kt         通道接口 + 能力声明
    │   └── AccessibilityChannel.kt  ★ 通道实现（动作分派）
    │
    ├── touch/TouchMethods.kt        14 种触控方式清单
    │
    └── ui/
        ├── MainScreen.kt            主界面
        ├── PreviewArea.kt           预览区
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

## 四、几个关键实现细节

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

## 五、导入 Android Studio

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

---

## 六、网络问题（国内必看）

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

## 七、现在能做什么、不能做什么

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
- 预览区显示画面 + 全黑检测
- 14 种触控方式清单（含能力标注）

**还没做**

| 项 | 说明 |
|---|---|
| AI 执行循环 | 通道全通了，但没接模型调用 |
| 提示词接入 UI 树 | `readUiTree()` 写好了，`agent` 还没消费它 |
| 自定义多指路径 | 接口预留了，还没暴露 |
| 设置持久化 | 改了配置重启就丢 |
| 通知栏急停 | 要有前台服务才能做 |
| 悬浮窗急停按钮 | 设计想清楚了，没实现 |
| 「敬请期待」内容 | 还没定 |

---

## 八、第一次跑建议这样验证

按顺序来，哪一步断了就停在那一步查：

```
1. 装上应用，打开 → 预览区应该提示"无障碍服务未开启"
2. 点「去开启无障碍服务」→ 在系统设置里开启
3. 回到应用，点「重新连接」→ 应该出现手机屏幕画面
4. 画面出来了，说明截图链路通了
5. 再验证 UI 树：如果能拿到编号列表，定位能力就是完整的
6. 最后才是接 AI 循环
```

**第 3 步是整个方案的验证点。** 如果画面出不来：
- 检查没开无障碍服务
- 如果是银行/支付类页面，那是有意截不到的（`FLAG_SECURE`）
- 如果提示"截图太频繁"，是平台限流，等一下重试
