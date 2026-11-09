package com.aiphone.assistant.browser

import com.aiphone.assistant.touch.TouchKind

/**
 * 浏览器通道的「交接」规则：哪些动作必须先离开浏览器，再交给主通道。
 *
 * 内置浏览器把操作锁在 WebView 里，open_app / 主页键 / 多任务键在这里
 * 都没有对应实现。以前它们被直接拒绝（「浏览器通道不负责打开应用」
 * 「浏览器通道不支持主页键」），而模型手上没有别的退出动作 ——
 * 于是进得去、出不来，只能放弃任务。
 *
 * 这三类动作的目标本来就是「离开当前页面、回到系统」，所以应当先
 * 关掉浏览器、切回主通道，再按原动作执行。
 *
 * 反过来，网页里的点击/滚动/返回键等必须留在浏览器里继续操作页面。
 */
internal object BrowserHandoff {

    fun shouldLeaveBrowser(isBrowser: Boolean, kind: TouchKind): Boolean =
        isBrowser && when (kind) {
            TouchKind.OPEN_APP, TouchKind.KEY_HOME, TouchKind.KEY_RECENTS -> true
            else -> false
        }
}
