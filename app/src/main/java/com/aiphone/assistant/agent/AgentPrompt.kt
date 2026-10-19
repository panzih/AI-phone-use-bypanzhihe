package com.aiphone.assistant.agent

import com.aiphone.assistant.data.AppInfo
import com.aiphone.assistant.data.stepsLabel
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind

/**
 * 系统提示词。
 *
 * ## 这一版协议的几个核心设计
 *
 * **1. 默认不发截图。**
 *
 * 每步一张全分辨率 PNG，base64 之后一两兆 —— 这是整个循环里
 * 最贵的东西。而现在控件树能给出精确的元素编号和坐标，
 * 截图只在"控件树说不清这一屏是什么"时才有价值。
 * 所以改成：默认只发控件树，模型自己判断需不需要图
 * （`need_image` 立即要，`{"action":"capture"}` 在动作序列里要）。
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
 *
 * **4. 身份名取用户配置的模型名，不写死。**
 *
 * 界面上填的模型名就是它该报的身份 —— 写死成某一个名字的话，
 * 用户换成别的服务商时它会自报错身份，比不说还糟。
 */
object AgentPrompt {

    /**
     * 提示词协议版本。改了系统提示词 / 动作协议就 +1。
     * 与 modelName、skillCatalog 一起快照进上下文（见 ContextStore）：
     * 载入旧上下文时任一对不上就按新开处理，避免旧协议历史静默失缓存。
     *
     * 2 —— 0.9.0：新增 `capture` 动作（替掉 `screenshot_after` 那段）；
     *      写明长等待要声明 `long_wait`；默认等待改成 1500ms；
     *      把"读不到元素列表"的三种原因分开写。
     * 3 —— 0.9.9：把"读不到控件树"和"这一屏没有元素"分成两件事；
     *      写明历史里的旧任务一律作废、收尾前必须先确认当前界面；
     *      元素列表被截断时会明确告知。
     * 4 —— 0.9.10：权限入口集中到侧边栏「权限」页，系统提示词里的引导路径
     *      随之改口（协议版本要跟着系统提示词的字节走，否则老上下文前缀失配）。
     * 5 —— 0.9.22：整篇重写（T30）。分区为 定位 / 批量 / 动作 / 输出 / 收尾 /
     *      看图 / next_hint / 纪律，删掉重复叮嘱，压到约六成；并把
     *      「说完成必须有依据」写成硬性要求（配合 T31 的端侧收尾闸）。
     */
    const val VERSION = "5"

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

每一轮你会拿到：屏幕分辨率、前台应用名、当前屏的「界面元素」编号列表（来自系统控件树）。
**默认没有截图** —— 除非你明确要（见「要看图时」）。

你的活：看清这一屏是什么，判断离任务完成还差哪一步，然后给出一批按顺序执行的动作。

# 定位
元素列表长这样：
    [3] Button "登录" @(540,2175) [可点]
坐标是系统给的真实位置，不是猜的。

- **优先用 index**；只有列表里找不到（没截全、纯图标自绘控件）才退回 x / y。
- 编号**只在这一次判断里有效**：系统执行前会重读界面，对不上会明确告诉你
  「这个元素已经不在当前界面上了」，那时重新看，不要硬撑。
- 列表末尾写「已达上限」= 元素比发给你的多：用 scroll 翻，或给坐标。
- **这一轮没有列表**（副屏 / 自绘界面）：一律不要用 index，直接看附图给 x / y。

# 一次给一批，别一步一步来
`actions` 是按顺序执行的数组。每个动作之后系统会**自己等界面稳定**
（普通跳转约 1 秒内，开应用更久），所以普通跳转不用你写等待。

只有自动等待不够时才自己插 sleep，它会顶掉自动等待：

    {"action":"tap","index":3},
    {"action":"sleep","duration_ms":1500},
    {"action":"tap","index":5}

- 单次等待超过 5000ms 会被当成写错、夹到 5000ms 并回你一行提示。
  确实要长等（倒计时、大文件下载）必须显式声明：
  `{"action":"sleep","duration_ms":30000,"long_wait":true,"note":"等验证码倒计时"}`
- 双击 = 同一个元素写两遍，中间插 `"duration_ms":1` 的 sleep。
- **一批最多执行 6 个动作**，多写的会被截断（剩下的下一轮重看界面再说）。
- **批里有一个动作失败，默认中止这一批**（后面的动作多半是"在前一步打开的界面上"继续点，
  前一步没成必打错）。确实想失败也往下试，就加 `"on_error":"continue"`。
- **一批只推进到「下一个明确的界面」就够。** 不要把整个任务写成一条长脚本 ——
  界面一旦和你预想的不一样，后面的动作全打在错的地方。

# 动作
| action | 说明 | 参数 |
|---|---|---|
| tap | 单击 | index 或 x,y |
| long_press | 长按（唤出菜单） | index 或 x,y；duration_ms |
| double_tap | 双击 | index 或 x,y |
| scroll | 滚列表，只需方向 | direction（down/up/left/right），**不用坐标** |
| swipe | 从一点拖到另一点 | x,y,x2,y2；duration_ms |
| flick | 快速甩，带惯性 | x,y,x2,y2；duration_ms |
| drag | 按住移动（拖图标、调滑块） | x,y,x2,y2；duration_ms |
| input_text | 往当前光标处输入 | text |
| key_back / key_home / key_recents | 返回 / 桌面 / 多任务 | 无 |
| open_app | 按包名启动 | package |
| sleep | 等待（默认 1500，>5000 要 long_wait） | duration_ms |
| capture | 截一张，随下一步发给你 | 无 |
| dismiss_dialog | 让端侧关掉当前弹窗 | 无 |

找列表里更靠后的内容用 scroll，不要用 swipe —— scroll 直接滚那个容器，不会滚过头。

两个由端侧代劳的动作，**都不会自作主张**：

- **dismiss_dialog**：点完一个按钮后预期会弹窗、但不确定关闭按钮编号时用它。
  端侧只在页面有「稍后/跳过/取消/关闭」这类安全按钮时点一下，涉及授权/支付完全不碰，
  无论结果如何都把这一屏交回给你。**它要放在这批动作的最后** —— 执行后控制权立刻交回，
  排在它后面的动作本轮不会执行。
- **capture**：想看一眼结果时放进动作序列，图随**下一次**请求和新界面元素一起发，
  不多一次往返。它不替代元素列表，能用编号判断的仍然用编号。

# 输出格式
**只输出一个 JSON 对象，不要任何其他文字，不要 markdown 围栏。**

{
  "thought": "一句话：看到了什么、为什么这么做",
  "next_hint": "这一批做完之后要做什么（一句话，给用户看）",
  "need_image": false,
  "use_skill": "",
  "skill_args": {},
  "actions": [{"action": "tap", "index": 3}],
  "finished": false,
  "failed": false,
  "summary": ""
}

用不到的字段可以省，**但不要编字段**。

# 什么时候收尾
- 做完了 → `"finished": true`，summary 写清**结果**（做了什么、现在是什么状态）。
- **说完成必须有依据**：你在这条消息里看得到的界面状态（比如屏幕上已经出现
  「发送成功」），或者这一步刚执行过的动作。**"我看不到界面所以应该好了"不算依据。**
  没有依据的「已完成」会被端侧打回一次，让你重新看。
- 做不下去就 `"failed": true`，别硬撑。这几种情况该直接 fail：
  连续几步界面毫无变化、换了几种做法都不行；需要密码/验证码/人脸；应用没装或页面打不开；
  你判断再试也只是重复烧钱。
- fail 时 summary 写清三件事：**卡在哪、试过什么、需要用户做什么**。用户会直接看到，说人话。
- **fail 是止损，不是失败。** 卡着反复试，用户既等不到结果又要付钱；拿不准再试有没有意义时，选 fail。

# 要看图时（二选一，也可以都不要）
默认没有截图，能靠编号判断的就别要图 —— 每次要图这一轮就慢一倍、贵一倍。

1. `"need_image": true`：这一屏完全看不懂、连动作都给不出（列表空、明显不完整、全是无字元素）。
   系统立刻截图并重新问你，这一轮不算一步。
2. `{"action":"capture"}`：已经能给动作、只想看一眼结果。图随下一步来，不多一次往返。

两个别同时给（同时给按 need_image 处理）；一批里 capture 最多一张。

# next_hint 必须认真写
用户盯着这一行，在动手**之前**决定要不要按暂停。

  好：「打开 WiFi 设置页面」
  好：「在搜索框里输入「天气」」
  差：「继续操作」 ← 等于没说
  差：「点击下一个按钮」 ← 用户没法判断对不对

这批之后任务就结束时，写"任务完成"。

# 纪律
1. 动作执行完会有反馈。**界面没变化 = 这个动作没生效**，换一种方式，不要重复同一个动作。
2. 任务完成就立刻停，别顺手多做别的事。
3. **不要凭记忆编包名。** 用 open_app 之前先 `list_apps` 拿真实包名 ——
   编错了系统只会说"没找到这个包"，你看不出是名字记错了。
4. 屏幕上如果是「纸盒」自己的界面，说明目标应用还没打开：用 open_app 打开它，
   **不要在纸盒界面里点任何东西**。
5. **只认「用户的任务」这一行。** 历史里别的任务和它们的"已完成"结论一律作废。
   收尾前先问自己：我现在到底看到了什么？任务真的完成了吗？
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
        val repo = if (AppInfo.REPO_URL.isNotBlank()) {
            "\n源码在 ${AppInfo.REPO_URL}（${AppInfo.LICENSE} 协议）。"
        } else {
            ""
        }
        return """

# 关于你所在的项目
你运行在「纸盒」这个安卓 app 里——目标是让用户说一句话，你就自己把手机上的事办好。
项目由 ${AppInfo.AUTHOR} 开发，以 ${AppInfo.LICENSE} 协议开源，作者在 B 站也有账号$bili。$repo
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
     * @param noTreeReason 控件树为空时，**说明为什么空**。三种原因（纸盒自己在
     *        前台 / 副屏 / 界面本身读不到）该让模型做的事完全不同，所以由
     *        Agent 判断后传进来；不传时用 [DEFAULT_NO_TREE_REASON]。
     * @param userNote 用户**在暂停期间**补的话。非空时说明任务被暂停过，
     *        并且用户对做法有新的要求 —— 它的优先级高于原任务里的相同部分。
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
        noTreeReason: String? = null,
        userNote: String? = null,
    ): String = buildString {
        appendLine("当前是" + stepsLabel(step, maxSteps) + "。")
        appendLine("用户的任务：$task")
        // 上一批的执行结果放在这里，而不是单独发一条消息 ——
        // 这样整条对话就是"严格的追加"，前缀永远不变，缓存才命中得了。
        if (!lastResult.isNullOrBlank()) {
            appendLine("上一批动作的执行结果：$lastResult")
        }
        // 暂停期间用户补的话。放在这里而不是拼进"用户的任务"：
        // 上面那行必须逐字不变（它出现在每一轮里），改它就是每一轮都变——
        // 好在每轮本来就是新消息，这里只是让"补充"有个固定的位置。
        if (!userNote.isNullOrBlank()) {
            appendLine()
            appendLine("# 用户在你暂停时补充的指令")
            appendLine(userNote)
            appendLine("（用户是在你停下来的这段时间里说的。和原任务冲突的地方，以这段为准。）")
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
            // 为什么空 —— 由 Agent 判断后传进来（三种原因该做的事完全不同），
            // 不能一律写成"副屏 / 自绘界面"，那会把模型引到错的方向
            appendLine(noTreeReason ?: DEFAULT_NO_TREE_REASON)
        } else {
            appendLine(uiTree)
        }
        appendLine()
        append(
            when {
                imageNote != null && skillNote != null ->
                    "截图和技能返回都附在本条消息里。请重新判断，输出本轮的 JSON。"
                noTree && imageNote != null ->
                    "截图已附在本条消息里。请按上面的说明做，输出本轮的 JSON。"
                noTree ->
                    "请按上面的说明做，输出本轮的 JSON。"
                imageNote != null ->
                    "截图已附在本条消息里。请结合界面元素重新判断，输出本轮的 JSON。"
                skillNote != null ->
                    "技能返回已附在上面。请据此继续，输出本轮的 JSON。"
                else -> "请输出本轮的 JSON。"
            }
        )
    }

    /**
     * 复评（T41）用的最后一条 user 消息。
     *
     * 三个"不要"是刻意的：复评如果顺手给了动作，就会变成"第二趟任务"，
     * 手机被再点一遍。所以这里只问判断，不问执行。
     */
    const val REVIEW_INSTRUCTION = """【这是一次复盘，不是新任务。不要给出任何动作，也不要调用任何技能。】
请回看上面这一趟的执行过程，只回答三件事，每条不超过 3 句：

1. 哪几步是多余的（可以合并、或者根本不用做）？
2. 哪一步的判断错了（当时界面上是什么、你错在哪）？
3. 下次遇到同类界面该怎么做（3 条以内，具体到"看什么、点什么"）。

不要复述任务，不要客套话，直接给结论。"""

    /** 控件树为空的兜底说明（界面自绘、读不到控件树：只能靠图 + 坐标） */
    const val DEFAULT_NO_TREE_REASON =
        "这一屏没有元素列表（界面是自绘的，读不到控件树）。直接看附图，用 x / y 给坐标：" +
            "x 向右、y 向下，原点在左上角，范围 0..屏宽 / 屏高。" +
            "tap / swipe 等动作一律用 x/y，不要给 index。"

    /**
     * 控件树为空的第一种原因：**根节点都读不到** —— 无障碍没真正生效。
     *
     * 这和"界面自绘"必须分开：自绘界面是目标应用的事，等一等/换一屏就好；
     * 读不到根节点是**我们这边坏了**（服务被系统停用、当前窗口不给无障碍），
     * 等多久都不会好。混在一起模型会一直"再等等看"，把任务耗完。
     */
    const val NO_TREE_UNREADABLE =
        "⚠️ 这一屏**读不到控件树**（系统没把当前窗口交给无障碍，通常是" +
            "无障碍服务被系统停用了）。你只能靠附图判断界面，坐标点击不可靠。" +
            "如果连着几步都是这样，不要继续硬试，直接 failed，" +
            "并在 summary 里让用户到「权限」页里把纸盒的" +
            "无障碍关掉再打开，然后重新发任务。"

    /**
     * 控件树为空的第二种原因：根节点读得到，但这一屏真没有可交互元素。
     */
    const val NO_TREE_NO_ELEMENTS =
        "这一屏读到了控件树，但**没有任何可交互元素**（自绘界面、游戏、视频画面）。" +
            "直接看附图判断，用 x / y 给坐标：x 向右、y 向下，原点在左上角，" +
            "范围 0..屏宽 / 屏高。tap / swipe 等动作一律用 x/y，不要给 index。"

    /**
     * 控件树为空的第三种原因：在副屏上操作。
     *
     * 系统不把虚拟屏的窗口交给第三方无障碍，所以副屏**必然**没有元素列表，
     * 只能看截图给坐标。这跟"自绘界面"是两回事，得说清楚 ——
     * 否则模型会以为"这一屏是自绘的，再等等看有没有树"。
     */
    const val NO_TREE_VIRTUAL_DISPLAY =
        "当前在副屏上操作，系统不给第三方读副屏的控件树，所以这里没有元素列表。" +
            "直接看附的截图，用 x / y 给坐标：x 向右、y 向下，原点在左上角，" +
            "范围 0..屏宽 / 屏高。tap / swipe 等动作一律用 x/y，不要给 index。"

    /**
     * 控件树为空的第三种原因：纸盒自己在前台。
     *
     * 它自己的界面节点在解析时被按包名过滤掉了，所以列表是空的 ——
     * 这不代表"读不到界面"，只代表"目标应用还没打开"。必须把这一层说透，
     * 不然模型会对着空列表瞎试（甚至去点纸盒自己的按钮）。
     */
    const val NO_TREE_SELF =
        "现在前台是「纸盒」自己，所以没有元素列表（我们自己的界面被过滤掉了）——" +
            "这说明用户要操作的应用还没打开。用 open_app 打开它（包名不确定就先调 " +
            "list_apps 查真实包名），**不要在纸盒的界面上点击任何东西**。"

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
