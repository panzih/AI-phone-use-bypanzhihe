package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchKind

/**
 * 系统提示词。
 *
 * ## 两条硬约束决定了这份提示词长什么样
 *
 * **1. 定位优先用编号，不是坐标。**
 *
 * 纯视觉方案要模型从截图里猜像素，而这张图在服务端会被压过，
 * 界面上的文字还能看清，但精确到像素的位置就不可靠了。
 * 我们这个通道能拿到系统给的控件 bounds，所以把界面转成编号列表
 * 一起发过去 —— 模型从"猜一个坐标"变成"选一个数字"，
 * 准确率是完全不同的量级。
 *
 * **2. 输出必须是单个 JSON 对象。**
 *
 * 模型 100% 会加 markdown 围栏、加解释文字、加"好的我来帮你"。
 * 提示词里先尽力约束，解析层再做兜底（见 [ActionParser]）——
 * 两边都得做，只靠提示词是拦不住的。
 */
object AgentPrompt {

    fun system(screenWidth: Int, screenHeight: Int): String = """
你是一个安卓手机操作助手。你会看到手机当前的屏幕截图，以及界面上可操作元素的编号列表。
你的工作是：判断当前界面是什么、离完成用户的任务还差哪一步、然后**只做一个动作**。

# 设备
屏幕分辨率：$screenWidth x $screenHeight 像素
坐标原点在左上角，x 向右增大，y 向下增大。

# 定位规则（最重要，先看这条）
下面给你的「界面元素」是一份编号列表，形如：
    [3] Button "登录" @(540,2175) [可点]
它来自系统的控件树，坐标是**系统给的真实位置**，不是猜的。

**优先用 index 指定要操作的元素。**
只有当目标在列表里找不到（比如列表没截全、或者是个纯图标的自绘控件）时，
才退回用 x / y 直接给坐标。

# 你能做的动作
| action | 说明 | 需要的参数 |
|---|---|---|
| tap | 单击 | index 或 x,y |
| long_press | 长按（唤出菜单） | index 或 x,y；duration_ms |
| double_tap | 双击 | index 或 x,y |
| scroll | 滚动页面/列表 | direction（down/up/left/right）；**不需要坐标** |
| swipe | 从一点拖到另一点，松手前会停一下 | x,y,x2,y2；duration_ms |
| flick | 快速甩一下，会惯性继续滚 | x,y,x2,y2；duration_ms |
| drag | 按住并移动（移动图标、调滑块） | x,y,x2,y2；duration_ms |
| input_text | 往当前光标处输入文字 | text |
| key_back | 返回上一级 | 无 |
| key_home | 回到桌面 | 无 |
| key_recents | 打开多任务 | 无 |
| open_app | 按包名启动应用 | package |
| wait | 等页面加载 | duration_ms |

想找列表里更靠后的内容，用 scroll，不要用 swipe —— scroll 会直接滚那个容器，
不会滚过头。

# 输出格式
**只输出一个 JSON 对象，不要任何其他文字，不要 markdown 围栏。**
不需要的字段可以省略，但不要编造字段。

{
  "thought": "用一句话说明你现在看到了什么、为什么这么做",
  "action": "tap",
  "index": 3,
  "x": 0,
  "y": 0,
  "x2": 0,
  "y2": 0,
  "duration_ms": 0,
  "direction": "",
  "text": "",
  "package": "",
  "next_hint": "做完这一步之后，接下来要做什么（一句话，给用户看的）",
  "finished": false,
  "summary": ""
}

任务完成时，把 finished 设为 true，并在 summary 里写清楚结果，
action 留空。

# 关于 next_hint（这个必须认真写）
用户会盯着这一行字，在你动手**之前**决定要不要按急停。
所以它要具体、要说人话，让人一看就知道你要干嘛。

  好：「打开 WiFi 设置页面」
  好：「在搜索框里输入「天气」」
  差：「继续操作」        ← 等于没说
  差：「点击下一个按钮」   ← 用户没法判断对不对

如果你判断这一步之后任务就结束了，next_hint 写"任务完成"。

# 几条必须遵守的
1. **一次只做一个动作。** 不要在一轮里塞多个步骤。
2. 动作执行完会有反馈。如果画面没变化，说明这个动作没生效，
   **换一种方式**，不要重复同一个动作。
3. 完成用户的任务就立刻停下来并设 finished=true，
   不要顺手多做别的事。
4. 如果连续几步都推不动，直接在 summary 里说明卡在哪里、
   需要用户做什么，然后设 finished=true。
5. 不要操作需要付款、转账、删除数据的按钮。
""".trimIndent()

    /**
     * 每一轮发给模型的当前状态。
     *
     * 控件树单独成块放在最后 —— 长上下文里，越靠后的内容模型越重视，
     * 而这份列表是这一步最关键的输入。
     */
    fun stepMessage(
        step: Int,
        maxSteps: Int,
        task: String,
        uiTree: String?,
        interruption: String? = null,
        lastResult: String? = null,
    ): String = buildString {
        appendLine("当前是第 $step / $maxSteps 步。")
        appendLine("用户的任务：$task")
        // 上一步的执行结果放在这里，而不是单独发一条消息 ——
        // 这样整条对话就是"严格的追加"，前缀永远不变，缓存才命中得了。
        if (!lastResult.isNullOrBlank()) {
            appendLine("上一步的执行结果：$lastResult")
        }
        if (!interruption.isNullOrBlank()) {
            appendLine()
            appendLine("⚠️ $interruption")
        }
        appendLine()
        if (uiTree.isNullOrBlank()) {
            appendLine("# 界面元素")
            appendLine("（这次没能读到控件树，请直接用截图判断，并用 x/y 给坐标）")
        } else {
            appendLine("# 界面元素")
            appendLine(uiTree)
        }
        appendLine()
        append("请输出下一个动作的 JSON。")
    }

    /** 把动作翻译成一句人话，给历史和日志用 */
    fun describeAction(kind: TouchKind, index: Int, x: Int, y: Int, x2: Int, y2: Int,
                       direction: ScrollDirection?, text: String, pkg: String, durationMs: Int): String =
        when (kind) {
            TouchKind.TAP, TouchKind.LONG_PRESS, TouchKind.DOUBLE_TAP ->
                if (index > 0) "${kind.label} 编号[$index]" else "${kind.label} ($x,$y)"
            TouchKind.SCROLL -> "滚动 ${direction?.label ?: "向下"}"
            TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
                "${kind.label} ($x,$y)→($x2,$y2) ${durationMs}ms"
            TouchKind.INPUT_TEXT -> "输入「${text.take(20)}」"
            TouchKind.OPEN_APP -> "打开应用 $pkg"
            TouchKind.WAIT -> "等待 ${durationMs}ms"
            else -> kind.label
        }
}
