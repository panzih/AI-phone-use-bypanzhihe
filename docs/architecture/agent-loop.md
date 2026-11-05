# Agent 主循环

> 对应代码：`app/src/main/java/com/aiphone/assistant/agent/`
> 最后更新：2026-10-31 / 0.12.1

这篇文档只讲**一次任务从开始到收尾**在代码里怎么流动，以及每一步的权责边界。
提示词协议、动作字段、坐标换算不在这里展开，见 `AgentPrompt` / `ActionParser` 的注释。

---

## 1. 入口与状态归属

```
用户点发送 / 定时任务到点
        │
        ├─ MainActivity.runTask() ─────────────┐
        │                                      │
        └─ ScheduleReceiver → PendingRunTask ──┤
                                               ▼
                                   TaskHost.scope（Application 级）
                                               │
                                               ▼
                                   Agent.run(task)  ← 对外门面
                                               │
                                               ▼
                                   AgentSession.run(task)
```

| 东西 | 放在哪 | 为什么 |
|---|---|---|
| 任务协程、运行状态、日志、对话、模型历史、上下文快照 | `task/TaskHost`（挂在 `PaperboxApp` 上） | Activity 旋转/重建不能取消任务；进程被杀时用 `task_in_flight.txt` 明确报告中断 |
| 单次任务的状态与主循环 | `agent/AgentSession` | 一次任务一个实例；`Agent` 只是稳定 API 门面 |
| 悬浮条、急停通知 | `overlay/OverlayService` | 任务要在别的应用上面继续显示；无悬浮窗权限时仍保持前台服务 |
| 每步日志、截图 | `log/RunLogger` | 每次运行独立目录，逐行落盘，进程被杀也能留下最后几行 |

`Agent` 不持有业务逻辑，只把构造参数交给 `AgentSession`，并暴露
`run` / `review` / `finalHistory` / `stopRequested`。`MainActivity`、定时任务和测试
都不需要知道内部拆成了几块。

---

## 2. 主循环一图流

```
AgentSession.run(task)
│
├─ 0. 重置本趟状态（步数、图片额度、通道切换标记、指纹判据…）
├─ 1. 通道自检
│     controller.probe()                  → 无障碍/副屏能不能用
│     controller.readUiTree()             → 真的读得到控件树吗（MIUI 假连接）
│     controller.screenSize()             → 坐标换算基准
│     固定 system prompt + 记忆快照        → 前缀缓存第 0 个 token，必须逐字稳定
│
└─ for (step in 1..stepLimit)
     │
     ├─ awaitResume(step)                 ← 暂停点：用户暂停/急停在这里生效
     │
     ├─ observeStep(step)                 ← AgentStepObserver
     │    ├─ 自动切副屏状态机（AutoSwitchWatcher）
     │    ├─ 用户接管检测（ChannelMigration）
     │    ├─ 端侧弹窗建议（LocalRuleEngine，只建议不点）
     │    ├─ readUiTree() + 前台包名 + 分辨率
     │    ├─ 页面指纹 / 重复动作 / 读不到树的止损
     │    └─ 拼出本步 interruptions（通道、可读性、卡死提示）
     │         └─ Skip：这一步被迁移/接管吃掉 → 下一步
     │         └─ Stop：已经收尾 → return
     │
     ├─ askModel(...)                     ← AgentModelExchange
     │    ├─ 拼 user 消息（控件树 / 截图 / 端侧提示 / 上一步结果）
     │    ├─ 需要图就截，需要技能就取，超限就回灌原因
     │    ├─ 急停看门狗：请求进行中也能 abort
     │    └─ 解析成 Parsed（动作批 / need_image / finished / failed）
     │         └─ Stop：已经收尾 → return
     │
     ├─ 没有动作？
     │    ├─ 记一次解析失败，把原因塞进 lastResult 让模型重出
     │    └─ 连续 MAX_PARSE_FAILS 次 → finish(false)
     │
     └─ executeBatch(...)                 ← AgentActionExecutor
          ├─ 单批动作预算 / 浏览器打开次数 / 截图次数
          ├─ 逐个动作：
          │    ├─ 点击前复核（TapReview，可选增益，失败按原点执行）
          │    ├─ 高风险闸门（默认拦截 / 打开后模型二次确认）
          │    ├─ 需要时藏悬浮条 → 注入 → 水波 → 显示悬浮条
          │    ├─ 等待界面稳定（awaitStable）
          │    └─ 动作后验证（verifyAction，有副作用的不自动重试）
          ├─ 特殊动作：browser_open / browser_html / browser_eval /
          │            dismiss_dialog / capture / open_app / sleep / use_skill
          └─ 把本批结果拼回 lastResult
               └─ Stop：已经收尾 → return

跑满 stepLimit → finish(false, "到了最大步数…")
```

`AgentSession.run` 只做编排；上面每个阶段都是 `AgentSession` 的扩展函数，
状态仍留在 session 里，拆的是职责不是数据。

---

## 3. 每一步的权责

### 3.1 观察（`AgentStepObserver`）

- **通道迁移**发生在读树之前：换屏后旧编号全部作废，`onChannelSwitched()` 会清零
  指纹/重复动作判据，并给模型留一行“已切换”的说明。
- **端侧弹窗规则**只输出建议：`localDismissAdvice()` 找到安全的关闭按钮后，
  把“建议点哪个编号”拼进本步消息。端侧**不自己点**（0.10.1 起的权责），
  真正动手只在模型显式下发 `dismiss_dialog` 时。
- **读不到控件树**和“这一屏没有元素”是两件事：`UiTreeRead.rootAvailable` 区分
  “无障碍没生效”和“界面确实是空/自绘”，止损计数只对前者累加。
- **卡死判据**用控件树指纹 + 重复动作签名，不用截图 MD5；界面持续动画时靠
  `awaitStable` 的 hard cap 兜底。

### 3.2 问模型（`AgentModelExchange`）

- 系统提示词是前缀的第 0 个 token，**逐字稳定**；记忆只在上下文新开时注入一次，
  之后整段沿用同一份快照。提示词版本 / 模型名 / 技能目录任一变化，旧上下文按新开处理
  （见 `ContextStore` 与 `TaskHost.initialize`）。
- 默认不发截图。模型用 `need_image` 要，或把 `capture` 放进动作序列；
  副屏/读不到树时自动带图，因为那是模型唯一的信息来源。
- `use_skill` 和要图一样是“本轮不算一步”的机制：取回结果后重新问，不消耗步数。
- 急停看门狗在请求进行中轮询 `isStopped()`，命中就 `llm.abort()`；
  暂停/停止在等待稳定、截图、模型请求里都能生效。

### 3.3 执行（`AgentActionExecutor`）

一批动作按顺序执行，单批有预算；任何一个动作失败默认**中止本批**（后面的动作
往往依赖前一个动作打开的页面）。

每个动作执行前：

1. **点击前复核**（可选）：截当前屏 → 裁落点附近 → 画十字 → 独立模型一问一答。
   复核失败/模型不收图时按原点执行 —— 它是增益，不是安全边界。
2. **高风险闸门**（执行层，不靠提示词）：
   - `allowHighRiskActions = false`（默认）→ 直接拦截，写日志，本批不算完成；
   - 打开 → 调 `RiskReview` 用独立模型二次确认；请求失败/输出无法解析一律拒绝
     （fail closed），结果写进运行日志。
3. **`browser_eval` 默认关闭**：执行层在调 JS 前拦截；打开后仍受高风险闸门约束。

执行后：

- `awaitStable` 等到界面稳定（或超时/急停/通道切换）；
- `verifyAction` 判断动作有没有生效；有副作用的动作**不自动重试**，避免重复支付/发送；
- 浏览器动作会把通道切到 `BrowserActivity`，下一步读的是 DOM 摘要而不是控件树。

### 3.4 收尾（`AgentRuntime`）

- `finish(success, message)` 统一写 `RunLogger`、`ContextStore`、`OverlayBus` 和界面回调。
- “模型说完成”不等于完成：端侧会检查这一批有没有被拦截的高风险动作、有没有
  读不到树的步骤、有没有未验证的动作；不满足就拒绝收尾并让模型继续确认。
- 任务协程在 `TaskHost.scope` 里；进程被杀时 `task_in_flight.txt` 留下，
  下次启动由 `TaskHost.reportInterruptedTask()` 明确报告“任务已中断”。

---

## 4. 安全边界（都在执行层，不依赖提示词）

| 边界 | 默认 | 代码位置 |
|---|---|---|
| 高风险动作（支付/转账/删除/发送/授权/验证码） | 拦截 | `AgentActionExecutor` + `RiskReview` |
| `browser_eval` | 关闭 | `AgentActionExecutor` + `AppSettings.allowBrowserEval` |
| `browser_open` 协议 | 只允许 http/https | `BrowserUrl` + `BrowserActivity` 导航层 |
| 公网明文 HTTP API | 发送前拒绝 | `EndpointSafety` + `LlmClient` / `ModelListFetcher` |
| 上下文超窗 | warn/stop + 输出预留 | `AgentLimits.ContextBudget` |
| 急停/暂停 | 每个等待点轮询 | `AgentSession.isStopped()` / `awaitResume` |
| 导出日志 | 文本脱敏 + 风险提示 | `LogSanitizer` / `LogExporter` |

原则：**提示词负责“让模型知道规则”，执行层负责“规则真的生效”**。
模型可以被屏幕/网页内容诱导，所以真正的拒绝必须落在 Kotlin 里。

---

## 5. 每步注入的「操作环境卡」（已实现，2026-11-06）

目标：让模型每一步都明确知道三件事 —— **我现在在哪个模式**、**哪块屏**、**这一轮拿到了什么**，
并且能**指定下一轮要什么**。现在这些信息散在 `noTreeReason` 的几个常量里，只在"读不到树"
时才说，正常步骤里完全缺失。

### 5.1 注入位置与格式

放在 `# 当前状态` 之后、`# 界面元素` 之前，位置固定（每轮都出现，模型不会漏看）：

```
# 操作环境
模式：操作软件（无障碍）
屏幕：前台（主屏）
本轮输入：控件树 42 项
下一轮可选：控件树 / 截图 / 两者
```

各组合能给的输入是硬约束，不是建议：

| 模式 | 屏幕 | 本轮能给的输入 |
|---|---|---|
| 操作软件（无障碍） | 前台主屏 | 控件树 / 截图 / 两者 |
| 操作软件（shell） | 副屏 `displayId=N` | **只能截图**（系统不给第三方读副屏树） |
| 内置浏览器 | 纸盒自己的 `BrowserActivity` | DOM 摘要 / 截图 / 两者 |

技能目录**不重复注入**（已经在系统提示词第 0 段；重复一遍会白白打断前缀缓存）。

### 5.2 模型怎么指定下一轮

JSON 顶层新增一个字段，一次性生效，不写就是控件树：

```json
{"view": "tree"}    // 控件树（默认）
{"view": "image"}   // 只截图 + 控件计数与建议
{"view": "both"}    // 截图 + 完整控件树
```

兼容老输出：`need_image` / `need_screenshot` 仍然等价于 `view:"image"`。

生效时机：

- 本轮**没有动作**、只有 `view` → 立刻重新观察并重问，**不算一步**（复用 `need_image` 现有机制）；
- 本轮**有动作** → 动作执行完、`awaitStable` 之后的下一轮换视图。

为什么必须"一次性"：历史里的图每次请求都要重发，`view` 一旦变成常驻状态，
token 会按步数线性上涨。所以默认回落到控件树，想连续看图就每轮再写一次。

### 5.3 给图时同步读树 + 阈值建议

只要给了图（`image` / `both`），就**同时读一次控件树，只用于计数和建议**，不发列表：

| 条件 | 追加的一行 |
|---|---|
| `count >= 10` | 这一屏能读到 N 个控件。要精细定位就下一轮写 `"view":"tree"` 拿编号列表。 |
| `count < 5` | 这一屏只有 N 个控件（自绘 / 网页残留），只能按图上坐标点。 |
| 读不到根节点 | **不追加计数** —— 和 `rootAvailable=false` 一个口径，别把"看不到"说成"空页"。 |

两个阈值各有分工：5 是"少到不值得要树"，10 是"多到值得建议要树"，中间地带**不提示**。
每步都劝模型换视图，既占 token 也干扰判断。

### 5.4 先修一个事实：浏览器模式读的是 DOM，不是无障碍树

`BrowserChannel.readTree()` 走的是 `BrowserDom.render`（页面 DOM 摘要），不是系统无障碍树。
所以"WebView 只读到几个退出按键"通常不是网页的问题，而是**通道没切过去**：
`ChannelController.mode` 还是 `A11Y`，读到的自然是 `BrowserActivity` 自己的无障碍树
（工具条 + 返回键）。

要做的是：环境卡如实报"模式"；并且当**模式=操作软件、前台却是 `BrowserActivity`**，
或元素数异常少时，明确建议一句"网页类界面请先 `browser_open` 切到内置浏览器，
DOM 摘要比无障碍树完整"。这比让模型自己从"只有三个按钮"里猜出原因可靠。

### 5.5 模型自己换屏（第二阶段）

```json
{"action":"move_app","to":"background"}   // 把当前目标应用拉到副屏
{"action":"move_app","to":"foreground"}   // 拉回主屏
```

- 前提：Shizuku READY + API 级别够，不够就返回中文原因，不改工具集；
- 复用 `ChannelMigration` 现有的迁出 / 回迁路径，不另写一套；
- 换屏后旧编号作废，照常走 `onChannelSwitched()` 清指纹与重复动作判据；
- **单任务最多 3 次**，超过返回"已到换屏上限，请在当前屏上想办法"。
  没有次数上限的话，模型会因为某一屏显示异常而每步搬一次，比不搬更糟。

存在理由是真的：有些界面在副屏渲染异常（`SurfaceView`、部分厂商 ROM 的弹窗），
这时候模型需要主动拉回主屏，而不是干等或盲点。

### 5.6 边界

- 模式切换必须走显式动作（`browser_open` / `move_app`），不允许"读着读着自己换了"；
- `view` 不做持久设置（token 会线性上涨）；
- "该给树还是给图"由模型决定，端侧**只给建议**（沿用 0.10.1 的权责划分）。

---

## 6. 相关测试

| 测试 | 钉住什么 |
|---|---|
| `ActionParserTest` | 模型输出归一化、坏动作丢弃、browser_open 白名单 |
| `AgentSwitchPolicyTest` / `RunGuardsTest` | 通道切换与收尾闸门 |
| `RiskGateTest` | 高风险词表与 fail-closed 解析 |
| `ContextBudgetTest` | 模型窗口预算的 warn/stop |
| `WaitPolicyTest` / `TapReviewTest` | 等待与点击复核的纯函数部分 |
| `TaskHostTest` | 应用级任务状态与进程死亡标记 |
| `BrowserUrlTest` / `EndpointSafetyTest` | 网页与 API 地址白名单 |

设备冒烟测试见 [docs/testing-device.md](../testing-device.md)。
