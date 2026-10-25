package com.aiphone.assistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aiphone.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 自动更新。
 *
 * 服务端是 `update-site/`（自己做的更新站，数据在云数据库里），
 * 客户端只认一个固定接口：`/api/v1/update`。发新版本不用改这个文件。
 *
 * 几个和更新站相关的硬约束，改的时候别踩：
 *   - 托管平台的分享码 `sharecode` **每个请求都要带**，否则网关直接 403；
 *   - 返回的 `downloadUrl` 已经带好了分享码，直接用，不要自己拼；
 *   - 只在这里用 `HttpURLConnection` + `org.json`（项目不引第三方运行时依赖）。
 */
object UpdateChecker {

    private const val ENDPOINT = "https://paperbox-update.app.workbuddy.host/api/v1/update"
    private const val SHARE_CODE = "3NBIVEHROW"
    private const val CHANNEL = "stable"

    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000

    /** 一次检查的结果。只在「确实有新版」时才会被构造出来 */
    data class Info(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val size: Long,
        val sha256: String,
        val downloadUrl: String,
        val force: Boolean,
    ) {
        val sizeText: String
            get() = when {
                size <= 0 -> ""
                size < 1024 * 1024 -> "${size / 1024} KB"
                // 固定 Locale.US：某些地区的小数点是逗号，会渲染成「3,5 MB」
                else -> String.format(java.util.Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
            }
    }

    /**
     * 解析接口返回。**抽成纯函数是为了能测** —— 真正容易出错的是这里
     * （字段缺失、downloadUrl 为空、force 在两处任一为真），不是 socket 那段。
     *
     * 没有新版、或返回里拿不到可下载的包时一律返回 null：
     * 推一个「点下载是 404」的版本比不推更糟。
     */
    internal fun parseInfo(json: String): Info? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.optBoolean("hasUpdate")) return null

        val latest = root.optJSONObject("latest") ?: return null

        // ⚠️ 必须显式判 null。`optString("downloadUrl")` 在值是 JSON null 时
        // 返回的是**字符串 "null"**，isBlank() 判不出来 —— 客户端会拿 "null"
        // 当下载地址，点下去必然失败。
        if (latest.isNull("downloadUrl")) return null
        val downloadUrl = latest.optString("downloadUrl", "")
        if (downloadUrl.isBlank()) return null

        return Info(
            versionCode = latest.optInt("versionCode"),
            versionName = latest.optString("versionName", "?"),
            changelog = latest.optString("changelog", ""),
            size = latest.optLong("size", 0L),
            sha256 = latest.optString("sha256", ""),
            downloadUrl = downloadUrl,
            force = root.optBoolean("force") || latest.optBoolean("force"),
        )
    }

    /**
     * 查一次。**没有新版返回 null，网络/解析失败返回 failure** ——
     * 调用方要能把「已经是最新」和「没查成」分开显示。
     */
    suspend fun check(context: Context): Result<Info?> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()

            // 版本名原样拼进 URL 会因为「-beta」「+2」这类字符出错，统一编码
            fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

            val url = URL(
                "$ENDPOINT?package=${enc(context.packageName)}" +
                    "&channel=$CHANNEL&sharecode=$SHARE_CODE" +
                    "&versionCode=${BuildConfig.VERSION_CODE}" +
                    "&versionName=${enc(BuildConfig.VERSION_NAME)}" +
                    "&deviceId=${enc(deviceId)}",
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    error("服务器返回 ${conn.responseCode}")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseInfo(text)
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 下载安装包到 `cacheDir/updates/`。
     *
     * 用 `HttpURLConnection` 自己下而不是 `DownloadManager`：下载完要**立刻**
     * 拉起安装，DownloadManager 得再注册一个广播接收器去等完成，多一个跨进程环节。
     */
    suspend fun download(
        context: Context,
        info: Info,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 旧包先清掉：一次只留一个，避免缓存里堆好几个 3.5MB 的 apk
        dir.listFiles()?.forEach { it.delete() }

        val safeName = info.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "paperbox-$safeName.apk")

        val conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("下载失败（${conn.responseCode}）")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (target.length() <= 0L) {
            target.delete()
            error("下载到的文件是空的")
        }
        // 服务端给了 sha256 就校验：能挡住网关返回的错误页被当安装包
        if (info.sha256.isNotBlank()) {
            val actual = sha256(target)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                target.delete()
                error("安装包校验没通过，请重试")
            }
        }
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * 装 APK。
 *
 * Android 8 起必须同时满足两件事，缺一个都会静默失败或直接抛异常：
 *   1. 清单里声明 `REQUEST_INSTALL_PACKAGES`；
 *   2. 用户在系统设置里允许本应用「安装未知应用」。
 * 所以先 [canInstall] 判断，不行就 [requestInstallPermission] 把人带过去。
 */
object ApkInstaller {

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
