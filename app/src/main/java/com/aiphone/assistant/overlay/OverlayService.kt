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
 * ## 为什么是两个窗口而不是一个全屏窗口
 *
 * 一个全屏窗口会把整块屏幕都变成可触摸区域，AI 注入的点击会打在它身上。
 * 做成两个 WRAP_CONTENT 的小窗口之后：
 *
 *   状态卡  不可触摸（FLAG_NOT_TOUCHABLE）—— 永不吃点击
 *   急停钮  可触摸，但只有它那一小块
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
    private var buttonView: View? = null

    /**
     * 点击水波层。全屏、不可触摸、只在有脉冲时绘制。
     *
     * 单独一个窗口而不是画在状态卡里 —— 点击可能发生在屏幕任何位置。
     */
    private var rippleView: RippleView? = null

    /** 底部按钮上的文字。录制模式下要把它从「急停」改成「停止」 */
    private var stopLabel: TextView? = null

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
    private lateinit var nextText: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        OverlayBus.service = this
        android.util.Log.i(TAG, "onCreate：可以画悬浮窗 = ${canDrawOverlays()}")
        startForegroundCompat()
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
    }

    // ------------------------------------------------------------------
    // 悬浮窗
    // ------------------------------------------------------------------

    private fun showOverlay() {
        if (statusView != null) return

        val card = buildStatusCard()
        val button = buildStopButton()
        val ripple = RippleView(this)

        // 状态卡用**固定尺寸**，不用 WRAP_CONTENT。
        //
        // 踩过的坑：WRAP_CONTENT 的窗口只在 addView / updateViewLayout 时
        // 算一次尺寸，而且这里量出来的宽度退化成了"只剩内边距"——
        // 卡片缩成一条竖着的灰条，文字全被压没了。
        // 固定尺寸一次到位，顺带还有个好处：面板不会随文字长短忽大忽小。
        val cardParams = baseParams(
            gravity = Gravity.TOP or Gravity.START,
            x = dp(12),
            y = dp(56),
            flags = FLAGS_PASSIVE,
        ).also {
            it.width = dp(CARD_WIDTH_DP)
            it.height = dp(CARD_HEIGHT_DP)
            this.cardParams = it
        }
        val buttonParams = baseParams(
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            x = 0,
            y = dp(72),
            flags = FLAGS_BUTTON,
        )

        // 逐个加，哪一个失败都不影响另一个 —— 但**必须把失败打出来**。
        // 静默失败会让"悬浮窗没出现"变成一个完全无从下手的现象。
        runCatching { wm.addView(card, cardParams); statusView = card }
            .onSuccess { android.util.Log.i(TAG, "状态卡已添加") }
            .onFailure { android.util.Log.e(TAG, "状态卡添加失败：$it") }
        runCatching { wm.addView(button, buttonParams); buttonView = button }
            .onSuccess { android.util.Log.i(TAG, "急停按钮已添加") }
            .onFailure { android.util.Log.e(TAG, "急停按钮添加失败：$it") }

        // 水波层铺满整屏。加在最后 = 盖在状态卡和按钮之上，
        // 而它们是 FLAG_NOT_TOUCHABLE 的，不吃点击，互不影响
        val rippleParams = baseParams(
            gravity = Gravity.TOP or Gravity.START,
            x = 0,
            y = 0,
            flags = FLAGS_PASSIVE,
        ).also {
            it.width = WindowManager.LayoutParams.MATCH_PARENT
            it.height = WindowManager.LayoutParams.MATCH_PARENT
        }
        runCatching { wm.addView(ripple, rippleParams); rippleView = ripple }
            .onSuccess { android.util.Log.i(TAG, "水波层已添加") }
            .onFailure { android.util.Log.e(TAG, "水波层添加失败：$it") }
    }

    private fun removeOverlay() {
        statusView?.let { runCatching { wm.removeView(it) } }
        buttonView?.let { runCatching { wm.removeView(it) } }
        rippleView?.let { runCatching { wm.removeView(it) } }
        statusView = null
        buttonView = null
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
     * 左上角的状态卡。
     *
     * 三行：第几步 / 正在做什么 / **下一步要做什么**。
     * 第三行是给用户预判用的 —— 觉得不对就能在事情发生前按急停。
     */
    private fun buildStatusCard(): View {
        // 阶段行：一个小圆点 + 文字。圆点用颜色区分阶段，
        // 扫一眼就知道它在忙什么，不用读字。
        phaseDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AgentPhase.IDLE.color)
            }
        }
        phaseText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = AgentPhase.IDLE.label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        stepText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "准备中 ..."
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        currentText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nextText = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#CC1B1B1B"))
            }
            setPadding(dp(12), dp(10), dp(14), dp(10))

            // 第一行：圆点 + 阶段
            addView(
                LinearLayout(this@OverlayService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        phaseDot,
                        LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) },
                    )
                    addView(phaseText)
                }
            )

            addView(stepText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(currentText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(nextText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
    }

    /** 底部居中的红色急停按钮 */
    private fun buildStopButton(): View {
        val label = TextView(this).apply {
            text = "急停"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(12), dp(28), dp(12))
        }
        stopLabel = label

        return LinearLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#D32F2F"))
            }
            // 轻微的投影，浅色界面下也看得清
            elevation = dp(6).toFloat()
            addView(label)
            isClickable = true
            setOnClickListener {
                // 录制时这个按钮是"结束录制"，不是"急停 AI"——
                // 不区分的话按下去只会置一个没人看的标志
                if (OverlayBus.recordingMode) {
                    OverlayBus.requestRecordingStop()
                    label.text = "正在结束 ..."
                } else {
                    OverlayBus.requestStop()
                    label.text = "停止中 ..."
                }
                // 按钮自己先变灰，给一个"已经收到了"的即时反馈 ——
                // 否则用户会以为没点上，反复戳
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(Color.parseColor("#757575"))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 给 OverlayBus 调
    // ------------------------------------------------------------------

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
            buttonView?.visibility = v
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
            currentText.text = "正在：手动操作手机"
            nextText.text = "下一步：点下面的按钮结束录制"
            stopLabel?.text = "停止"
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
     * 这个坐标是不是压在底部急停按钮上。
     *
     * 按钮是可触摸窗口，注入的点击落在它上面会被它吃掉。
     * 只有真重叠时才需要把悬浮窗藏起来 —— 其余时候留着，
     * 用户才能看到"正在操作手机"。
     */
    fun overlapsStopButton(x: Int, y: Int): Boolean {
        val v = buttonView ?: return false
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
            stepText.text = stepsLabel(step, maxSteps)
            currentText.text = "正在：$current"
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

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "OverlayService"

        /** 状态卡尺寸（dp）。固定尺寸是为了绕开 WRAP_CONTENT 窗口的测量坑 */
        private const val CARD_WIDTH_DP = 236
        private const val CARD_HEIGHT_DP = 118
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
