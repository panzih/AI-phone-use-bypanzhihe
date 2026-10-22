package com.aiphone.assistant.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aiphone.assistant.R
import com.aiphone.assistant.data.stepsLabel

/**
 * AI 操作手机时的悬浮控制面板。
 *
 * ## 为什么是 Service 而不是 Activity
 *
 * 需求原话是"做一个透明的 Activity 浮在应用上层"。这在 Android 上做不到：
 * Activity 一旦启动，被它盖住的应用就退到后台、不再渲染了 ——
 * 透明背景也救不了，你看到的会是壁纸而不是那个应用。
 *
 * 能真正做到"浮在别的应用之上"的只有两条路：画中画（不能透明、窗口小、
 * 只能在角落）和悬浮窗 `TYPE_APPLICATION_OVERLAY`。按需求要的界面
 * （全屏透明 + 左上角状态卡 + 底部居中按钮），只有悬浮窗放得下。
 * 所以这里用 Service + WindowManager。**画面效果和需求描述完全一致**，
 * 区别只是它在系统眼里叫"窗口"而不是"Activity"。
 *
 * ## 为什么只有一个窗口、还只占屏幕底边一条
 *
 * 一个全屏窗口会把整块屏幕都变成可触摸区域，AI 注入的点击会打在它身上。
 * 所以悬浮条只占**屏幕底部一条**（状态 / 三个键 / 指标都在这一条里，
 * 见 [buildBottomBar]）：
 *
 *   水波层  不可触摸（FLAG_NOT_TOUCHABLE）—— 永不吃点击
 *   悬浮条  可触摸，但只有屏幕底边那一块
 *
 * 其余区域完全是空的，用户还能同时正常用手机。
 *
 * ## 截图前必须藏
 *
 * 无障碍截图抓的是整块物理屏，悬浮窗会出现在图里 —— 模型会把
 * "急停按钮"当成界面元素去点它。而且注入的点击如果落在按钮上，
 * 会把自己的任务停掉。所以 Agent 在每次截图和注入前后都会喊
 * [setVisible]。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var statusView: View? = null

    /** 「后台」按钮（主屏时切副屏、副屏时回前台） */
    private var backgroundButton: View? = null
    private var backgroundLabel: TextView? = null

    /**
     * 按钮当前方向：true = 副屏模式（点它回迁）、false = 主屏模式（点它切副屏）。
     * 由 Agent 经 updateMoveButton 同步，Service 不自己拿 isVirtualDisplay 猜。
     */
    private var moveBackMode = false

    /**
     * 点击水波层。全屏、不可触摸、只在有脉冲时绘制。
     *
     * 单独一个窗口而不是画在状态卡里 —— 点击可能发生在屏幕任何位置。
     */
    private var rippleView: RippleView? = null

    /** 「接管」按钮与它的文字（录制模式下要把它从「接管」改成「停止」） */
    private var takeoverButton: View? = null
    private var takeoverLabel: TextView? = null

    /** 「补充」按钮与它的文字（录制时整块隐藏） */
    private var supplementButton: View? = null
    private var supplementLabel: TextView? = null

    /**
     * 状态卡的窗口参数。
     *
     * 必须留一份引用 —— 见 [updateStatus] 里那个 Android 的坑。
     */
    private var cardParams: WindowManager.LayoutParams? = null

    private lateinit var phaseDot: View
    private lateinit var phaseText: TextView
    private lateinit var stepText: TextView
    private lateinit var currentText: TextView
    private lateinit var hitText: TextView
    private lateinit var ttftText: TextView
    private lateinit var nextText: TextView

    /** 指标行：本次任务累计 token */
    private lateinit var totalText: TextView

    /** 指标行：上下文占用百分比 */
    private lateinit var contextText: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 必须第一时间挂前台服务：startForegroundService 之后系统只给一个很短的
        // 窗口；进程冷启动、切后台过渡、通知权限对话框并发时主线程可能被拖住，
        // 放在它前面的每一步都在挤占这个窗口，超时会抛
        // ForegroundServiceDidNotStartInTimeException（B1）。这是系统调度竞态、
        // 应用层无法根除，只能把「到 startForeground 的路径」压到最短。
        // 本函数只用到 NotificationManager、不依赖下面的 wm，可安全前置；
        // 它内部已 try/catch，失败会主动 stopSelf 收掉自己，不让系统超时杀进程。
        startForegroundCompat()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        OverlayBus.service = this
        android.util.Log.i(TAG, "onCreate：可以画悬浮窗 = ${canDrawOverlays()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 录制时这个按钮是"停止录制"，别去动 Agent 的停止标志
                if (OverlayBus.recordingMode) {
                    OverlayBus.requestRecordingStop()
                } else {
                    OverlayBus.requestStop()
                }
                // 不立刻移除 —— 让 Agent 走到检查点、把日志收尾。
                // 悬浮窗在任务结束时由 stopSelf 那条路移除。
            }

            else -> {
                android.util.Log.i(TAG, "onStartCommand：可以画悬浮窗 = ${canDrawOverlays()}")
                if (!canDrawOverlays()) {
                    // 没有权限就干不了活。这里不弹 UI（Service 弹窗体验很差），
                    // 交给主界面去引导用户授权。
                    OverlayBus.service = null
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlay()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        OverlayBus.service = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 权限与通知
    // ------------------------------------------------------------------

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /**
     * 起前台服务。
     *
     * Android 8+ 前台服务必须挂一个常驻通知；Android 14+ 还必须声明
     * foregroundServiceType。用 specialUse 是因为这个服务既不是定位、
     * 也不是播放，是"AI 操作期间的悬浮控制面板"。
     *
     * 通知上挂了一个「急停」按钮 —— 顺带把"下拉就能停"这条也做了，
     * 悬浮窗正好被藏着的那一瞬间用户还有这条退路。
     */
    private fun startForegroundCompat() {
        // 起不了前台服务也不能让它崩：悬浮窗本就是「锦上添花」，没它任务照样要
        // 跑通。specialUse 权限 / 通知渠道 / 资源等任何异常，都降级为「本次无悬浮窗」。
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI 操作手机",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "AI 正在操作手机时的状态与急停"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val stopIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("纸盒正在操作手机")
                .setContentText("点通知里的「急停」可以随时停下")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "急停",
                        stopIntent,
                    ).build()
                )
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            android.util.Log.e(
                TAG,
                "startForeground 失败，主动收掉服务、本次无悬浮窗，任务继续：${t.message}",
                t,
            )
            // 进不了前台就不能让服务悬在「没进前台」的状态：主动销毁，平台检测到
            // 服务在销毁会取消前台超时，不会再抛 DidNotStart；任务协程在
            // MainActivity、与本服务解耦，无悬浮窗也能照常跑完（B1 闭环）。
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // 悬浮窗
    // ------------------------------------------------------------------

    private fun showOverlay() {
        if (statusView != null) return

        // 0.10.0：状态条 + 按钮面板**合成一条**，整体挪到**屏幕下方**。
        //
        // 原来分两个窗口：顶部一条状态卡 + 底部一摞按钮。现在按用户画的
        // 草图（照豆包手机的样子）合成一块：状态行 / 三个按钮 / 指标行。
        //
        // 合成的好处不只是好看：原来那块"底部按钮面板"是可触摸窗口，
        // AI 注入的点击落在它上面会被吃掉（点到急停会把自己停掉）。
        // 现在只有一条窗口，要判重叠也只判这一条。
        val bar = buildBottomBar()
        val ripple = RippleView(this)

        // 先按屏宽量一次内容高度，再拿这个高度去建窗口 ——
        // 写死高度踩过坑：三行内容比常量高一点，指标行就被窗口底边切掉半截。
        // 宽度必须是固定值（理由见下），高度这次让内容自己说话。
        val barWidth = resources.displayMetrics.widthPixels - dp(24)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(barWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val barHeight = bar.measuredHeight

        // 用**固定尺寸**，不用 WRAP_CONTENT。
        //
        // 踩过的坑：WRAP_CONTENT 的窗口只在 addView / updateViewLayout 时
        // 算一次尺寸，而且这里量出来的宽度退化成了"只剩内边距"——
        // 卡片缩成一条竖着的灰条，文字全被压没了。
        // 固定尺寸一次到位，顺带还有个好处：面板不会随文字长短忽大忽小。
        val barParams = baseParams(
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            x = 0,
            // 抬高一点，别压在系统导航条（手势条 / 虚拟按键）上
            y = dp(12) + navBarHeight(),
            flags = FLAGS_BUTTON,
        ).also {
            // 横向条：铺满"屏幕宽 - 两边各 12dp"，高度固定
            it.width = barWidth
            it.height = barHeight
            // 0.9.19：Android 12+ 的「不信任触摸」保护——屏幕上叠加的不透明
            // 遮挡 > 0.80 时，系统直接丢弃注入的点击（a11y/adb 都算）。
            // 合并成一条之后有效遮挡 = 底色不透明度 0.9 × 窗口 alpha 0.85 = 0.765，
            // 仍在 0.80 以下（见水波层那段的合成算式）。
            it.alpha = 0.85f
            this.cardParams = it
        }

        // ---- 背景模糊（0.10.2）----
        //
        // 想做成「磨砂玻璃」那种动态模糊。Android 自带的路子只有一条：
        // **跨窗口模糊**（Android 12 / API 31 起）——
        //   窗口声明 `setBlurBehindRadius(px)` + `FLAG_BLUR_BEHIND`，
        //   系统把窗口**背后那块屏幕内容**实时模糊后衬在窗口下面。
        //   官方 API：WindowManager.LayoutParams#setBlurBehindRadius（API 31+）。
        //
        // 三个必须知道的限制（都在这段代码里挡住了）：
        //   1. 系统级开关：`WindowManager.isCrossWindowBlurEnabled` 为 false 时
        //      设了也**完全没效果**（模拟器、不少国产 ROM 默认关）。
        //   2. 主线程实时合成，低端机上持续模糊会掉帧 —— 所以半径给 20dp 左右，
        //      不是越大越好。
        //   3. 我们**拿不到**被模糊的那层图（它是系统合成的），所以没法自己做
        //      "截图 + 高斯模糊"来兜底：那需要每帧截屏，代价远大于收益。
        val blurPx = backgroundBlurRadiusPx()
        if (blurPx > 0) {
            barParams.flags = barParams.flags or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            // 公开 API 名字是 setBlurBehindRadius（API 31+）；
            // 文档里常被写成 setBackgroundBlurRadius，那是内部/系统签名那个
            barParams.setBlurBehindRadius(blurPx)
            // 有模糊撑底，底色可以更透，磨砂感才看得出来
            (bar.background as? GradientDrawable)
                ?.setColor(Color.parseColor(BAR_BG_BLURRED))
        } else {
            (bar.background as? GradientDrawable)
                ?.setColor(Color.parseColor(BAR_BG_SOLID))
        }

        // 逐个加，哪一个失败都不影响另一个 —— 但**必须把失败打出来**。
        // 静默失败会让"悬浮窗没出现"变成一个完全无从下手的现象。
        runCatching { wm.addView(bar, barParams); statusView = bar }
            .onSuccess { android.util.Log.i(TAG, "底部悬浮条已添加") }
            .onFailure { android.util.Log.e(TAG, "底部悬浮条添加失败：$it") }

        // 水波层铺满整屏。加在最后 = 盖在悬浮条之上。
        // 水波层自己是 FLAG_NOT_TOUCHABLE 的，不吃点击，互不影响
        val rippleParams = baseParams(
            gravity = Gravity.TOP or Gravity.START,
            x = 0,
            y = 0,
            flags = FLAGS_PASSIVE,
        ).also {
            it.width = WindowManager.LayoutParams.MATCH_PARENT
            it.height = WindowManager.LayoutParams.MATCH_PARENT
            // 0.9.19：见 barParams 处的注释。
            // 合成算式：1-(1-0.765)×(1-0.05) = 0.7768 < 0.80 ✓
            // 水波层必须压到 0.05 —— 0.15 的话合成是 0.805，**越线**，
            // AI 点在这条附近会被系统整片丢弃（0.9.19 踩过：搜索框点不动）。
            // 水波自身的视觉反馈靠内部画笔 alpha（RippleView），窗口 alpha 只做整体系数。
            it.alpha = 0.05f
        }
        runCatching { wm.addView(ripple, rippleParams); rippleView = ripple }
            .onSuccess { android.util.Log.i(TAG, "水波层已添加") }
            .onFailure { android.util.Log.e(TAG, "水波层添加失败：$it") }
    }

    private fun removeOverlay() {
        statusView?.let { runCatching { wm.removeView(it) } }
        rippleView?.let { runCatching { wm.removeView(it) } }
        statusView = null
        rippleView = null
    }

    private fun baseParams(
        gravity: Int,
        x: Int,
        y: Int,
        flags: Int,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        this.x = x
        this.y = y
    }

    /**
     * 底部悬浮条（0.10.0 重做版，按用户 2026-10-20 的草图画）。
     *
     * 三行：
     *
     *   当前：点开搜索框                    执行中
     *   下一步：输入关键词
     *   [补充] [接管] [后台]              14/49 步
     *   TTFT 1.3s   命中 98%   总量 26.0k   上下文 21%
     *
     * 几条硬约束（都是踩出来的）：
     *
     * 1. 窗口尺寸**固定**（见 [showOverlay]）：WRAP_CONTENT 的悬浮窗只在
     *    addView / updateViewLayout 时量一次，文字变长不会自己撑开。
     * 2. 高度**不写死** —— 先在 [showOverlay] 里按屏宽量一次内容高度再设窗口，
     *    写死过一次，指标行被窗口底边切掉半截。
     * 3. 指标行四格都用**左对齐 + 等分**：右对齐时文字一长就从左边被切掉，
     *    看起来像"数字丢了"（0.10.0 第一版就是这么错的）。
     */
    private fun buildBottomBar(): View {
        phaseDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AgentPhase.IDLE.color)
            }
        }
        phaseText = label(9f, "#99FFFFFF").apply {
            text = AgentPhase.IDLE.label
            gravity = Gravity.END
        }
        currentText = label(11.5f, "#FFFFFF").apply { text = "当前：准备中 …" }
        nextText = label(10f, "#FFD54F").apply { text = "下一步：—" }
        stepText = label(9.5f, "#B3FFFFFF").apply {
            text = "—"
            gravity = Gravity.END
        }
        ttftText = label(9.5f, "#B3FFFFFF").apply { text = "TTFT —" }
        hitText = label(9.5f, "#B3FFFFFF").apply { text = "命中 —" }
        totalText = label(9.5f, "#B3FFFFFF").apply { text = "总量 —" }
        contextText = label(9.5f, "#B3FFFFFF").apply { text = "上下文 —" }

        // ① 当前在干什么（右边角落留一个小小的阶段字，颜色跟圆点一致）
        val currentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(phaseDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
            addView(currentText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(phaseText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }

        // ② 下一步（黄色，给用户预判用 —— 觉得不对就在事情发生前按接管）
        val nextRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nextText, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        }

        // ③ 三个键 + 步数
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(buildSupplementButton(), LinearLayout.LayoutParams(0, -2, 1f))
            addView(buildTakeoverButton(), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            addView(buildBackgroundButton(), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            addView(stepText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }

        // ④ 指标行：四格等分、各自左对齐
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ttftText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(hitText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(totalText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(contextText, LinearLayout.LayoutParams(0, -2, 1f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                // 90% 不透明的 #1B1B1B。不能写死不透明：窗口 alpha 0.85 时
                // 有效遮挡会冲到 0.85，越过 Android 12+「不信任触摸」的 0.80 红线，
                // AI 注入的点击会被系统整片丢弃。0.9 × 0.85 = 0.765，留在红线内。
                setColor(Color.parseColor("#E61B1B1B"))
            }
            setPadding(dp(14), dp(9), dp(14), dp(10))
            addView(currentRow, LinearLayout.LayoutParams(-1, -2))
            addView(nextRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(buttonRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(metricsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
    }

    /**
     * 三个按钮共用的外壳：圆角、实底、按下有反馈。
     *
     * 差别只有「底色 + 文案 + 点了干什么」，所以统一一个工厂方法 ——
     * 各写一份的话，改一次圆角要改三处。
     */
    private fun actionButton(label: TextView, bgColor: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor(bgColor))
            }
            setPadding(dp(6), dp(7), dp(6), dp(7))
            addView(label, LinearLayout.LayoutParams(-1, -2))
            isClickable = true
            setOnClickListener {
                onClick()
                // 按下去先给一个"收到了"的即时反馈：不然用户会以为没点上、反复戳
                alpha = 0.6f
                postDelayed({ alpha = 1f }, 180)
            }
        }

    /** 按钮上的字 */
    private fun buttonLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /**
     * 「补充」＝ 原来的「暂停」。
     *
     * 按下去 AI 在下一个动作间隙停下，**纸盒自动回到前台、光标落进输入框**，
     * 用户补一句话再继续；那句话并进下一轮（不新开消息，缓存前缀不断）。
     */
    private fun buildSupplementButton(): View {
        val label = buttonLabel("补充")
        supplementLabel = label
        val btn = actionButton(label, BTN_PRIMARY) {
            OverlayBus.requestSupplement()
            label.text = "已补充"
        }
        supplementButton = btn
        return btn
    }

    /** 补充 / 继续按钮的真实状态（由 Agent 调，别人别调） */
    fun updateSupplementButton(paused: Boolean) {
        main.post {
            val btn = supplementButton ?: return@post
            val label = supplementLabel ?: return@post
            if (OverlayBus.recordingMode) {
                // 用 INVISIBLE 不用 GONE：GONE 会让这一格退出布局，三个键的位置会跳
                btn.visibility = View.INVISIBLE
                return@post
            }
            btn.visibility = View.VISIBLE
            label.text = if (paused) "继续" else "补充"
            // 暂停中/正常都是同一个主操作位，靠文案区分（继续 / 补充），不再换颜色
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor(BTN_PRIMARY))
            btn.alpha = 1f
        }
    }

    /**
     * 「接管」＝ 原来的「急停」。
     *
     * 真的不要了：这趟结束。历史完整保留，之后还能「复盘」。
     * 录制模式下这个键是"结束录制"，文案跟着变（见 [updateRecording]）。
     */
    private fun buildTakeoverButton(): View {
        val label = buttonLabel("接管")
        takeoverLabel = label
        val btn = actionButton(label, BTN_NORMAL) {
            if (OverlayBus.recordingMode) {
                OverlayBus.requestRecordingStop()
                label.text = "结束中 …"
            } else {
                OverlayBus.requestTakeover()
                label.text = "接管中 …"
            }
        }
        takeoverButton = btn
        return btn
    }

    /**
     * 「后台」＝ 原来的「切到副屏」。
     *
     * 主屏模式下按它 = AI 切到副屏去干，主屏还给用户；
     * 副屏模式下同一个键变成「回前台」。
     * 能不能点由 Agent 给（没 Shizuku 就没法切），见 [updateBackgroundButton]。
     */
    private fun buildBackgroundButton(): View {
        val label = buttonLabel("后台")
        backgroundLabel = label
        val btn = actionButton(label, BTN_DISABLED) {
            if (moveBackMode) {
                OverlayBus.requestReturnFromVd()
                label.text = "回前台 …"
            } else {
                OverlayBus.requestMoveToVirtualDisplay()
                label.text = "切换中 …"
            }
            backgroundButton?.isEnabled = false
        }
        btn.isEnabled = false
        backgroundButton = btn
        return btn
    }

    /**
     * 更新「后台」键：方向、文案、可点与否由 Agent 给定。
     *
     * 启用且主屏 = 蓝；启用且副屏 = 绿；禁用 = 灰。
     */
    fun updateBackgroundButton(enabled: Boolean, text: String, backMode: Boolean) {
        main.post {
            val btn = backgroundButton ?: return@post
            val label = backgroundLabel ?: return@post
            moveBackMode = backMode
            btn.isEnabled = enabled
            label.text = text
            // 主屏/副屏不再用蓝/绿区分（都是同一块底），只靠文案说清是切走还是回来
            val color = if (enabled) BTN_NORMAL else BTN_DISABLED
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor(color))
            btn.alpha = if (enabled) 1f else 0.5f
        }
    }

    /**
     * 显示 / 隐藏（截图和注入前后调）。
     *
     * ## 必须是 INVISIBLE，不能是 GONE —— 这是踩过的坑
     *
     * `GONE` 会让 view 退出测量和布局，而 WindowManager 窗口的尺寸
     * 是在布局里定下来的。结果是：一旦 GONE 过一次，窗口尺寸就退化，
     * 状态卡缩成一条只剩内边距的灰色竖条，文字全没了。
     *
     * `INVISIBLE` 保留占位和尺寸，只是不绘制 —— 正好是我们要的：
     * 截图上没有它，但尺寸不丢。
     *
     * 这个做法参考了肉包（Roubao）的 OverlayService：它用的就是
     * INVISIBLE，而且"隐藏 100ms → 截图 → 显示"这段时序也和我们一样。
     *
     * 也不用 removeView/addView：每一步要做两遍，太慢，闪烁会明显得多。
     */
    fun setVisible(visible: Boolean) {
        main.post {
            val v = if (visible) View.VISIBLE else View.INVISIBLE
            statusView?.visibility = v
            rippleView?.visibility = v
            // 藏起来之前把没画完的水波清掉：否则再显示时，
            // 会看到半截圈从旧位置继续扩散，像是点在了别的地方
            if (!visible) rippleView?.clear()
        }
    }

    /**
     * 在指定坐标闪一圈水波。
     *
     * 坐标是**屏幕坐标**（和窗口同一套），由通道层在执行动作时上报 ——
     * 那里才知道按编号点击最终落在了哪个元素的中心。
     */
    fun pulse(x: Int, y: Int) {
        main.post {
            val v = rippleView ?: return@post
            // 藏起来的时候不画 —— 那是正在截图，画了就等于污染画面
            if (v.visibility != View.VISIBLE) return@post
            v.pulseScreen(x, y)
        }
    }

    /**
     * 录制模式的状态卡。
     *
     * 复用同一个卡片，但字段含义变了：不是"第几步"，而是"录到第几步了"。
     * 按钮文案也要跟着改成"停止" —— 这一步不能省，否则用户不知道
     * 那个红按钮现在是干什么的。
     */
    fun updateRecording(count: Int) {
        main.post {
            phaseText.text = "录制中"
            (phaseDot.background as? GradientDrawable)?.setColor(RECORDING_COLOR)
            stepText.text = "已记录 $count 步"
            currentText.text = "当前：手动操作手机"
            nextText.text = "下一步：点下面的按钮结束录制"
            takeoverLabel?.text = "停止"
            // 录制时「补充」没有意义（暂停的是 AI 循环，不是录制）
            supplementButton?.visibility = View.INVISIBLE
        }
    }

    /**
     * 右侧那三个数字（由 Agent 每次拿到模型结果后刷）。
     *
     * @param hitPercent 缓存命中率，null = 这一轮没有统计（比如免费通道没给 usage）
     */
    fun updateMetrics(
        hitPercent: Double?,
        ttftMs: Long,
        contextPercent: Int? = null,
        totalTokens: Int? = null,
    ) {
        main.post {
            ttftText.text = when {
                ttftMs <= 0 -> "TTFT —"
                ttftMs < 1000 -> "TTFT ${ttftMs}ms"
                else -> "TTFT %.1fs".format(ttftMs / 1000.0)
            }
            hitText.text = if (hitPercent == null) "命中 —" else "命中 %.1f%%".format(hitPercent)
            // 「总量」是**本次任务累计**（输入+输出），不是本轮 ——
            // 用户问的是"这一趟花了多少"，只看本轮看不出量级。
            totalText.text = when {
                totalTokens == null || totalTokens <= 0 -> "总量 —"
                totalTokens < 1000 -> "总量 $totalTokens"
                else -> "总量 %.1fk".format(totalTokens / 1000.0)
            }
            // 上下文占用：服务端真实 prompt_tokens ÷ 窗口上限（见 Agent）
            contextText.text = if (contextPercent == null) {
                "上下文 —"
            } else {
                "上下文 $contextPercent%"
            }
            android.util.Log.i(
                TAG,
                "指标: 命中=" + hitPercent + " TTFT=" + ttftMs + "ms 总量=" + totalTokens + " 上下文=" + contextPercent + "%",
            )
        }
    }

    /** 切换阶段。圆点颜色和文字一起变 */
    fun updatePhase(phase: AgentPhase) {
        main.post {
            phaseText.text = phase.label
            (phaseDot.background as? GradientDrawable)?.setColor(phase.color)
        }
    }

    /**
     * 这个坐标是不是压在底部按钮面板（「切到副屏」+「急停」）上。
     *
     * 面板是可触摸窗口，注入的点击落在它上面会被它吃掉（点到急停还会把
     * 自己的任务停掉、点到切副屏会中途凭空迁移）。两个按钮都要算，
     * 只有真重叠时才把悬浮窗藏起来 —— 其余时候留着，
     * 用户才能看到"正在操作手机"。
     */
    fun overlapsOverlayButtons(x: Int, y: Int): Boolean {
        // 0.10.0：状态条和按钮合成一条窗口了，所以判重叠就判这一条。
        val v = statusView ?: return false
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        // 留 8dp 余量：按钮有圆角，真实可点区域比矩形略小，
        // 宁可多藏一下也不要漏
        val pad = dp(8)
        return x >= loc[0] - pad && x <= loc[0] + v.width + pad &&
            y >= loc[1] - pad && y <= loc[1] + v.height + pad
    }

    fun updateStatus(step: Int, maxSteps: Int, current: String, nextHint: String) {
        main.post {
            // 普通任务里「补充」要在（录制结束回到任务时它可能还是 GONE）
            supplementButton?.visibility = View.VISIBLE
            stepText.text = stepsLabel(step, maxSteps)
            currentText.text = "当前：$current"
            nextText.text = if (nextHint.isBlank()) "下一步：—" else "下一步：$nextHint"

            // ⚠️ 这一行不能省。
            //
            // WindowManager 的 WRAP_CONTENT 窗口**只在 addView / updateViewLayout
            // 时算一次尺寸**。文字变长之后 view 自己会 requestLayout，
            // 但那个窗口不会被重新测量 —— 结果就是卡片停留在第一次的窄尺寸上，
            // 后面的长文字全被压扁。这是实际踩到的：第一次渲染时内容是
            // "准备中 ..."，窗口就只有那么宽，之后写什么都撑不开。
            val view = statusView ?: return@post
            val params = cardParams ?: return@post
            runCatching { wm.updateViewLayout(view, params) }
                .onFailure { android.util.Log.w(TAG, "重新布局状态卡失败：$it") }
        }
    }

    // ------------------------------------------------------------------

    /** 建一个统一样式的单行文字（悬浮条里所有文字都走它，字号/颜色只在调用处写） */
    private fun label(sizeSp: Float, color: String): TextView = TextView(this).apply {
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    /**
     * 这条窗口能用多大的背景模糊半径；**0 = 这台机器不支持**。
     *
     * 判断顺序很重要：版本够 → 系统真开了跨窗口模糊 → 才给半径。
     * 只判版本的话，在模拟器/关掉模糊的 ROM 上会白设一个永不生效的窗口标志。
     */
    private fun backgroundBlurRadiusPx(): Int {
        if (!OverlayBus.overlayBlurEnabled) {
            android.util.Log.i(TAG, "跨窗口模糊：设置里没开，用半透明底")
            return 0
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            android.util.Log.i(TAG, "跨窗口模糊：系统版本 < Android 12，不支持")
            return 0
        }
        val enabled = runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        android.util.Log.i(
            TAG,
            if (enabled) {
                "跨窗口模糊：系统支持，半径 ${BLUR_RADIUS_DP}dp"
            } else {
                "跨窗口模糊：系统未启用（模拟器或 ROM 关掉了），退回纯半透明底"
            },
        )
        return if (enabled) dp(BLUR_RADIUS_DP) else 0
    }

    /**
     * 底部导航条（手势条 / 虚拟按键）的高度，dp。
     *
     * 悬浮条要抬到它**上面**，否则会被系统导航条盖住 —— 而导航条那一块
     * 恰恰是很多应用放主操作按钮的地方，AI 想点那里就会被挡住。
     *
     * 取不到就按 0 处理（老设备 / 某些 ROM 拿不到 insets），
     * 那样悬浮条只是贴着屏幕底边，不会崩。
     */
    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id <= 0) return 0
        return runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0)
    }

    companion object {
        private const val TAG = "OverlayService"

        /*
         * 三个键**一律不带色相**（用户 2026-10-22："把颜色去掉吧，那个颜色太丑了"）。
         *
         * 原来琥珀/红/蓝三块彩色按钮压在网页上非常吵，而且和"豆包那种安静的
         * 深色条"完全不是一个调子。现在只用三层白透明度做层级：
         *
         *   主操作（补充 / 继续）  20% 白 —— 最亮，因为最常按
         *   其余两键              12% 白
         *   不可用（没 Shizuku）   6% 白 + 整体 alpha 压到 0.5
         *
         * 深色底 + 白字 + 圆角，没有第二种色相，跟背景是照片还是网页都不打架。
         */
        /** 悬浮条底色：不支持模糊时用较实的（90% 不透明），支持时更透（70%） */
        private const val BAR_BG_SOLID = "#E61B1B1B"
        // 模糊版底色仍要够深：模糊会把**背后的内容**透上来，网页是白底时
        // 70% 的底会把白字衬成"浅灰底白字"，几乎看不清（实测截图）。
        // 85% × 窗口 alpha 0.85 = 0.72 的有效遮挡，仍在「不信任触摸」0.80 以下。
        private const val BAR_BG_BLURRED = "#D91B1B1B"

        /**
         * 背景模糊半径（dp）。
         *
         * 20dp 是"看得出是磨砂、又不至于把背后内容糊成一坨"的折中；
         * 再大一点在小屏上会把悬浮条自己的圆角边缘也糊掉。
         */
        private const val BLUR_RADIUS_DP = 20

        private const val BTN_PRIMARY = "#33FFFFFF"
        private const val BTN_NORMAL = "#1FFFFFFF"
        private const val BTN_DISABLED = "#0FFFFFFF"
        private const val CHANNEL_ID = "aiphone_overlay"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.aiphone.assistant.overlay.START"
        const val ACTION_STOP = "com.aiphone.assistant.overlay.STOP"

        /*
         * FLAG_KEEP_SCREEN_ON 是这次从肉包那里学到的，很重要：
         *
         * 任务跑到一半屏幕自动熄灭的话，无障碍截图会直接失败
         * （实际见过日志里连着几步 "截图失败，跳过本轮"）。
         * 加了这个标志，只要悬浮窗还在，屏幕就不会睡。
         * 只影响我们这个窗口存活期间，任务结束就恢复正常。
         */

        /**
         * 状态卡：不抢焦点，而且**完全不可触摸**。
         *
         * FLAG_NOT_TOUCHABLE 是关键 —— 它保证这块永远不会吃掉
         * AI 注入的点击，用户可以放心把它盖在界面任何位置。
         */
        /** 录制态的圆点颜色（橙色，和 Agent 的各个阶段区分开） */
        val RECORDING_COLOR = 0xFFFF7043.toInt()

        private const val FLAGS_PASSIVE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        /**
         * 急停按钮：不抢焦点但可触摸。
         *
         * 不抢焦点很重要：抢了的话被操作的应用会失焦，
         * 界面状态可能因此改变。
         */
        private const val FLAGS_BUTTON =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 任务结束，收掉悬浮窗 */
        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
