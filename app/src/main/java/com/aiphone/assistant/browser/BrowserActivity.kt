package com.aiphone.assistant.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.aiphone.assistant.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 内置浏览器窗口。
 *
 * ## 为什么它是 Activity 而不是一个 View
 *
 * WebView 必须在有窗口的环境里才会真正排版、执行 JS、画图。把它塞进
 * 悬浮窗也行，但悬浮窗是"浮在所有应用上面"的，用户没法正常滚动/输入，
 * 而且权限门槛更高。单独一个 Activity 最简单，也符合用户"打开浏览器看"
 * 的直觉。
 *
 * ## 这一层只做"窗口 + 执行器"
 *
 * 决策、提示词、任务推进全在 Agent 那边；这里不出现任何 AI 控制按钮 ——
 * 悬浮窗已经承担了急停/进度，重复放一套只会让用户困惑。
 */
class BrowserActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var urlText: TextView
    private val bridge = BrowserBridge()

    /** 当前这次导航的完成信号。每次 onPageStarted 换一个新的 */
    @Volatile
    private var ready: CompletableDeferred<Unit>? = null

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var helperInjected = false

    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("BrowserActivity", "onCreate，extra url=" + intent.getStringExtra(EXTRA_URL))
        BrowserDriver.attach(this)
        setContentView(buildLayout())

        // 允许 http 与 https 混排：不少国内站点主页面是 https、图片/脚本走 http，
        // 一律拦掉会出现"空白页但控制台没错误"的怪状
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.defaultTextEncodingName = "utf-8"

        webView.addJavascriptInterface(bridge, BrowserJs.HOST_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false // 一律在同一个 WebView 里打开，避免跳到系统浏览器

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                android.util.Log.i("BrowserActivity", "onPageStarted: " + url)
                // ⚠️ 只有"上一轮已经完成"时才换新的等待器。
                //
                // 踩过的坑：原来这里无条件 ready = CompletableDeferred()，而
                // open() 的 awaitReady 可能已经抓住**上一个**（永远不会完成的）
                // 等待器 —— 页面明明加载好了，awaitReady 却等到 10 秒超时，
                // 日志里是"页面还没加载完（等了 10 秒）"，而屏幕上页面早就开了。
                if (ready?.isCompleted != false) {
                    ready = CompletableDeferred()
                }
                loadError = null
                helperInjected = false
                if (!url.isNullOrBlank()) urlText.text = url
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                android.util.Log.i("BrowserActivity", "onPageFinished: " + url)
                // 页面脚本可能把 DOM 换了，helper 必须重新注入；
                // 注入是幂等的（version 相同直接 return）
                injectHelper()
                ready?.complete(Unit)
                if (!url.isNullOrBlank()) urlText.text = url
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // 子资源（图片/广告）失败不该把整页判死，只认主框架
                if (request?.isForMainFrame != true) return
                loadError = "页面加载失败：" + (error?.description?.toString() ?: "未知网络错误")
                ready?.complete(Unit)
                urlText.text = loadError
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (!url.isNullOrBlank()) urlText.text = url
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBackOrFinish()
            }
        })

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        beginLoad()
        android.util.Log.i("BrowserActivity", "loadUrl: " + url)
        webView.loadUrl(if (url.isBlank()) "about:blank" else url)
    }

    // ------------------------------------------------------------------
    // 布局：顶部一条窄工具条 + 全屏 WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(Color.parseColor("#F2F2F2"))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        urlText = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#555555"))
            setPadding(dp(4), 0, dp(8), 0)
            text = getString(R.string.browser_blank)
        }
        bar.addView(
            urlText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val back = toolButton("‹", getString(R.string.browser_back))
        back.setOnClickListener { goBackOrFinish() }
        val reload = toolButton("⟳", getString(R.string.browser_reload))
        reload.setOnClickListener {
            beginLoad()
            webView.reload()
        }
        val close = toolButton("✕", getString(R.string.browser_close))
        close.setOnClickListener { finish() }
        bar.addView(back)
        bar.addView(reload)
        bar.addView(close)

        webView = WebView(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun toolButton(label: String, desc: String): TextView = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        contentDescription = desc
        setTextColor(Color.parseColor("#333333"))
        val pad = dp(12)
        setPadding(pad, dp(4), pad, dp(4))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun goBackOrFinish() {
        if (::webView.isInitialized && webView.canGoBack()) {
            beginLoad()
            webView.goBack()
        } else {
            finish()
        }
    }

    /**
     * 开一次新的导航，并把等待器换成一个**干净的、没人抓过**的实例。
     *
     * 所有"我们自己发起"的导航（loadUrl / reload / goBack）都要走它，
     * 这样 awaitReady 抓到的永远是本次导航的那个。
     */
    private fun beginLoad() {
        ready = CompletableDeferred()
        loadError = null
        helperInjected = false
    }

    // ------------------------------------------------------------------
    // 给 BrowserDriver 用的执行接口
    // ------------------------------------------------------------------

    fun isAliveNow(): Boolean = !destroyed && !isFinishing && !isDestroyed

    /**
     * 等页面加载完。
     *
     * 超时要给明确原因，不能静默返回成功 —— 上层会以为页面能操作，
     * 结果后面所有 eval 都落到空白 about:blank 上，模型看到 0 个元素却
     * 不知道是"没加载完"。
     */
    suspend fun awaitReady(timeoutMs: Long): String? = withContext(Dispatchers.Main) {
        if (destroyed) return@withContext "内置浏览器已经关闭了"
        val d = ready ?: return@withContext "内置浏览器还没开始加载页面"
        withTimeoutOrNull(timeoutMs) { d.await() }
        when {
            destroyed -> "内置浏览器已经关闭了"
            loadError != null -> loadError
            d.isCompleted -> null
            else -> "页面还没加载完（等了 " + (timeoutMs / 1000) + " 秒），可能是网络太慢或被拦截了。"
        }
    }

    /** 在主线程执行一段 JS，返回结果字符串；null = 超时 / 页面被导航走 / 已销毁 */
    suspend fun runJs(source: String, timeoutMs: Long = 5000): String? = withContext(Dispatchers.Main) {
        if (destroyed || !::webView.isInitialized) return@withContext null
        try {
            bridge.eval(webView, source, timeoutMs)
        } catch (t: Throwable) {
            // Activity 销毁时 pending 会被强制唤醒成异常，这里统一转成"没结果"
            null
        }
    }

    /** 确保页面里已经装了 __zh__。每个动作前调一次，成本只是一次同步判断 */
    suspend fun prepareHelper() {
        if (helperInjected) return
        withContext(Dispatchers.Main) { injectHelper() }
    }

    private fun injectHelper() {
        if (destroyed || !::webView.isInitialized) return
        webView.evaluateJavascript(BrowserJs.HELPER, null)
        helperInjected = true
    }

    /** WebView 的物理像素宽度。JS 用它把 CSS 像素换算成屏幕像素 */
    fun viewWidth(): Int = if (::webView.isInitialized) webView.width else 0

    fun webViewSize(): Pair<Int, Int> =
        if (::webView.isInitialized) webView.width to webView.height else 0 to 0

    /** WebView 在整块屏幕上的左上角，用于把页面坐标变成水波纹坐标 */
    suspend fun locationOnScreen(): Pair<Int, Int> = withContext(Dispatchers.Main) {
        if (!::webView.isInitialized) return@withContext 0 to 0
        val loc = IntArray(2)
        webView.getLocationOnScreen(loc)
        loc[0] to loc[1]
    }

    /**
     * 把 WebView 当前画面画成 JPEG。
     *
     * 为什么用 draw(Canvas) 而不是系统截屏：这条通道的目标就是"不依赖
     * 无障碍/Shizuku 也能看到页面"，draw 只截 WebView 自己的内容，
     * 坐标和 <img> 里的 element bounds 天然对齐（都以 WebView 左上角为原点）。
     */
    suspend fun captureJpeg(): ByteArray? = withContext(Dispatchers.Main) {
        if (destroyed || !::webView.isInitialized) return@withContext null
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return@withContext null
        runCatching {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bitmap))
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bitmap.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        bridge.cancelAll("内置浏览器已关闭")
        // 把还在等页面的调用放出来，让上层拿到"已关闭"而不是一直挂到超时
        ready?.complete(Unit)
        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        BrowserDriver.detach(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.aiphone.assistant.browser.EXTRA_URL"
    }
}
