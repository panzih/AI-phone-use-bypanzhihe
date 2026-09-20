package com.aiphone.assistant.agent

import com.aiphone.assistant.a11y.UiNode
import java.security.MessageDigest

/**
 * 页面指纹：把当前界面控件的「资源 id + 类名 + 文字 + 量化坐标」
 * 拼成串做哈希。
 *
 * - 界面没变 → 指纹不变；界面变了 → 指纹通常会变。
 * - 坐标统一除以 10 做量化，吸收一两像素的抖动。
 * - 节点按坐标排序，保证同一界面每次算出来都一样。
 *
 * 用来判断“界面是不是已经稳定”（[Agent.awaitStable]），以及动作执行后
 * 验证界面有没有变化（[Agent.verifyAction]）。
 *
 * 注意：指纹把文字和坐标都算进去了，应用内时钟、秒表、进度条、动画这类
 * 持续变字/位移的界面会让指纹一直变，那种情况靠 awaitStable 的 hardCap
 * 兜底，而不是指纹“抗干扰”。它相对截图 MD5 的真正优势是：不用截图、
 * 不受截图限流、直接比控件语义。
 */
object PageFingerprint {

    /** 单个控件的文字最长取这么多，避免长文本把串撑爆 */
    private const val LABEL_CAP = 30

    /** 整条拼接串最长取这么多再哈希 */
    private const val TOTAL_CAP = 2000

    /** 计算当前节点列表的页面指纹（取前 16 位） */
    fun fingerprint(nodes: List<UiNode>): String {
        val sorted = nodes.sortedWith(
            compareBy<UiNode> { it.bounds.top }.thenBy { it.bounds.left }
        )
        val sb = StringBuilder()
        for (n in sorted) {
            // viewId 是比文字更稳定的键；类名区分控件种类
            sb.append(n.viewId).append('|')
            sb.append(n.className).append('|')
            // 文字优先，纯图标按钮退到无障碍描述
            sb.append(n.text.ifBlank { n.contentDesc }.take(LABEL_CAP)).append('|')
            sb.append(n.bounds.left / 10).append(',')
            sb.append(n.bounds.top / 10).append(',')
            sb.append(n.bounds.right / 10).append(',')
            sb.append(n.bounds.bottom / 10).append(';')
            if (sb.length >= TOTAL_CAP) break
        }
        return sha256(sb.toString().take(TOTAL_CAP)).take(16)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
