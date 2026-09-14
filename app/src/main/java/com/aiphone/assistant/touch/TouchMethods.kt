package com.aiphone.assistant.touch

enum class TouchKind(
    val id: String, val label: String, val description: String,
    val needsPoint: Boolean, val needsDuration: Boolean,
    val requiresExtraComponent: Boolean = false, val extraNote: String? = null,
) {
    TAP(id = "tap", label = "点击", description = "在指定位置单击一次", needsPoint = true, needsDuration = false),
    LONG_PRESS(id = "long_press", label = "长按", description = "按住指定位置不放，常用于唤出菜单", needsPoint = true, needsDuration = true),
    DOUBLE_TAP(id = "double_tap", label = "双击", description = "快速点两下，用于放大或选中", needsPoint = true, needsDuration = false),
    SCROLL(id = "scroll", label = "滚动", description = "滚动页面或列表，只需说方向，不用给坐标", needsPoint = false, needsDuration = true),
    SWIPE(id = "swipe", label = "滑动", description = "从一点滑到另一点，松手前停一下，不会继续滚", needsPoint = true, needsDuration = true),
    FLICK(id = "flick", label = "甩动", description = "快速甩一下，松手后还会惯性滚动一段（刷列表常用）", needsPoint = true, needsDuration = true),
    DRAG(id = "drag", label = "拖拽", description = "按住并移动，比滑动更慢，用于移动图标、排序", needsPoint = true, needsDuration = true),
    PINCH_OUT(id = "pinch_out", label = "双指放大", description = "两根手指向外分开，放大画面", needsPoint = true, needsDuration = true),
    PINCH_IN(id = "pinch_in", label = "双指缩小", description = "两根手指向内合拢，缩小画面", needsPoint = true, needsDuration = true),
    MULTI_FINGER(id = "multi_finger", label = "多指手势", description = "三指及以上，用于特定游戏或应用操作", needsPoint = true, needsDuration = true, requiresExtraComponent = true, extraNote = "无障碍的多指能力已就绪，但还没做「自定义手指路径」的接口。"),
    INPUT_TEXT(id = "input_text", label = "输入文字", description = "往当前聚焦的输入框里打字", needsPoint = false, needsDuration = false),
    KEY_BACK(id = "key_back", label = "返回键", description = "相当于按一下系统返回", needsPoint = false, needsDuration = false),
    KEY_HOME(id = "key_home", label = "主页键", description = "回到桌面", needsPoint = false, needsDuration = false),
    KEY_RECENTS(id = "key_recents", label = "多任务键", description = "打开后台任务列表", needsPoint = false, needsDuration = false),
    OPEN_APP(id = "open_app", label = "打开应用", description = "按包名启动一个应用", needsPoint = false, needsDuration = false),
    WAIT(id = "wait", label = "等待", description = "等页面加载完再继续", needsPoint = false, needsDuration = true),
    ;

    companion object {
        fun fromId(id: String): TouchKind? = entries.firstOrNull { it.id == id }
        val availableNow: List<TouchKind> get() = entries.filter { !it.requiresExtraComponent }
        val needsExtra: List<TouchKind> get() = entries.filter { it.requiresExtraComponent }
    }
}

enum class ScrollDirection(val id: String, val label: String) {
    DOWN("down", "向下（看后面的内容）"), UP("up", "向上（看前面的内容）"),
    LEFT("left", "向左"), RIGHT("right", "向右"),
    ;
    companion object { fun fromId(id: String?): ScrollDirection? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } }
}

data class TouchAction(
    val kind: TouchKind,
    val targetIndex: Int = 0,
    val x: Int = 0, val y: Int = 0,
    val x2: Int = 0, val y2: Int = 0,
    val durationMs: Int = 0,
    val direction: ScrollDirection? = null,
    val distanceRatio: Float = 0f,
    val text: String = "",
    val packageName: String = "",
)

interface TouchExecutor {
    suspend fun isReady(): Boolean
    fun channelName(): String
    suspend fun execute(action: TouchAction): String?
    suspend fun screenSize(): Pair<Int, Int>?
}
