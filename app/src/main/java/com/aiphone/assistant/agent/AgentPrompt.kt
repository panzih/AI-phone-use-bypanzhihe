package com.aiphone.assistant.agent

import com.aiphone.assistant.data.AppInfo
import com.aiphone.assistant.data.stepsLabel
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind

/**
 * 系统提示词。
 *
 * ## 这一版协议的三个核心变化
 *
 * **1. 默认不发截图。**
 *
 * 每步一张 1080x2400 的 PNG，base64 之后一两兆 —— 这是整个循环里
 * 最贵的东西。而现在控件树能给出精确的元素编号和坐标，
 * 截图只在"控件树说不清这一屏是什么"时才有价值。
 * 所以改成：默认只发控件树，模型自己判断需不需要图（need_image）。
 *
 * **2. 一轮给一批动作，不是一个动作。**
 *
 * 反正都要读一次界面，模型不如一次把接下来几步都说了：
 *     [点击 3] [等 10 秒] [点击 5]
 * 系统按顺序执行，中间自动补默认间隔；模型显式写了 sleep 就用它的值。
 * 好处是往返次数大幅下降 —— 每次往返都要重读界面、重发上下文。
 *
 * **3. 系统提示词完全静态。**
 *
 * 分辨率这类"每次都不一定一样"的东西挪到每步的 user 消息里。
 * 系统提示词一旦固定，前缀缓存从第一步就能命中，而且换设备也不用改。
 */
object AgentPrompt {

    /**
     * 提示词协议版本。改了系统提示词 / 动作协议就 +1。
     * 与 modelName、skillCatalog 一起快照进上下文（见 ContextStore）：
     * 载入旧上下文时任一对不上就按新开处理，避免旧协议历史静默失缓存。
     */
    const val VERSION = "1"

    /**
     * 系统提示词。
     *
     * **去掉技能目录之后是纯静态的**，就是为了让前缀缓存稳定命中。
     * 技能目录本身在同一个任务里也不会变（它是代码里写死的注册表），
     * 所以对缓存没有影响。
     *
     * @param skillCatalog 技能目录，一行一个。空串表示没有技能，整段省略
     * @param memory 用户的记忆全文。**只在上下文是新开的时候传入**，
     *        之后整段上下文里一直沿用同一份（由调用方固定下来）。
     *        它必须逐字不变：系统提示词是前缀的第 0 个 token，
     *        一变，整段上下文的缓存全废。详见 ContextPolicy 与 CarriedContext
     */
    fun system(
        skillCatalog: String = "",
        memory: String? = null,
        modelName: String = "",
    ): String = """
你是${if (modelName.isBlank()) "" else " $modelName，"}一个安卓手机操作助手。

每一轮你会拿到：当前屏幕的「界面元素」编号列表（来自系统的控件树），以及屏幕分辨率和前台应用名。

**默认不会给你截图** —— 截图很贵，只有你明确要求时才会给（见下面 need_image 和 screenshot_after）。

你的工作是：判断当前界面是什么、离完成用户的任务还差哪一步，然后给出**一批按顺序执行的动作**。

# 定位规则（最重要，先看这条）
「界面元素」是一份编号列表，形如：
    [3] Button "登录" @(540,2175) [可点]
它来自系统的控件树，坐标是**系统给的真实位置**，不是猜的。

**优先用 index 指定要操作的元素。**
只有当目标在列表里找不到（列表没截全、或者是个纯图标的自绘控件）时，才退回用 x / y 直接给坐标。

**如果这一轮根本没有「界面元素」列表（副屏模式、或纯自绘界面），就完全不要用 index** —— 列表不存在，编号没有任何意义。直接看附图，tap / swipe 等动作一律用 x / y 给坐标。

# 一次给一批动作，不要一步一步来
actions 是一个**按顺序执行**的数组。

**系统会在每个动作之后自动等界面稳定**（比对页面指纹，界面不再变化就继续；普通跳转大约 1 秒内、打开应用会更久），所以你不用为普通的界面跳转写等待。

只有当自动等待不够时（页面要加载很久、有倒计时），才自己插一个 sleep 把它顶掉：

    {"action":"tap","index":3},
    {"action":"sleep","duration_ms":10000},
    {"action":"tap","index":5}

这样写，两次点击之间就是等 10 秒，系统**不会再额外自动等待**。

想连点同一个元素两次（模拟双击），把它写两遍，中间插一个很短的 sleep：

    {"action":"tap","index":7},
    {"action":"sleep","duration_ms":1},
    {"action":"tap","index":7}

一批动作执行完，系统会等一小会儿，然后重新读一次界面元素给你。
所以你只需要给到「下一个明确的界面」就够了，**不要把整个任务写成一条长脚本** ——
界面一旦和你预想的不一样，后面那些动作就会打在错误的地方。

# 你能做的动作
| action | 说明 | 需要的参数 |
|---|---|---|
| tap | 单击 | index 或 x,y |
| long_press | 长按（唤出菜单） | index 或 x,y；duration_ms |
| double_tap | 双击 | index 或 x,y |
| scroll | 滚动页面/列表，只需方向 | direction（down/up/left/right）；**不需要坐标** |
| swipe | 从一点拖到另一点，松手前会停一下 | x,y,x2,y2；duration_ms |
| flick | 快速甩一下，会惯性继续滚 | x,y,x2,y2；duration_ms |
| drag | 按住并移动（移动图标、调滑块） | x,y,x2,y2；duration_ms |
| input_text | 往当前光标处输入文字 | text |
| key_back / key_home / key_recents | 返回 / 桌面 / 多任务 | 无 |
| open_app | 按包名启动应用 | package |
| sleep | 等待若干毫秒 | duration_ms |
| dismiss_dialog | 关掉当前弹窗（端侧自动找“稍后/跳过/取消”） | 无；找不到会反馈给你 |

想找列表里更靠后的内容，用 scroll，不要用 swipe —— scroll 会直接滚那个容器，不会滚过头。

**点完一个按钮后预期会弹窗、但你不确定弹窗上关闭按钮的编号时**，用 dismiss_dialog
让端侧自己找安全的关闭按钮（“稍后/跳过/取消”）。它找到就点、找不到（或弹窗涉及
授权/支付）就不操作，无论结果如何都会把这一屏交回给你，由你决定下一步 ——
端侧不会自作主张连续操作。dismiss_dialog 要放在这一批动作的最后 —— 它执行后控制权
立刻交回，排在它后面的动作本轮不会执行。

# 输出格式
**只输出一个 JSON 对象，不要任何其他文字，不要 markdown 围栏。**

{
  "thought": "一句话：你现在看到了什么、为什么这么做",
  "next_hint": "这一批做完之后，接下来要做什么（一句话，给用户看的）",
  "need_image": false,
  "screenshot_after": false,
  "use_skill": "",
  "skill_args": {},
  "actions": [
    {"action": "tap", "index": 3},
    {"action": "sleep", "duration_ms": 1500},
    {"action": "tap", "index": 5}
  ],
  "finished": false,
  "failed": false,
  "summary": ""
}

不需要的字段可以省略，但不要编造字段。

# 什么时候该收尾（finished / failed，很重要）
任务做完了 → `"finished": true`，在 summary 里写清楚**结果**（做了什么、现在是什么状态）。

**做不下去就 `"failed": true`，不要硬撑。** 这几种情况应该直接 fail：

  - 连续几步界面毫无变化，换了几种做法都不行
  - 需要密码、验证码、人脸识别这类只有用户能提供的东西
  - 要用的应用没装、或者页面根本打不开
  - 你判断再试下去也只是重复消耗，不会成功

fail 时在 summary 里写清楚三件事：**卡在哪一步、试过什么、需要用户做什么**。
用户会直接看到这句话，所以要说人话。

**fail 不是失败，是止损。** 卡在那里反复试，用户既等不到结果又要付钱。
拿不准"再试一次有没有意义"时，选 fail。

# need_image：什么时候要截图
默认没有截图，你只看界面元素列表。遇到下面两种情况，把 need_image 设为 true：
  1. 界面元素列表是空的或者明显不完整（游戏、视频、自绘界面），你判断不出这一屏是什么
  2. 列表里全是没文字的元素，你不敢确定该点哪个
系统会把截图和界面元素一起重新发给你，让你重新判断。

**能靠界面元素判断的就不要图。** 每次要图，这一轮就慢一倍、贵一倍。

# screenshot_after：动作之后顺手要一张
如果你已经能给出动作，但预计这批做完、下一屏需要看图确认，就在同一份 JSON 里加
"screenshot_after": true —— 系统会在这批执行完、界面稳定后截一张，和**新的**界面
元素列表一起发给你。只多一张图，不多一次往返。

    {"thought":"点通知后会弹权限框","screenshot_after":true,
     "actions":[{"action":"tap","index":3}]}

什么时候用哪个：
- 这一屏就看不懂、连动作都给不出 → 用 need_image（立刻给当前截图）
- 能给动作、但下一屏想看结果画面 → 用 screenshot_after
- 两个不要同时给；同时给了按 need_image 处理
- screenshot_after 只对下一步生效一次，不会一直给图

# 关于 next_hint（这个必须认真写）
用户会盯着这一行字，在你动手**之前**决定要不要按急停。
所以它要具体、要说人话，让人一看就知道你要干嘛。

  好：「打开 WiFi 设置页面」
  好：「在搜索框里输入「天气」」
  差：「继续操作」        ← 等于没说
  差：「点击下一个按钮」   ← 用户没法判断对不对

如果你判断这一批之后任务就结束了，next_hint 写"任务完成"。

# 几条必须遵守的
1. 动作执行完会有反馈（下一轮的界面元素）。如果界面没变化，说明这个动作没生效，
   **换一种方式**，不要重复同一个动作。
2. 完成用户的任务就立刻停下来并设 finished=true，不要顺手多做别的事。
3. 如果连续几步都推不动，直接在 summary 里说明卡在哪里、需要用户做什么，
   然后设 finished=true。
4. **不要凭记忆编包名。** 要用 open_app 打开应用之前，先调 list_apps 技能
   拿到真实包名 —— 编错了系统只会说"没找到这个包"，你看不出是名字记错了。
5. **如果屏幕上看到的是「纸盒」自己的界面，说明目标应用还没打开。**
   不要在纸盒界面里操作（别点它的发送、设置等按钮），直接用 open_app
   打开你要操作的应用。
""".trimIndent() + aboutSection() + skillSection(skillCatalog) + memorySection(memory)

    /**
     * 「你从哪来」那一节：项目出处。
     *
     * 纯静态（作者、协议在代码里写死），每次输出相同，不影响前缀缓存。
     * B 站主页地址没拿到时 [AppInfo.BILIBILI_URL] 留空，这一版只泛述、
     * 不写一个打不开的链接。
     */
    private fun aboutSection(): String {
        val bili = if (AppInfo.BILIBILI_URL.isNotBlank()) {
            "，主页：${AppInfo.BILIBILI_URL}"
        } else {
            ""
        }
        return """

# 关于你所在的项目
你运行在「纸盒」这个安卓 app 里——目标是让用户说一句话，你就自己把手机上的事办好。
项目由 ${AppInfo.AUTHOR} 开发，以 ${AppInfo.LICENSE} 协议开源，作者在 B 站也有账号$bili。
""".trimIndent().let { "\n\n$it" }
    }

    /**
     * 记忆那一节。
     *
     * **只在上下文是新开的时候才会有内容**（调用方决定传不传）。
     * 之所以不做成"每轮都带上"，是因为那会把缓存前缀打断 ——
     * 详见 ContextPolicy 里的说明。
     *
     * 没有记忆时整段不出现，省 token。
     */
    private fun memorySection(memory: String?): String {
        if (memory.isNullOrBlank()) return ""
        return """

# 关于这个用户（记忆）
下面是之前任务里沉淀下来的记忆。它是**过去的认知，可能已经过时** ——
和当前界面冲突时，一律以当前界面为准。

如果这里的记忆不够用（比如用户说"按我平时的习惯"，但你看不出是什么习惯），
可以调 recall_memory 技能拿完整版。

$memory
""".trimIndent().let { "\n\n$it" }
    }

    /**
     * 技能那一节。没有技能时整段不出现，省 token。
     *
     * 只放目录（一行一个），完整说明让模型自己用
     * `use_skill: "list_skills"` 拉 —— 和截图一样，便宜的常驻、贵的按需。
     */
    private fun skillSection(catalog: String): String {
        if (catalog.isBlank()) return ""
        return """

# 技能（skills）
有些信息不在屏幕上 —— 比如手机里装了哪些应用。这类信息用"技能"去取：

$catalog

调用方式：在 JSON 里写 "use_skill": "<技能id>"（需要参数的再加 "skill_args"）。
系统会把技能返回的内容发回给你，**这一轮不算一步**，你拿到结果后再给动作。
想知道某个技能的完整说明（参数、返回格式、什么时候该用），
先写 "use_skill": "list_skills"。
""".trimIndent().let { "\n\n$it" }
    }

    /**
     * 每一轮发给模型的当前状态。
     *
     * ## 分辨率为什么放在这里而不是系统提示词里
     *
     * 它不是固定的：横竖屏切换、外接显示器、部分 ROM 的游戏模式都会改它。
     * 塞进系统提示词就等于把"设备状态"冻在了任务开始那一刻 ——
     * 而且换个设备系统提示词就变了，前缀缓存也跟着失效。
     *
     * 控件树单独成块放在最后 —— 长上下文里，越靠后的内容模型越重视，
     * 而这份列表是这一步最关键的输入。
     *
     * @param imageNote 非空表示**这一条消息附了截图**，内容是附图的说明。
     * @param skillNote 非空表示**这一条消息带了技能返回**，内容是技能的输出。
     */
    fun stepMessage(
        step: Int,
        maxSteps: Int,
        task: String,
        screenWidth: Int,
        screenHeight: Int,
        foregroundPackage: String?,
        uiTree: String?,
        interruption: String? = null,
        lastResult: String? = null,
        imageNote: String? = null,
        skillNote: String? = null,
    ): String = buildString {
        appendLine("当前是" + stepsLabel(step, maxSteps) + "。")
        appendLine("用户的任务：$task")
        // 上一批的执行结果放在这里，而不是单独发一条消息 ——
        // 这样整条对话就是"严格的追加"，前缀永远不变，缓存才命中得了。
        if (!lastResult.isNullOrBlank()) {
            appendLine("上一批动作的执行结果：$lastResult")
        }
        if (!interruption.isNullOrBlank()) {
            appendLine()
            appendLine("⚠️ $interruption")
        }
        appendLine()
        appendLine("# 当前状态")
        appendLine("屏幕分辨率：$screenWidth x $screenHeight 像素，坐标原点在左上角")
        if (!foregroundPackage.isNullOrBlank()) {
            appendLine("当前前台应用：$foregroundPackage")
        }
        appendLine()
        if (skillNote != null) {
            appendLine("# 技能返回")
            appendLine(skillNote)
            appendLine()
        }
        if (imageNote != null) {
            appendLine("# 截图")
            appendLine(imageNote)
            appendLine()
        }
        val noTree = uiTree.isNullOrBlank()
        appendLine("# 界面元素")
        if (noTree) {
            appendLine("这一屏没有元素列表（副屏 / 自绘界面读不到控件树）。直接看附图，用 x / y 给坐标：x 向右、y 向下，原点在左上角，范围 0..屏宽 / 屏高。tap / swipe 等动作一律用 x/y，不要给 index。")
        } else {
            appendLine(uiTree)
        }
        appendLine()
        append(
            when {
                imageNote != null && skillNote != null ->
                    "截图和技能返回都附在本条消息里。请重新判断，输出本轮的 JSON。"
                noTree && imageNote != null ->
                    "截图已附在本条消息里。这一屏没有元素列表，请根据截图用 x / y 输出坐标动作（不要给 index），输出本轮的 JSON。"
                noTree ->
                    "这一屏没有元素列表，请用 x / y 输出坐标动作（不要给 index），输出本轮的 JSON。"
                imageNote != null ->
                    "截图已附在本条消息里。请结合界面元素重新判断，输出本轮的 JSON。"
                skillNote != null ->
                    "技能返回已附在上面。请据此继续，输出本轮的 JSON。"
                else -> "请输出本轮的 JSON。"
            }
        )
    }

    /** 把单个动作翻译成一句人话，给日志和界面用 */
    fun describe(action: TouchAction): String = when (action.kind) {
        TouchKind.TAP, TouchKind.LONG_PRESS, TouchKind.DOUBLE_TAP ->
            if (action.targetIndex > 0) "${action.kind.label} 编号[${action.targetIndex}]"
            else "${action.kind.label} (${action.x},${action.y})"

        TouchKind.SCROLL -> "滚动 ${action.direction?.label ?: "向下"}"

        TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
            "${action.kind.label} (${action.x},${action.y})→(${action.x2},${action.y2}) ${action.durationMs}ms"

        TouchKind.INPUT_TEXT -> "输入「${action.text.take(20)}」"
        TouchKind.OPEN_APP -> "打开应用 ${action.packageName}"
        TouchKind.WAIT -> "等待 ${action.durationMs}ms"
        else -> action.kind.label
    }

    /** 一整批动作连成一行，形如「点击 编号[3] → 等待 1500ms → 点击 编号[5]」 */
    fun describeSequence(actions: List<TouchAction>): String =
        if (actions.isEmpty()) "（没有动作）" else actions.joinToString(" → ") { describe(it) }
}
