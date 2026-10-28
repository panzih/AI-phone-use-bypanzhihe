package com.aiphone.assistant.log

/**
 * 导出前脱敏。
 *
 * ## 目标不是"保证没有敏感信息"
 *
 * 截图里可能有聊天记录、支付页面、验证码，自由文本里可能有用户随口说的
 * 地址/电话 —— 这些靠正则不可能可靠识别。所以这里只做**确定能识别**的
 * 高价值凭据：API Key、Authorization 头、password/密码、验证码/OTP 字段。
 *
 * `manifest.txt` 会如实写明：文本已尽力脱敏，截图没有做任何遮挡。
 * 用户仍然要自己核对接收者。
 */
object LogSanitizer {

    /** `sk-...` 形式的 Key（OpenAI/DeepSeek/多数兼容网关） */
    private val API_KEY = Regex("""\bsk-[A-Za-z0-9_-]{12,}\b""")

    /** `Authorization: Bearer ...`；`Bearer public`（免费池）只有 6 位，不会命中 */
    private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}""")

    /** JSON 里的字符串凭据：`"api_key": "..."` / `"password": "..."` */
    private val JSON_SECRET = Regex(
        """(?i)("(?:api[_-]?key|access[_-]?token|secret|password|passwd|pwd|验证码|密码|otp|verification[_-]?code)"\s*:\s*)"[^"]*""""
    )

    /** JSON 里数字形式的 OTP：`"otp": 123456` */
    private val JSON_NUMERIC_OTP = Regex(
        """(?i)("(?:otp|verification[_-]?code|验证码)"\s*:\s*)\d{4,8}"""
    )

    /** 纯文本 / 日志里的 `password: xxx`、`api_key=xxx`、`验证码：123456` */
    private val PLAIN_SECRET = Regex(
        """(?i)((?:api[_-]?key|access[_-]?token|secret|password|passwd|pwd|验证码|密码|otp|verification[_-]?code)\s*[:=：]\s*)([^\s,;}\]"']+)"""
    )

    /** 中文验证码：「验证码 123456」「验证码是 123456」 */
    private val OTP_CN = Regex("""(验证码|动态码|校验码)\s*[:：是为]?\s*\d{4,8}""")

    /** 中文密码：「密码：hunter2」「密码是 hunter2」 */
    private val PASSWORD_CN = Regex("""(密码|口令)\s*[:：是为]?\s*\S+""")

    fun redact(text: String): String {
        var out = text
        out = API_KEY.replace(out, "sk-***")
        out = BEARER.replace(out, "Bearer ***")
        out = JSON_SECRET.replace(out, "$1\"***\"")
        out = JSON_NUMERIC_OTP.replace(out, "$1\"***\"")
        out = PLAIN_SECRET.replace(out, "$1***")
        out = OTP_CN.replace(out, "$1：***")
        out = PASSWORD_CN.replace(out, "$1：***")
        return out
    }
}
