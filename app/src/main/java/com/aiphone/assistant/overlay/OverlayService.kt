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

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var statusView: View? = null
    private var buttonView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private lateinit var stepText: TextView
    private lateinit var currentText: TextView
    private lateinit var nextText: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        OverlayBus.service = this
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> OverlayBus.requestStop()
            else -> {
                if (!canDrawOverlays()) {
                    OverlayBus.service = null
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlay()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { removeOverlay(); OverlayBus.service = null; super.onDestroy() }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AI 操作手机", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI 正在操作手机时的状态与急停"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("纸盒正在操作手机")
            .setContentText("点通知里的「急停」可以随时停下")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "急停", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlay() {
        if (statusView != null) return
        val card = buildStatusCard()
        val button = buildStopButton()
        val cardParams = baseParams(gravity = Gravity.TOP or Gravity.START, x = dp(12), y = dp(56), flags = FLAGS_PASSIVE).also {
            it.width = dp(CARD_WIDTH_DP); it.height = dp(CARD_HEIGHT_DP); this.cardParams = it
        }
        val buttonParams = baseParams(gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, x = 0, y = dp(72), flags = FLAGS_BUTTON)
        runCatching { wm.addView(card, cardParams); statusView = card }
        runCatching { wm.addView(button, buttonParams); buttonView = button }
    }

    private fun removeOverlay() {
        statusView?.let { runCatching { wm.removeView(it) } }
        buttonView?.let { runCatching { wm.removeView(it) } }
        statusView = null; buttonView = null
    }

    private fun baseParams(gravity: Int, x: Int, y: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply { this.gravity = gravity; this.x = x; this.y = y }

    private fun buildStatusCard(): View {
        stepText = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); text = "准备中 ..."; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        currentText = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        nextText = TextView(this).apply { setTextColor(Color.parseColor("#FFD54F")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#CC1B1B1B")) }
            setPadding(dp(12), dp(10), dp(14), dp(10))
            addView(stepText)
            addView(currentText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(nextText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
    }

    private fun buildStopButton(): View {
        val label = TextView(this).apply { text = "急停"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER; setPadding(dp(28), dp(12), dp(28), dp(12)) }
        return LinearLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.parseColor("#D32F2F")) }
            elevation = dp(6).toFloat()
            addView(label)
            isClickable = true
            setOnClickListener {
                OverlayBus.requestStop()
                label.text = "停止中 ..."
                background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.parseColor("#757575")) }
            }
        }
    }

    fun setVisible(visible: Boolean) {
        main.post {
            val v = if (visible) View.VISIBLE else View.INVISIBLE
            statusView?.visibility = v
            buttonView?.visibility = v
        }
    }

    fun updateStatus(step: Int, maxSteps: Int, current: String, nextHint: String) {
        main.post {
            stepText.text = "第 $step / $maxSteps 步"
            currentText.text = "正在：$current"
            nextText.text = if (nextHint.isBlank()) "下一步：—" else "下一步：$nextHint"
            val view = statusView ?: return@post
            val params = cardParams ?: return@post
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val CARD_WIDTH_DP = 232
        private const val CARD_HEIGHT_DP = 96
        private const val CHANNEL_ID = "aiphone_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.aiphone.assistant.overlay.START"
        const val ACTION_STOP = "com.aiphone.assistant.overlay.STOP"
        private const val FLAGS_PASSIVE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        private const val FLAGS_BUTTON =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, OverlayService::class.java)) }
    }
}
