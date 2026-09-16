# 纸盒

**让多模态大模型直接操作你的安卓手机。**

截图 → 发给模型 → 解析它给出的动作 → 用无障碍服务执行 → 循环，直到任务完成。

不需要 root，不需要电脑，不需要装 Shizuku。装上、授权无障碍、填一个 API Key 就能用。

> 名字来自作者「潘纸盒」，图标就是一个纸板箱。

---

## 目录

- [它是什么](#它是什么)
- [工作原理](#工作原理)
- [功能](#功能)
- [截图](#截图)
- [环境要求](#环境要求)
- [构建与安装](#构建与安装)
- [快速开始](#快速开始)
- [配置](#配置)
- [项目结构](#项目结构)
- [已知限制](#已知限制)
- [路线图](#路线图)
- [开源致谢](#开源致谢)
- [免责声明](#免责声明)

---

## 它是什么

市面上大多数"AI 操作手机"方案要么依赖电脑上的 ADB，要么依赖厂商预装的系统级权限。
纸盒走的是第三条路：**利用安卓自带的无障碍服务**。

它带来的实际差别：

| | 需要电脑 | 重启后失效 | 额外安装 | 截安全窗口 |
|---|---|---|---|---|
| ADB / Shizuku | 首次需要 | **是** | Shizuku | 可以 |
| 厂商预装方案 | 否 | 否 | — | 可以 |
| **纸盒（无障碍）** | **否** | **否** | **无** | 不可以 |

代价是截不到银行 / 支付类页面（系统有 `FLAG_SECURE` 保护）—— 这个限制绕不过去，
也不该绕。

## 工作原理

```
        ┌────────────────────────────────────────────────┐
        │                                                │
        ▼                                                │
   控件树 ──→ 大模型 ──→ 一批动作 ──→ 逐个执行 ──────────┘
                          ↑
                    （模型主动要图时才截一张）
```

三个关键设计：

**1. 定位靠控件树编号，不靠猜像素。**

每一轮发给模型的是一份从系统控件树提取的编号列表：

```
[3] Button "登录" @(540,2175) [可点]
[7] EditText "手机号" @(540,1200) [可点,可输入]
```

坐标是系统给的真实 `bounds`，模型只需要"选一个数字"，不需要从截图里估算坐标。
这比纯视觉方案的点击准确率高一个量级。

**2. 默认不发截图。**

一张 1080x2400 的截图 base64 之后有一两兆，是整条链路里最贵的东西。
控件树已经能说清绝大多数界面，所以截图改成**按需**：模型判断不了这一屏
（游戏、视频、纯自绘界面）时，把 `need_image` 设为 true 要一张。
日志里会写清哪一轮带了图。

**3. 一轮给一批动作，不是一个动作。**

```
actions: [点击 3] → [等 10 秒] → [点击 5]
```

系统按顺序执行，动作之间自动补 1.5 秒；模型显式写了 `sleep` 就完全用它的值。
连点两次同一个元素（模拟双击）就是把同一条写两遍、中间插一个 1ms 的 sleep。
往返次数因此大幅下降 —— 每次往返都要重读界面、重发上下文。

**4. 上下文严格追加，缓存友好。**

对话是严格递增的 `[system][user1][assistant1][user2]...`，前缀永不变动。
系统提示词也是纯静态的（分辨率这类会变的东西放在每轮消息里），
所以从第 2 步开始大部分输入都命中缓存。真机上日志会直接打印命中率。

细节和踩过的坑见 [`docs/DESIGN-NOTES.md`](docs/DESIGN-NOTES.md)。

## 功能

**技能（skills）**

模型能主动索取"屏幕上没有的信息"，系统取回来塞进上下文，这一轮不算一步：

- `list_apps` —— 列出已安装的可启动应用和包名。
  要用 `open_app` 之前先调它，**不要凭记忆编包名**（系统应用在不同 ROM 上
  包名完全不一样，只能查）
- `list_skills` —— 拿技能的完整说明文档（参数、返回格式、什么时候该用）

设计上分两层：**目录**（每个技能一行）常驻系统提示词，**完整文档**按需拉。
和截图是同一个思路 —— 便宜的常驻，贵的按需。

新增一个技能 = 实现 `Skill` 接口 + 在 `SkillRegistry` 里注册一行，
目录和系统提示词会自动带上（记忆、用户洞察这类以后都走这个口子）。

**AI 循环**

- 控件树编号定位；截图按需索取（默认不发，省 token）
- 一轮可以给一批动作，中间自动补默认间隔；模型写 `sleep` 可覆盖
- 严格追加的上下文 + 静态系统提示词，命中服务端缓存
- 防御性 JSON 解析：剥 markdown 围栏 → 提取配平的花括号块 → 动作名白名单 →
  坐标和时长夹紧；坏的那条丢掉、好的照常执行
- 模型输出不合法时把原因回灌让它重出，而不是直接失败

**防死循环**（比 `max_steps` 更管用）

- 控件树文本指纹连续 3 步不变 → 下一轮提示里点破"你上个动作没生效"
  （不再依赖截图 —— 而且没有动画和时钟的干扰，比截图哈希更准）
- 整批动作的签名连续 3 次相同 → 强制要求换动作
- 同一轮最多给 2 次截图，防止"要图 → 还是不确定 → 再要图"耗下去

**悬浮控制面板**

- 左上角：当前第几步 / 正在做什么 / **模型预测的下一步**
- 底部居中：红色「急停」
- 注入会撞到急停按钮时才隐藏，其余时候一直可见
- 急停在"动作已决定但还没注入"这个检查点生效，不是硬砍
- 等待被拆成 100ms 一小段，长 sleep 中途也能立刻急停

**三路急停**：悬浮窗按钮 / 通知栏按钮 / 主界面按钮

**16 种触控动作**

点击、长按、双击、滚动、滑动、甩动、拖拽、双指缩放、输入文字、
返回 / 主页 / 多任务、打开应用、等待。

其中"滚动"和"滑动"是两回事：滚动针对可滚动容器（不需要坐标，优先走节点级
`ACTION_SCROLL_FORWARD`），滑动是从一点拖到另一点。

**中文输入不需要任何额外组件** —— 用 `ACTION_SET_TEXT` 直接设字符串，
绕开了 `adb shell input text` 只支持 ASCII 的老问题。

**日志系统**

每次运行一个独立目录，永不覆盖：

```
<应用私有目录>/纸盒/logs/runs/<时间>_<任务名>/
  run.log          完整文字记录，带时间戳
  report.json      结构化记录（每步动作 + 模型原始输出）
  screenshots/     模型要求看截图时才会有的截图
```

导出打包成 zip 走系统分享面板 —— Android 11 之后 `/Android/data` 对文件管理器
和 USB 都不可见，把日志丢在私有目录里用户根本拿不到。

## 截图

| 主界面 | 悬浮控制面板 | 设置页 |
|---|---|---|
| ![主界面](docs/screenshots/01-主界面.png) | ![悬浮窗](docs/screenshots/02-悬浮窗.png) | ![设置页](docs/screenshots/03-设置页.png) |

## 环境要求

| | |
|---|---|
| 手机 | Android 9（API 28）及以上 |
| 构建 | JDK 17、Android SDK 35 |
| 模型 | 任意 **OpenAI 兼容** 的模型。默认只用文字，纯文本模型也能跑；模型要图时才需要它能看图 |
| 电脑 | 只在构建时需要；日常使用完全不需要 |

已实测：Android 15 模拟器、DeepSeek API（`deepseek-flash`）。

> 说明：默认流程**不发图片**，所以纯文本模型也能操作界面（靠控件树）。
> 但如果界面读不出元素、模型要求看截图，纯文本模型就会报 400，
> 应用里会把这类错误翻译成「这个模型不接受图片输入」。

## 构建与安装

```bash
git clone <repo-url>
cd AI手机

# 直接用 gradlew，不需要额外装 Gradle
./gradlew assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

装到手机：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

或者用 Android Studio：`File → Open` 选**项目根目录**（里面有 `settings.gradle.kts`），
等 Gradle 同步完点运行。

<details>
<summary>构建报错怎么办</summary>

| 报错 | 处理 |
|---|---|
| `Connection refused` 下载 Gradle | 国内网络问题，wrapper 里已换成腾讯云镜像。清一下缓存重试：`rm -rf ~/.gradle/wrapper/dists/gradle-8.9-bin` |
| `Could not find GradleWrapperMain` | 跑 `bash setup_gradle.sh` 补 wrapper |
| JDK 版本问题 | Android Studio 里把 Gradle JDK 改成 `jbr-17` |
| `SDK location not found` | 建 `local.properties` 写 `sdk.dir=<你的 SDK 路径>` |
| `plugin classpath entry points to a non-existent location` | 项目路径含中文导致的，见 [`docs/DESIGN-NOTES.md`](docs/DESIGN-NOTES.md)。`gradle.properties` 里已用 `in-process` 编译绕开，顺带快很多 |

</details>

## 快速开始

```
1. 装上应用，打开

2. 右上角 ⚙ → 填写「模型」那一栏
     接口地址   https://api.deepseek.com
     API Key    你的 Key（默认打码显示）
     模型       deepseek-flash
     图片精度   original（手机 UI 文字多，别选 low）

3. 设置页 →「操作授权」→「前往授权」
   在系统设置里开启「纸盒」的无障碍服务

4. 设置页 →「操作授权」→「悬浮窗」→ 去开启
   允许「显示在其他应用上层」

5. 回到主界面，在底部输入框发一条任务，比如「打开设置」
```

跑起来之后：中间是日志流，手机屏幕上能看到 AI 在点，
左上角悬浮窗显示它的**下一步打算做什么**，觉得不对随时按急停。

**建议从简单的任务开始**，比如「打开设置」「打开 WiFi 页面」，
别一上来就试「帮我订外卖」。

### 不花 API Key 先试一遍

仓库里带了一个假的模型服务，按剧本返回动作：

```bash
python3 tools/mock_llm.py
# 模拟器里接口地址填 http://10.0.2.2:8765
```

剧本里故意混了 markdown 围栏、编造的动作名、越界坐标，
用来验证防御性解析真的在工作。加 `MOCK_DELAY=2` 可以让它每步慢 2 秒，
方便观察悬浮窗。

它还会顺带校验**上下文缓存前缀有没有保住**，每次请求打印复用了几条消息。

## 配置

**模型**

| 项 | 说明 |
|---|---|
| 接口地址 | 任意 OpenAI 兼容端点。会自动补 `/v1/chat/completions` |
| API Key | 存储在本机应用私有目录，默认打码显示 |
| 模型 | 必须支持图片输入 |
| 图片精度 | `original` 保留原图；`low` 压到 512×512（**界面文字会糊，不建议**） |

**操作授权**

| 项 | 说明 |
|---|---|
| 操作方式 | 目前只有「无障碍」。ADB / Shizuku 在规划中 |
| 悬浮窗 | 特殊权限，需要手动去系统设置开 |

**开发者设置**

| 项 | 默认 | 说明 |
|---|---|---|
| 最大步数 | 30 | 每步一次模型调用，这个值就是成本上限 |
| 自动清空上下文 | 不清空 | 见下方说明 |
| 开启记忆 | 关 | 让 AI 把上下文沉淀成「用户洞察」 |
| 保存记忆 | 开 | 清空上下文前先写成 md 存下来 |
| 保存日志 | 开 | 每次执行写 run.log |
| 保存截图到本地 | 开 | 截图落盘（默认流程不截图，模型要图时才有） |

> **「自动清空上下文」默认是关的，这是刻意的。**
> 服务端的上下文缓存按前缀匹配，清掉上下文等于下次全部按未命中计价，
> 反而更贵。清空现在只是个手动开关，不该是默认行为。

## 项目结构

```
app/src/main/
├── AndroidManifest.xml                        权限与服务注册
├── res/
│   ├── drawable/ic_box_logo.xml               纸盒图标（启动图标 + 界面 logo 共用一份）
│   ├── mipmap-anydpi-v26/                     自适应图标
│   └── xml/                                   无障碍配置 / FileProvider / 网络安全
│
└── java/com/aiphone/assistant/
    ├── MainActivity.kt                        入口 + 状态中枢
    ├── ChannelController.kt                   通道管理：探测、截图、读控件树
    │
    ├── a11y/
    │   ├── AutoService.kt        ★ 无障碍服务本体（眼睛 + 手）
    │   └── UiNode.kt             ★ 控件树解析与压缩成编号列表
    │
    ├── agent/
    │   ├── Agent.kt              ★ 决策循环、防死循环、上下文管理
    │   ├── AgentPrompt.kt        系统提示词
    │   └── ActionParser.kt       模型输出的防御性解析
    │
    ├── llm/LlmClient.kt          OpenAI 兼容客户端（HttpURLConnection，零依赖）
    │
    ├── channel/
    │   ├── DeviceChannel.kt      通道接口
    │   └── AccessibilityChannel.kt  动作分派
    │
    ├── touch/
    │   ├── TouchMethods.kt       16 种触控方式清单
    │   └── GestureSpec.kt        统一手势模型（按下/移动/停顿/松手）
    │
    ├── overlay/                  悬浮控制面板
    │   ├── OverlayService.kt     两个独立窗口 + 前台服务
    │   └── OverlayBus.kt         与 Agent 的通信桥
    │
    ├── skill/                    技能：模型主动索取界面之外的信息
    │   ├── Skill.kt              技能接口 + 运行上下文
    │   ├── SkillRegistry.kt      注册表：目录、按需文档、执行
    │   └── AppListSkill.kt       list_apps：应用列表 + 包名
    │
    ├── log/                      日志与导出
    ├── memory/                   上下文与用户洞察
    ├── data/                     设置项与持久化
    └── ui/                       Compose 界面

tools/
└── mock_llm.py                 假模型服务，不花 Key 就能验证链路

docs/
├── DESIGN-NOTES.md             为什么这么做 + 踩过的坑
├── screenshots/
└── mockups/                    设计手稿
```

**零第三方运行时依赖**：除了 AndroidX / Jetpack Compose，
HTTP 用 JDK 自带的 `HttpURLConnection`，JSON 用 Android 自带的 `org.json`。
少一个依赖就少一份体积、一份版本冲突、一份构建风险。

## 已知限制

| 限制 | 说明 |
|---|---|
| **截不到安全窗口** | 银行 / 支付类页面有 `FLAG_SECURE`，系统不允许截图。这是有意为之，绕不过去 |
| **截图有限流** | 无障碍截图有平台级频率限制（约 1 秒一次）。一步步调模型的节奏通常撞不到，代码里也做了重试 |
| **主流 App 可能封堵** | 微信、支付宝等对自动化操作有风控。字节的豆包手机助手最终也主动退让了。**这不是技术问题** |
| **自绘控件读不到控件树** | 游戏、地图这类自绘界面只能靠坐标定位 |
| **没有多指自定义路径** | 双指缩放能跑，三指以上的自定义路径还没暴露接口 |

## 路线图

- [x] 无障碍通道（截图 / 控件树 / 16 种触控）
- [x] 日志系统 + 导出
- [x] AI 决策循环 + 防死循环
- [x] 悬浮控制面板 + 三路急停
- [x] 上下文无限继承 + 缓存友好
- [ ] 记忆的 AI 归纳（`InsightDistiller` 目前只做原样转存）
- [ ] ADB / Shizuku 通道（能截安全窗口，代价是重启要重新授权）
- [ ] 自定义多指手势路径
- [ ] 跨任务上下文继承

## 开源致谢

### 运行时依赖

只有 AndroidX 和 Kotlin 官方组件，均为 **Apache-2.0**：

- [AndroidX Core KTX](https://developer.android.com/jetpack/androidx) ·
  [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) ·
  [Activity Compose](https://developer.android.com/jetpack/androidx/releases/activity)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI / Material 3 / Material Icons Extended）
- [Kotlin](https://kotlinlang.org/) 与 [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)

构建工具：[Android Gradle Plugin](https://developer.android.com/build) · [Gradle](https://gradle.org/)

### 设计参考

这些项目**没有进入依赖树**，但设计上确实受了启发，一并致谢：

- **[Roubao（肉包）](https://github.com/Turbo1123/roubao)** — MIT。
  同样用无障碍 + 纯视觉做安卓自动化。本项目的悬浮窗借鉴了它三点做法：
  用 `View.INVISIBLE` 而不是 `GONE` 隐藏（否则窗口尺寸会退化）、
  加 `FLAG_KEEP_SCREEN_ON`（否则屏幕熄灭会让截图失败）、
  以及"隐藏 100ms → 截图 → 显示"的时序。
  **只参考了做法，没有拷贝代码。**

- **[UI-TARS](https://github.com/bytedance/UI-TARS)** — Apache-2.0，字节 Seed + 清华的
  原生 GUI Agent 模型。本项目的动作空间设计受它启发：
  把 `wait` 作为一等动作而不是脚本里的固定延时、
  坐标用标签包裹以便解析。

- **[MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)** — AGPL-3.0。
  把 MAA 的自动化能力原生跑在安卓上，用 Shizuku + scrcpy + 虚拟显示器实现
  "游戏在虚拟屏里跑、用户照常刷手机"。本项目只研究了它的架构思路
  （**因为是 AGPL，一行代码都没有参考或拷贝**）。

- **[Shizuku](https://github.com/RikkaApps/Shizuku)** — Apache-2.0。
  早期评估过作为操作通道（能力更强、能截安全窗口），最终因为
  "无 root 时每次重启都要重新用无线调试授权一次"的体验代价而放弃。

### 模型服务

默认配置使用 [DeepSeek API](https://platform.deepseek.com/)。
本项目不绑定任何特定服务商 —— 任何 OpenAI 兼容且支持图片输入的端点都可以用。

## 免责声明

这是一个**个人自用**的自动化工具，不是为大规模分发设计的。

- 它会**真实地操作你的手机** —— 能点任何东西，包括付款按钮和删除按钮。
  目前**没有**危险动作拦截，请在可控的场景下使用。
- 使用前请确认你有权操作目标设备和目标应用。
- 自动化操作可能违反部分应用的服务条款，由此产生的后果由使用者承担。
- 无障碍权限是安卓系统中被监控最严的权限之一，部分应用商店会拒绝上架
  申请了该权限的应用。

## 许可证

尚未指定。如果你打算基于本项目二次开发或分发，请先开个 issue 讨论。

---

**相关文档**：[设计笔记](docs/DESIGN-NOTES.md) —— 为什么选无障碍、悬浮窗踩过的三个坑、
上下文缓存怎么做才命得中、中文路径怎么把 Kotlin 编译搞坏的。
