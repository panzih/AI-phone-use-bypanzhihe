# 待办路线图

> 最后更新：2026-11-06（第 0 节感知层全部落地、§1–3 清空；只剩第 4 节的外部输入项）
> 这里只列**还没做**的和**明确不做**的；已经做完的看 [CHANGELOG](../CHANGELOG.md)，
> 2026-10-27 评估的整改进度看 [整改清单](plan/2026-10-27-整改清单.md)。

优先级原则：**先补测试兜底（防回归），再修低风险小坑，需要真机或外部输入的排最后**。
第 0 节（感知层）不排在这条线里 —— 它决定"点得准不准"，是命中率的地基，碰到相关代码就顺手做。

---

## 0. 感知层（命中率地基）—— 已全部落地（2026-11-06）

> 来源：2026-10-31 对照 `ccch1mneyyy/dsh-Go` v1.0.4（Flutter + 内置 proot Linux，195MB）的技术拆解。
> 结论：**真正拖累命中率的不是"截图上没画编号"，而是快照本身**——元素上限、截断顺序、
> 文本没有语义结构、密码不屏蔽。截图编号排在最后，因为它依赖前两项先做对。
>
> 2026-11-01 追加设计：[agent-loop.md 第 5 节](../architecture/agent-loop.md) ——
> 每步注入"操作环境卡"，由模型指定下一轮要控件树还是截图。
> **2026-11-06：第 5 节设计全部实现**，实现批次见分支 `tasks/docs-2026-11-04`
> （f77ea91 → 3b99118 → 87f6034 → 171eac2）。下面保留原条目与实现要点，
> 供以后对照。

- [x] **每步注入「操作环境卡」**：`AgentPrompt.envCard` 固定四行（模式 / 屏幕 /
  本轮输入 / 下一轮可选），插在「# 当前状态」之后、「# 界面元素」之前；`ChannelController`
  新增 `modeLabel` / `screenLabel` 如实报通道。
- [x] **`view` 字段：模型指定下一轮要树还是图**：`{"view":"tree"|"image"|"both"}`，
  一次性生效（`AgentSession.pendingView`，observeStep 消费后清空）；无动作时解析层直接转
  `need_image`（立刻重问且不计步）；有动作时下一步生效；与 `need_image` 同给时后者优先。
  实现要点：`view:image` 的轮次树**仍照常读**（指纹/卡死判据不能断），只是列表不发给模型。
- [x] **给图时同步读树 + 阈值建议**：`AgentPrompt.viewImageOnlyReason` ——
  `count >= 10` 建议换回树；`count < 5` 明说只能按坐标点；读不到根节点不报数；5–10 不提示。
- [x] **浏览器模式的环境说明**：环境卡如实报「内置浏览器」；前台是 `BrowserActivity`
  但通道没切时（`BrowserDriver.isAlive()`），中断建议直接指路 `browser_open`。
- [x] **`move_app` 动作**：`{"action":"move_app","to":"background"|"foreground"}`，
  复用 `ChannelMigration` 的迁出/回迁路径；**单任务最多 3 次**（`AgentLimits.MAX_MOVE_APP`，
  AgentSession.moveAppCount 计数）。
- [x] **元素上限与截断顺序**：`keepNodesDetailed` 改为全量遍历（500ms 预算 + 3000 节点
  硬顶）→ **空间排序**（从上到下、行容差按屏高切 24 行）→ **结构优先预算**（可交互全留，
  纯文字填空位，`remaining` 如实上报"还有约 N 项"）。没有照抄对方的 2000。
- [x] **屏幕文本的语义结构**：四件全做 —— ①中文角色（`UiNode.roleOf`：按钮/输入框/
  开关/滑块…，认不出的保留类名）；②顶部/中部/底部分区表头 + 可滚容器缩进（render）；
  ③可点击/可输入行吸收非交互后代文字（上限 5 条 / 60 字，被吸收后代不再单列）；
  ④`⚠` 敏感标记（复用 `AgentLimits.HIGH_RISK_WORDS`）与 `+` 相对上一帧新增标记。
- [x] **节点字段缺失**：`UiNode` 补 `isPassword` / `selected` / `heading` /
  `stateDesc`（API 30+）/ `rangeText`（API 30+ RangeInfo，滑块当前值）/ 纵横滚动位
  （从 ACTION_SCROLL_* 推出）。全部带默认值，旧构造点不受影响。
- [x] **密码框内容不屏蔽**：`isPassword` 的节点文本在**解析层**即替换为定长掩码
  `••••`（`UiNode.PASSWORD_MASK`）—— 不进提示词、不进日志、不可从任何导出文件复原；
  身份匹配两帧掩码一致，不受影响。
- [x] **祖先查找缺 bounds 命中校验**：`AutoService.clickNode` 的祖先攀爬补了
  `rect.contains(centerX, centerY)`；不覆盖的祖先跳过继续往上，全都覆盖不住就如实失败
  （上层退回坐标手势）。
- [x] **亮屏 / 解锁 / 无障碍连接闸门**：`AgentRuntime.screenUnlockGate()` 在任务开跑前
  拦（灭屏/锁屏直接给中文原因）；任务跑到一半灭屏/锁屏由 `screenOffAdvice()` 拼进
  user 消息，让模型如实收尾。
- [x] **抓树的时间预算**：`keepNodesDetailed` 增加 `budgetMs`（默认
  `UiTreeParser.TREE_BUDGET_MS = 500`，每 32 个节点查一次时钟），超时如实上报
  （`budgetExceeded`，日志写明"深树截断"）。abort 代际未做 —— 目前 `stopRequested`
  布尔 + 时间预算已覆盖实际会卡死的场景，真需要再补。
- [x] **`setText` 重试链与 IME 回车**：`writeTextWithRetry`（focus → setText → 失败则
  click → refresh → 再 setText；重试只对可编辑节点做，防误触按钮）；新动作
  `press_enter`：无障碍 API 32+ 走 `ACTION_IME_ENTER`（低版本补 `\n`）、shell 走
  keyevent 66、网页派发表单提交。
- [x] **近黑帧判定**：`a11y/BlackFrame` —— 降采样 32×32、≥99% 采样点亮度 < 8 才算近黑
  （深色主题不误伤）；`AgentModelExchange` 与执行器的 capture 路径都按"截不到"处理，
  并给模型 FLAG_SECURE 的中文说明。
- [x] **提示词 / 工具集哈希测试**：`PromptFreezeTest` —— 整篇系统提示词的 SHA-256 钉死
  （有意修改 = 连哈希一起更新 + 升 `AgentPrompt.VERSION`）；动作名"只增不改"：
  0.12.x 已发布的 18 个动作名一个都不许动。
- [x] **`collect`（批量翻页收集）**：`{"action":"collect","direction":"down","pages":5}`，
  连滚多页、每页读一次元素列表拼成一条结果；停止条件三条：页数（默认 5、最多 10）/
  时间（20s）/ 连续两页内容相同（到底了）；总量 20k 字封顶；中途可暂停/急停/切换。
- [x] **意图类工具**：`set_alarm`（AlarmClock SKIP_UI，manifest 补 SET_ALARM 权限）/
  `sms_draft`（只开**草稿**，发送由用户确认）/ `open_nav`（geo:）/ `share_text`（分享面板
  由用户点）/ `clipboard_get`（结果限 500 字，提示词写明只在任务明确要求时用）/
  `clipboard_set`。全部走系统 Intent，不靠 UI 点。
- [x] **截图编号（排最后）**：前提两项（空间截断 + 语义结构）先做完才动它。
  `a11y/NumberedShot` —— 元素列表随图一起发时，把同一套编号按截图分辨率换算后画成
  圆标（图上的数字 = 列表里的 [n]）；任何一步失败都退回原图（编号是增益，不弄丢截图）；
  `view:image`（只有图没有列表）不编号。
- [ ] **副屏的控件树与免 Shizuku 通道**（长期，短期不改）：副屏现在只能截图猜坐标
  （平台不给第三方读副屏无障碍树，实测记录见 `DisplayChannel.kt:24-58`），且强依赖 Shizuku。
  对方的 self-ADB（通知里输配对码）是我们完全没做的部分。先确认 shell 侧能不能枚举
  非默认显示器的窗口，再决定做不做。

---

## 1. 测试兜底 —— 已落地（2026-11-06）

- [x] **入口层测试**：`EntryGuard`（agent 包）把入口的两条可测判定抽成纯函数 ——
  模型配置四组合（免费/自定义 × 有/无 Key，与网络层共用 `LlmConfig.missingApiKey`）
  与"忙碌时收到输入的处置"。结构性约定（提前 return 的检查必须在 startForegroundService
  之前）写在 EntryGuard 的 KDoc 里靠评审保住；`EntryGuardTest` 7 例钉住判定本身。
- [x] **任务中途消息的回归测试**：行为已改（见第 3 节），判定抽成
  `EntryGuard.busyInput(isRunning, isPaused)` —— 运行中必须返回 `PROMPT_BUSY`
  （提示、不吞），暂停中返回 `MERGE_AS_NOTE`，回归测试钉死。

## 2. 小修 —— 已落地（2026-11-06）

- [x] **Key 加密失败不再静默存明文**：`SecretCrypto.seal()` 加密异常改为**返回 null**；
  `SettingsStore.save` 不写明文（保住之前存过的值，没存过就写空串）并返回警告，
  MainActivity 明示给用户。`SecretCryptoTest` / `SettingsStoreTest` 共补 9 例
  （含"盘上绝不能出现明文"）。
- [x] **设置页"模型模式"加选中态**：核对后确认**已由 0.12.1 的界面重写解决**
  （`SegmentedControl` 的选中项用 `surface` 底浮起 + 全亮文字，未选中透明 + 灰字，
  亮暗两套主题对比都足够）；本轮无需改动，留此记录防止再查一遍。

## 3. 体验问题 —— 已落地（2026-11-06）

- [x] **键盘遮挡悬浮条**：发送后 `clearFocus()` 收起键盘；悬浮条跟随输入法 ——
  悬浮窗拿不到 IME insets（系统只发给获焦窗口），由主界面读 `WindowInsets.ime`
  经 `OverlayBus.reportImeInset` 转发，`OverlayService.onImeInset` 把悬浮条抬高。
  **Android 10 及以下系统不报键盘 insets，悬浮条维持原位（平台限制）**。
- [x] **任务运行中发的消息不再被静默吞掉**：现在给 toast
  （"先按「暂停」再发就能并进下一轮，新任务等当前结束"），输入框原样保留。
- [x] **副屏连续多步没画面就中止**：`blindNoFrameSteps` 只对盲通道计数
  （有树的通道截图只是参考），连续 3 步（`BLIND_NO_FRAME_STOP_STEPS`，给
  "第一步 open_app 切应用"留余量）抓不到画面就止损，给用户中文原因。
- [x] **导出日志兜底 + 进度**：`exportEverything` 带 `onProgress`（"正在打包运行 3/12…"，
  显示在导出按钮下方的 LoadingRow）；分享面板拉不起来时把 zip 复制进系统「下载」
  （MediaStore，API 29+ 免权限），拿不到就用一条可读的话说明。
- [x] **定时任务页**：表盘换 M3 `TimeInput`（24h 表盘 12/24 两套数字重叠、
  点分钟要连点两次的问题一起消失）；选了过去的时间当场明示
  "第一次会在明天 HH:MM 执行"；添加/确认后 `clearFocus()`，键盘不再常驻。
- [x] **关于页项目地址**：整行可点（打开浏览器）不变，右侧补复制按钮
  （进剪贴板 + toast"已复制"）。
- [x] **首次安装的权限引导**：首启弹一次欢迎引导（无障碍 + 悬浮窗两项各是干什么的），
  确认进「权限」页、稍后也能跳过；`onboardingDone` 落设置，确认/关掉都算看过，不反复打扰。
- [x] **录制入口的实验开关**：硬编码常量 `RECORDING_ENTRY_ENABLED` 删除，改为
  `AppSettings.recordingEntry`（默认关）——设置页「更多设置」里开关；抽屉入口与
  输入框快捷指令两处都看这个开关。

## 4. 需要真机或外部输入

- [ ] **真机验收**：副屏自动回迁、副屏画面（渲染/截图/触控三段）、release(R8) 冒烟、权限页、
  录制的"录→学→用"全流程；以及本轮感知层改动的真机抽测（环境卡/view 字段/collect/
  意图类动作/截图编号）。模拟器上这几项要么是平台限制（拿不到副屏画面），
  要么结论不可信。
- [ ] **GitHub Release**：远端已有 `v0.10.5` / `v0.10.6` 的 tag，但一个 Release 对象都没建。
  要定三件事：发不发、用开发者签名还是正式签名、发哪个版本。
  **正式发布签名链和发布工程化（混淆/可复现/GitHub Release）暂缓**，等签名方案确定后再做。
- [ ] **提示词里的身份段落**：`AppInfo.BILIBILI_URL` 还空着，拿到作者 B 站主页地址后再补，
  并同步提示词版本号（注意：改系统提示词要连 `PromptFreezeTest` 的哈希一起更新）。

## 5. 明确不做

- **高风险动作的强制人工确认**：不做"每次支付/发送/删除前都弹一次确认"。
  现行策略是：**默认直接拦截**；用户在设置里显式打开「允许 AI 执行高风险操作」后，
  每个高风险动作仍要过一次**独立的模型二次确认**（请求失败/输出无法解析一律拒绝，fail closed），
  结果写进运行日志。这样默认状态不会无限制执行，也不需要用户每一下都盯着点确认。
- **端侧自主决策**：端侧只执行云端点名的动作；端侧弹窗规则只给建议，点不点由云端决定。
  结论性判断只做建议，最终决定权归模型。
- **引入第三方运行时依赖**：运行时只用 AndroidX + Shizuku，不加网络框架、依赖注入框架。
  `androidx.test` 只用于 `androidTest`，不进 APK。
- **把 `material-icons-extended` 换成 `material-icons-core`**：换过一轮评估（2026-11-09），
  结论是**保留 extended**。收益只在 debug：它让 `classes.dex` 到 44.7MB、debug APK 到 56MB，
  换掉能降到 ~15MB、编译也快一截。但代价要先动 23 个图标 —— 项目实际用到 39 个图标，
  其中 23 个（`Visibility`/`Timer`/`Sync`/`TouchApp` 等）core 里没有，要么自己写 `ImageVector`
  （路径数据抄错就是图标变形），要么换成语义对不上的近似图标。
  **对最终用户零影响**：release 开了 R8，没引用的图标已被裁掉，包只有 3.6MB。
  所以这纯粹是本地 debug 的体验优化，不值得为它引入视觉回归风险。
- **MCP 协议 / 插件市场 / 远程连电脑**：这三项是"接生态"，不是"点得准"，和命中率不是一个维度，
  不该和感知层的工作抢优先级：
  - MCP 的价值在"容器里的 Node 进程"能通过标准协议调手机能力。我们本来就在同一个进程里，
    skill 注册表直接调用，没有序列化开销 —— 这是优势，不是缺陷。真要改，只把 `SkillRegistry`
    的接口 schema 化 + 冻结测试，**不需要 unix socket**。
  - 插件市场是和对方 195MB 的 proot 架构绑死的。我们没有 Node 运行时，"npm 包市场"等于把
    唯一的优势（轻量）交出去。等价的正确形态是"用户自定义技能：一段提示词 + 一组固定动作"，
    本地存、不做远端分发。
  - 远程连电脑是另一条产品线（手机当瘦客户端），做了也不让手机 agent 更准。真要做就复用
    现成 Noise 实现，别自己写密码学。

## 6. 已知限制（不是 bug，别当 bug 报）

- 副屏上的窗口不会交给无障碍，所以副屏只能靠截图 + 坐标，没有元素编号列表。
- `FLAG_SECURE` 的页面（支付、银行）截不到图 —— 现在会被识别成近黑帧并如实告知模型，
  但画面依然是黑的，这不是能修的东西。
- Android 10 及以下拿不到键盘 insets，悬浮条不跟随输入法（上面拿得到，会跟着抬）。
- `input text` 不支持中文，模拟器输入法也只有英文，驱动界面时只能敲 ASCII
  （无障碍通道的 `input_text` 不受限，直接 SET_TEXT）。
- 免费通道有速率限制，额度用尽时网关返回 429；换个时间段或填自己的 Key。
- 上下文缓存是"完整匹配一个缓存前缀单元"，中间断一条就从断点起全废，所以不能随意裁剪历史。
- 同一步内同一张图可能被**重复发送**一次（`pendingImage` 不清空）：这是为保持前缀逐字节
  稳定、让服务端缓存继续命中；不重发会让缓存从该回合起全部失效，代价远大于省下的图片 token。
- 内置浏览器只放行 http/https；`file://`、`intent://`、自定义 scheme 会被拒绝。
- 公网明文 HTTP 默认拦截；本机/局域网地址不受影响。
- collect 收集到的各页编号只在**该页当帧**有效（翻走了就失效），结果文本里已写明。
