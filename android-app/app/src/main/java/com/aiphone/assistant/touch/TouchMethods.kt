package com.aiphone.assistant.touch

/**
 * 触控方式清单。
 *
 * 这个文件同时承担两个职责：
 *   1. 定义"软件支持哪些触控" —— 界面、提示词、执行器都从这里取，避免三处对不上
 *   2. 标注每种方式的技术可行性，让界面能如实告诉用户哪些暂时用不了
 *
 * ## 单指动作已经做完整
 *
 *   单击 / 长按 / 双击 / 滑动 / 甩动 / 拖拽 / 滚动 —— 全部实现。
 *
 *   它们不是七个独立实现，而是同一个三段式手势模型的参数变体：
 *
 *       按下 ──┬── 移动 ──┬── 停顿 ── 松手
 *              │          │
 *              │      滑动有停顿，甩动没有
 *              └── 拖拽在这里加长按
 *
 *   详见 GestureSpec.kt。
 *
 * ## 双指暂时搁置
 *
 *   双指捏合/张开在代码里能跑（dispatchGesture 支持多路径），
 *   但按计划先只把单指做扎实，双指和旋转、双指点击暂缓。
 */
enum class TouchKind(
    /** 内部标识，写进动作 JSON 用 */
    val id: String,
    /** 界面上显示的名字 */
    val label: String,
    /** 一句话说明 */
    val description: String,
    /** 需不需要坐标参数 */
    val needsPoint: Boolean,
    /** 需不需要时长/速度参数 */
    val needsDuration: Boolean,
    /** 是不是 Android 端新增的（相对桌面版） */
    val requiresExtraComponent: Boolean = false,
    /** 为什么需要额外组件 */
    val extraNote: String? = null,
) {
    TAP(
        id = "tap",
        label = "点击",
        description = "在指定位置单击一次",
        needsPoint = true,
        needsDuration = false,
    ),

    LONG_PRESS(
        id = "long_press",
        label = "长按",
        description = "按住指定位置不放，常用于唤出菜单",
        needsPoint = true,
        needsDuration = true,
    ),

    DOUBLE_TAP(
        id = "double_tap",
        label = "双击",
        description = "快速点两下，用于放大或选中",
        needsPoint = true,
        needsDuration = false,
    ),

    /**
     * 滚动页面/列表。
     *
     * 和"滑动"的区别很关键：
     *   滚动 → 目标是某个可滚动容器，**不需要坐标**，无障碍可以直接滚节点
     *   滑动 → 从一点拖到另一点，必须给起终点坐标
     *
     * 模型大多数时候想做的是"滚动"，所以单独列出来。
     */
    SCROLL(
        id = "scroll",
        label = "滚动",
        description = "滚动页面或列表，只需说方向，不用给坐标",
        needsPoint = false,
        needsDuration = true,
    ),

    SWIPE(
        id = "swipe",
        label = "滑动",
        description = "从一点滑到另一点，松手前停一下，不会继续滚",
        needsPoint = true,
        needsDuration = true,
    ),

    /**
     * 甩动（惯性滑动）。
     *
     * 和滑动的唯一区别：**松手前不停顿**。
     * 停顿会让速度归零，系统就不触发惯性；不停顿则保持速度，继续滚一段。
     *
     * 参数上就是 GestureSpec 里 restBeforeUpMs 一个有值一个为 0。
     */
    FLICK(
        id = "flick",
        label = "甩动",
        description = "快速甩一下，松手后还会惯性滚动一段（刷列表常用）",
        needsPoint = true,
        needsDuration = true,
    ),

    DRAG(
        id = "drag",
        label = "拖拽",
        description = "按住并移动，比滑动更慢，用于移动图标、排序",
        needsPoint = true,
        needsDuration = true,
    ),

    PINCH_OUT(
        id = "pinch_out",
        label = "双指放大",
        description = "两根手指向外分开，放大画面",
        needsPoint = true,
        needsDuration = true,
    ),

    PINCH_IN(
        id = "pinch_in",
        label = "双指缩小",
        description = "两根手指向内合拢，缩小画面",
        needsPoint = true,
        needsDuration = true,
    ),

    MULTI_FINGER(
        id = "multi_finger",
        label = "多指手势",
        description = "三指及以上，用于特定游戏或应用操作",
        needsPoint = true,
        needsDuration = true,
        requiresExtraComponent = true,
        extraNote = "无障碍的多指能力已就绪，但还没做"自定义手指路径"的接口。",
    ),

    // ---- 下面这些是坐标类之外的基础动作，桌面版已有，端上版不该丢 ----

    INPUT_TEXT(
        id = "input_text",
        label = "输入文字",
        description = "往当前聚焦的输入框里打字",
        needsPoint = false,
        needsDuration = false,
    ),

    KEY_BACK(
        id = "key_back",
        label = "返回键",
        description = "相当于按一下系统返回",
        needsPoint = false,
        needsDuration = false,
    ),

    KEY_HOME(
        id = "key_home",
        label = "主页键",
        description = "回到桌面",
        needsPoint = false,
        needsDuration = false,
    ),

    KEY_RECENTS(
        id = "key_recents",
        label = "多任务键",
        description = "打开后台任务列表",
        needsPoint = false,
        needsDuration = false,
    ),

    OPEN_APP(
        id = "open_app",
        label = "打开应用",
        description = "按包名启动一个应用",
        needsPoint = false,
        needsDuration = false,
    ),

    WAIT(
        id = "wait",
        label = "等待",
        description = "等页面加载完再继续",
        needsPoint = false,
        needsDuration = true,
    ),
    ;

    companion object {
        fun fromId(id: String): TouchKind? = entries.firstOrNull { it.id == id }

        /** 当前纯 input 方案下真正可用的那些 */
        val availableNow: List<TouchKind> get() = entries.filter { !it.requiresExtraComponent }

        /** 需要额外组件才能做的那些 */
        val needsExtra: List<TouchKind> get() = entries.filter { it.requiresExtraComponent }
    }
}

/**
 * 滚动方向。
 *
 * 注意语义：这里说的是**内容往哪个方向走**，不是手指往哪划。
 * 说"向下滚动"= 看后面的内容 = 手指向上划。
 * 代码里换算，模型不用操心这个反直觉的地方。
 */
enum class ScrollDirection(val id: String, val label: String) {
    DOWN("down", "向下（看后面的内容）"),
    UP("up", "向上（看前面的内容）"),
    LEFT("left", "向左"),
    RIGHT("right", "向右"),
    ;

    companion object {
        fun fromId(id: String?): ScrollDirection? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * 一次具体的触控动作。
 *
 * 坐标一律用**真实屏幕像素**。归一化坐标的换算在工具层做，
 * 这一层不掺和，避免两处都在换算导致算两次。
 */
data class TouchAction(
    val kind: TouchKind,
    /**
     * UI 控件树里的元素编号。
     *
     * 这是**首选的定位方式** —— 大于 0 时优先按编号操作，
     * 坐标直接来自系统给的 bounds，不存在模型算偏的问题。
     * 为 0 时才退回用 x/y 坐标。
     */
    val targetIndex: Int = 0,
    /** 起点（多数动作只用这一个点） */
    val x: Int = 0,
    val y: Int = 0,
    /** 终点，滑动和拖拽用 */
    val x2: Int = 0,
    val y2: Int = 0,
    /** 时长，毫秒 */
    val durationMs: Int = 0,
    /** 滚动方向，SCROLL 动作用 */
    val direction: ScrollDirection? = null,
    /**
     * 滚动距离占屏幕的比例。0 表示用默认值。
     * 方向式滚动用这个算起终点，模型不需要操心具体像素。
     */
    val distanceRatio: Float = 0f,
    /** 输入文字的内容 */
    val text: String = "",
    /** 打开应用的包名 */
    val packageName: String = "",
)

/**
 * 触控执行器接口。
 *
 * 现在只有这个接口，没有实现 —— 因为底层通道（ADB / Shizuku / 无障碍）还没定。
 * 定下来之后写一个实现类即可，上层不用改。
 *
 * 之所以现在就定接口：让"有哪些触控方式"这件事先固化下来，
 * 界面、提示词、执行器都从这里取，不会三处各写一份然后对不上。
 */
interface TouchExecutor {

    /** 检查底层通道是否就绪（设备连上了吗、权限拿到了吗） */
    suspend fun isReady(): Boolean

    /** 底层通道的名字，显示给用户看，比如 "ADB"、"Shizuku" */
    fun channelName(): String

    /**
     * 执行一个触控动作。
     * 返回 null 表示成功，返回字符串表示失败原因（直接可以显示给用户）。
     */
    suspend fun execute(action: TouchAction): String?

    /** 屏幕尺寸，坐标换算和界面预览都要用 */
    suspend fun screenSize(): Pair<Int, Int>?
}
