package com.aiphone.assistant.browser

import android.webkit.JavascriptInterface
import com.aiphone.assistant.a11y.UiNode
import android.graphics.Rect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 页面里注入的那段 JS，以及给 JS 传值的转义工具。
 *
 * ## 为什么把 DOM 采集放在页面里做
 *
 * 网页没有"控件树"这种系统级结构，但 DOM 本身就是一棵树。用 JS 遍历它，
 * 拿到的就是**页面作者自己声明的可交互元素**（a / button / input …），
 * 比在截图里猜像素稳得多。采集回来的坐标还要乘 WebView 的缩放比，
 * 才能和截图 / 水波纹落在同一套物理像素上。
 *
 * 这段脚本会被反复注入（每次导航后重新注入一次），所以必须幂等：
 * 已经存在就立刻返回，避免把页面里的 __zh__ 覆盖成空对象。
 */
internal object BrowserJs {

    /** JS 桥在页面里的全局名。Android 侧 addJavascriptInterface 用同一个名字。 */
    const val HOST_NAME = "__zhHost__"

    /**
     * 采集 + 操作脚本。
     *
     * 选择器刻意写得很松：除了标准可交互标签，还捞 [onclick] / [role=button]
     * / [contenteditable] —— 现代前端大量用 div 自己画按钮，只认标签会漏掉
     * 一半以上的可点元素。
     */
    val HELPER: String = """
(function () {
  if (window.__zh__ && window.__zh__.version === 3) return;
  var SEL = 'a,button,input,textarea,select,summary,label[for],[onclick],[role=button],[role=link],[role=checkbox],[role=tab],[role=switch],[contenteditable=""],[contenteditable=true],[tabindex]:not([tabindex="-1"])';

  function textOf(el) {
    var t = el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('title') || '';
    var tag = el.tagName;
    if (!t && (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')) t = el.value || '';
    if (!t) {
      var img = el.querySelector && el.querySelector('img[alt]');
      if (img) t = img.alt || '';
    }
    if (!t) t = el.innerText || el.textContent || '';
    return String(t).replace(/\s+/g, ' ').trim().slice(0, 40);
  }

  function visible(el) {
    if (!el.getClientRects || el.getClientRects().length === 0) return false;
    var s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse') return false;
    var op = parseFloat(s.opacity);
    if (!isNaN(op) && op === 0) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    if (r.bottom <= 0 || r.right <= 0) return false;
    if (r.top >= innerHeight || r.left >= innerWidth) return false;
    return true;
  }

  function all() {
    var list;
    try { list = document.querySelectorAll(SEL); }
    catch (e) { list = document.querySelectorAll('a,button,input,textarea,select'); }
    var out = [];
    for (var i = 0; i < list.length; i++) {
      if (visible(list[i])) out.push(list[i]);
    }
    return out;
  }

  function scale(viewWidth) {
    var iw = window.innerWidth || document.documentElement.clientWidth || 0;
    if (iw > 0 && viewWidth > 0) return viewWidth / iw;
    return window.devicePixelRatio || 1;
  }

  function box(el, sc) {
    var r = el.getBoundingClientRect();
    return { x: r.left * sc, y: r.top * sc, w: r.width * sc, h: r.height * sc };
  }

  function editable(el) {
    var t = el.tagName;
    return t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT' || el.isContentEditable === true;
  }

  window.__zh__ = {
    version: 3,

    snapshot: function (limit, viewWidth) {
      var els = all();
      var sc = scale(viewWidth);
      var n = Math.min(els.length, limit > 0 ? limit : els.length);
      var out = [];
      for (var i = 0; i < n; i++) {
        var el = els[i];
        var b = box(el, sc);
        out.push({
          tag: el.tagName,
          text: textOf(el),
          x: b.x, y: b.y, w: b.w, h: b.h,
          editable: editable(el),
          clickable: true,
          disabled: el.disabled === true
        });
      }
      return {
        url: String(location.href || ''),
        title: String(document.title || ''),
        total: els.length,
        truncated: els.length > n,
        scale: sc,
        elements: out
      };
    },

    // 按序号找回元素；hint 里的文字对不上就再用文字在全页找唯一一个，
    // 找不到返回 null —— 调用方必须如实失败，不能拿序号盲点
    pick: function (seq, want) {
      var els = all();
      var el = (seq > 0 && seq <= els.length) ? els[seq - 1] : null;
      if (el && want) {
        var t = textOf(el);
        if (t.indexOf(want) < 0 && want.indexOf(t) < 0) el = null;
      }
      if (!el && want) {
        var cand = [];
        for (var i = 0; i < els.length; i++) {
          var t2 = textOf(els[i]);
          if (t2 === want || (want.length > 1 && t2.indexOf(want) >= 0)) cand.push(els[i]);
        }
        if (cand.length === 1) el = cand[0];
      }
      return el;
    },

    click: function (seq, want, viewWidth) {
      var sc = scale(viewWidth);
      var el = this.pick(seq, want);
      if (!el) return { ok: false, reason: seq > 0 ? ('页面里找不到编号 ' + seq + ' 的元素') : '页面里找不到目标元素' };
      try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) {}
      var b = box(el, sc);
      var cx = b.x + b.w / 2, cy = b.y + b.h / 2;
      try { el.click(); } catch (e) { return { ok: false, reason: '点击时出错：' + e }; }
      return { ok: true, x: cx, y: cy, text: textOf(el) };
    },

    clickPoint: function (px, py, viewWidth) {
      var sc = scale(viewWidth);
      var el = document.elementFromPoint(px / sc, py / sc);
      if (!el) return { ok: false, reason: '这个位置上没有元素' };
      var target = el;
      while (target && target !== document.body &&
             !(target.tagName === 'A' || target.tagName === 'BUTTON' ||
               target.isContentEditable === true || target.getAttribute('role') === 'button' ||
               typeof target.onclick === 'function')) {
        target = target.parentElement;
      }
      var use = target || el;
      try { use.click(); } catch (e) { return { ok: false, reason: '点击时出错：' + e }; }
      var b = box(use, sc);
      return { ok: true, x: b.x + b.w / 2, y: b.y + b.h / 2, text: textOf(use) };
    },

    type: function (seq, want, value, viewWidth) {
      var el = this.pick(seq, want);
      if (!el && seq <= 0) {
        var ae = document.activeElement;
        if (ae && editable(ae)) el = ae;
      }
      if (!el && seq <= 0) {
        var els = all();
        for (var i = 0; i < els.length; i++) {
          if (editable(els[i])) { el = els[i]; break; }
        }
      }
      if (!el) return { ok: false, reason: '页面里找不到要输入的输入框' };
      try {
        el.focus();
        if (el.isContentEditable === true) {
          el.textContent = value;
        } else {
          var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
                    : (el.tagName === 'INPUT' ? HTMLInputElement.prototype : null);
          var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
          if (desc && desc.set) desc.set.call(el, value); else el.value = value;
        }
        // React 之类的受控组件只认原生 setter + 事件，直接改 .value 它看不见
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        var actual = el.isContentEditable === true ? (el.textContent || '') : (el.value || '');
        return { ok: true, x: 0, y: 0, text: String(actual) };
      } catch (e) {
        return { ok: false, reason: '写入输入框失败：' + e };
      }
    },

    scrollPx: function (dxPx, dyPx, viewWidth) {
      var sc = scale(viewWidth);
      try { window.scrollBy(dxPx / sc, dyPx / sc); }
      catch (e) { return { ok: false, reason: '滚动时出错：' + e }; }
      return { ok: true, x: 0, y: 0 };
    },

    back: function () {
      history.back();
      return { ok: true, x: 0, y: 0 };
    },

    longpress: function (seq, want, viewWidth) {
      var sc = scale(viewWidth);
      var el = this.pick(seq, want);
      if (!el) return { ok: false, reason: '页面里找不到目标元素' };
      try {
        el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, view: window }));
      } catch (e) {
        return { ok: false, reason: '长按失败：' + e };
      }
      var b = box(el, sc);
      return { ok: true, x: b.x + b.w / 2, y: b.y + b.h / 2 };
    },

    dblclick: function (seq, want, viewWidth) {
      var sc = scale(viewWidth);
      var el = this.pick(seq, want);
      if (!el) return { ok: false, reason: '页面里找不到目标元素' };
      try { el.click(); el.click(); }
      catch (e) { return { ok: false, reason: '双击失败：' + e }; }
      var b = box(el, sc);
      return { ok: true, x: b.x + b.w / 2, y: b.y + b.h / 2 };
    }
  };
})();
""".trimIndent()

    /** 把 Kotlin 字符串安全地塞进 JS 源码里（反斜杠、引号、换行都要转义） */
    fun jsString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /**
     * 把一段用户 JS 包进"执行 → 通过 Android 桥回传结果"的壳里。
     *
     * 为什么不直接用 evaluateJavascript 的 ValueCallback：
     * 那个回调对对象只给 "[object Object]" / 空串，且没法带出错信息；
     * 走桥可以把结果 JSON.stringify 后完整带回来，异常也能说清是哪一句。
     */
    fun wrapEval(id: String, source: String): String =
        "(function () {\n" +
            "  var id = " + jsString(id) + ";\n" +
            "  try {\n" +
            "    var r = eval(" + jsString(source) + ");\n" +
            "    var out;\n" +
            "    if (r === undefined) out = 'undefined';\n" +
            "    else if (typeof r === 'string') out = r;\n" +
            "    else out = JSON.stringify(r);\n" +
            "    __zhHost__.postResult(id, JSON.stringify({ ok: true, out: out }));\n" +
            "  } catch (e) {\n" +
            "    __zhHost__.postResult(id, JSON.stringify({ ok: false, error: String((e && e.message) || e) }));\n" +
            "  }\n" +
            "})();\n"
}

/**
 * JS ↔ Kotlin 的结果回传桥。
 *
 * 每次 eval 生成一个递增 id，把 CompletableDeferred 放进 map 等 JS 回调；
 * 超时后从 map 里摘掉 —— 页面里的脚本可能再也不会回来（页面被导航走了），
 * 不清就是永久泄漏。
 */
class BrowserBridge {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val counter = AtomicLong(0)

    @JavascriptInterface
    fun postResult(id: String, payload: String) {
        pending.remove(id)?.complete(payload)
    }

    /** 执行一段 JS，返回结果字符串；null = 超时或页面已跳走。必须在主线程调 evaluateJavascript。 */
    suspend fun eval(webView: android.webkit.WebView, source: String, timeoutMs: Long): String? {
        val id = counter.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        return try {
            webView.evaluateJavascript(BrowserJs.wrapEval(id, source), null)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Activity 销毁时把等待中的调用全部唤醒成失败，别让上层永远挂着 */
    fun cancelAll(reason: String) {
        val keys = pending.keys.toList()
        for (k in keys) {
            pending.remove(k)?.completeExceptionally(IllegalStateException(reason))
        }
    }
}

/**
 * 把 JS 采集回来的一份页面快照转成项目里统一的 [UiNode] 列表。
 *
 * ## 为什么单独抽成纯函数
 *
 * WebView 在 JVM 单测里根本跑不起来，但"JS 产出的 JSON → 编号列表 →
 * 给模型看的文本"这段是纯字符串处理。抽出来后单测能钉住格式和编号稳定性，
 * 不用拖一台设备或模拟器进来。
 */
object BrowserDom {

    data class Snapshot(
        val url: String,
        val title: String,
        val total: Int,
        val truncated: Boolean,
        val nodes: List<UiNode>,
    )

    /**
     * 解析 JS snapshot 的 JSON。不是对象 / 根本解析不了时返回 null ——
     * 调用方要把它当成"这一帧没读到页面"，而不是"页面有 0 个元素"。
     */
    fun parseSnapshot(json: String): Snapshot? {
        val root = runCatching { BrowserJson.parse(json) }.getOrNull() as? Map<*, *> ?: return null
        val url = root["url"] as? String ?: ""
        val title = root["title"] as? String ?: ""
        val elements = root["elements"] as? List<*> ?: emptyList<Any?>()
        val total = (root["total"] as? Number)?.toInt() ?: elements.size
        val truncated = root["truncated"] as? Boolean ?: (total > elements.size)

        val nodes = elements.mapIndexedNotNull { i, raw ->
            val m = raw as? Map<*, *> ?: return@mapIndexedNotNull null
            val tag = (m["tag"] as? String ?: "?").uppercase()
            val text = (m["text"] as? String ?: "").trim()
            val x = num(m["x"])
            val y = num(m["y"])
            // 宽高取绝对值再兜个底：个别元素 flex 布局下会报 0，直接丢掉会让
            // 编号和页面里的序号错位（viewId 就是按序号找回元素的）
            val w = num(m["w"]).coerceAtLeast(1)
            val h = num(m["h"]).coerceAtLeast(1)
            val editable = m["editable"] as? Boolean ?: false
            UiNode(
                // 编号必须从 1 开始且与 JS 返回的数组下标一一对应，所以这里用
                // mapIndexedNotNull 的 i 而不是重新数一遍存活元素
                index = i + 1,
                className = tag,
                text = text,
                contentDesc = "",
                viewId = "web:" + (i + 1),
                bounds = Rect(x, y, x + w, y + h),
                // 输入框不标"可点"，只标"可输入"，和 UiNode.describe 的风格一致；
                // 真正点击时 JS 仍然会对它 .click()，功能不受影响
                clickable = !editable,
                longClickable = false,
                scrollable = false,
                editable = editable,
                enabled = !(m["disabled"] as? Boolean ?: false),
                checked = false,
            )
        }
        return Snapshot(url, title, total, truncated || total > nodes.size, nodes)
    }

    /** 只取节点列表，给测试和"按序号找回"用 */
    fun parseTree(json: String): List<UiNode> = parseSnapshot(json)?.nodes ?: emptyList()

    /**
     * 渲染给模型看的文本。
     *
     * 第一行必须是页面上下文：模型需要知道"这是哪个网站"，否则在多个
     * 标签页/多次跳转之间会串味。元素被截断时首行要写出来 —— 静默截断
     * 会让模型以为页面上只有 60 个元素，然后反复找不到某个按钮。
     */
    fun render(snapshot: Snapshot): String = buildString {
        append("页面：").append(snapshot.url.ifBlank { "(空白页)" })
        append(" ｜ 标题：").append(snapshot.title.ifBlank { "(无标题)" })
        append(" ｜ 元素 ").append(snapshot.total).append(" 个")
        if (snapshot.truncated) append("（已截断）")
        for (node in snapshot.nodes) {
            append('\n').append(node.describe())
        }
    }

    private fun num(v: Any?): Int = ((v as? Number)?.toDouble() ?: 0.0).roundToInt()
}

/**
 * 一个极简 JSON 解析器。
 *
 * 为什么不直接用 org.json：本地单测里它是 Android 桩，方法一调就抛
 * "not mocked"，而这段解析恰恰是最该被单测钉住的部分。解析器只覆盖
 * JSON 标准类型，够解析 JS 回传的快照。
 */
internal object BrowserJson {

    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (i < s.length) {
                skipWs()
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("缺少冒号 @$i")
                i++
                map[key] = parseValue()
                skipWs()
                when {
                    i >= s.length -> break
                    s[i] == ',' -> i++
                    s[i] == '}' -> { i++; break }
                    else -> throw IllegalArgumentException("对象里出现意外字符 @$i")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (i < s.length) {
                list.add(parseValue())
                skipWs()
                when {
                    i >= s.length -> break
                    s[i] == ',' -> i++
                    s[i] == ']' -> { i++; break }
                    else -> throw IllegalArgumentException("数组里出现意外字符 @$i")
                }
            }
            return list
        }

        private fun parseString(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("期望字符串 @$i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("\\u 缺字符")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("字符串没有结束引号")
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i] == '-' || s[i] == '+' || s[i] == '.' ||
                    s[i].isDigit() || s[i] == 'e' || s[i] == 'E')) {
                i++
            }
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("不是数字 @$start")
        }

        private fun expectWord(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("期望 $word @$i")
            i += word.length
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
