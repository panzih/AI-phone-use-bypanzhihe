package com.aiphone.assistant.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 重发策略：什么时候该重发、什么时候不该、重发几次、能不能被叫停。
 *
 * 这几条一起构成"模型卡住了怎么办"的完整答案，改动它们会直接影响
 * 用户的钱（重发 = 可能重复计费）和体感（干等 vs 自己恢复），
 * 所以钉在这里。
 *
 * 用真的 `ServerSocket` 手写最小 HTTP，而不是 mock 掉网络层 ——
 * 要验的恰恰是 `HttpURLConnection` 的超时、断开、重连这些**真实行为**，
 * mock 掉就等于什么都没验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmClientRetryTest {

    private val server = FakeServer()

    private companion object {
        /** 一份能被 parseResponse 认下的最小响应 —— 少了 choices 会被判成"空响应" */
        const val OK_BODY =
            """{"choices":[{"message":{"content":"AI 决定打开设置"}}],""" +
                """"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(
        maxAttempts: Int = 3,
        timeoutMs: Int = 30_000,
        scheme: String = "http",
    ) = LlmClient(
        LlmConfig(
            baseUrl = "$scheme://127.0.0.1:${server.port}",
            apiKey = "test-key",
            model = "test-model",
            maxAttempts = maxAttempts,
            timeoutMs = timeoutMs,
        )
    )

    private fun chat(c: LlmClient, onRetry: ((String) -> Unit)? = null, abort: (() -> Boolean)? = null) =
        c.chat("你是一个助手", listOf(ChatTurn(ChatTurn.USER, "打开设置")), null, { _, _, why, _ ->
            onRetry?.invoke(why)
        }, abort)

    // ---- 该重发的：5xx ----

    @Test
    fun `服务端503_重发后成功`() {
        // 前两次 503，第三次给正常响应 —— 这是最典型的"服务端抽了一下"
        server.plan(Reply.fail(503), Reply.fail(503), Reply.ok(OK_BODY))
        val retries = CopyOnWriteArrayList<String>()

        val r = chat(client(), onRetry = { retries.add(it) })

        assertTrue("应当成功，实际：$r", r is LlmResult.Ok)
        assertEquals("做了三次尝试", 3, server.served.get())
        assertEquals("通知了两次重发", 2, retries.size)
        assertTrue("原因里要带状态码，实际：${retries[0]}", retries[0].contains("503"))
    }

    // ---- 不该重发的：4xx ----

    @Test
    fun `鉴权失败401_一次就放弃`() {
        // 4xx 是我们自己的请求有问题（Key 错、模型名错），
        // 重发一百次结果一样，只会白烧时间和额度
        server.plan(Reply.fail(401), Reply.fail(401), Reply.fail(401), Reply.fail(401))

        val r = chat(client(), onRetry = { error("4xx 不该触发重发，但回调了：$it") })

        assertTrue("应当是失败，实际：$r", r is LlmResult.Fail)
        assertEquals("只发一次", 1, server.served.get())
        assertTrue("要提示是 Key 的问题", (r as LlmResult.Fail).message.contains("API Key"))
    }

    @Test
    fun `HTTPS握手失败_直接报告证书错误且不重发`() {
        // 服务端说普通 HTTP，客户端却尝试 TLS：真实触发 SSLException。
        // 重发同一条请求无法修复协议或证书配置错误。
        server.plan(Reply.badTls(), Reply.badTls())
        val retries = CopyOnWriteArrayList<String>()

        val r = chat(client(scheme = "https"), onRetry = { retries.add(it) })

        assertTrue("应当报告失败，实际：$r", r is LlmResult.Fail)
        assertTrue("应当指出 HTTPS 握手问题，实际：$r", (r as LlmResult.Fail).message.contains("HTTPS 握手失败"))
        assertEquals("握手失败不应重发", 1, server.served.get())
        assertTrue("握手失败不应通知重发", retries.isEmpty())
    }

    // ---- 该重发的：超时（服务端收下请求但不吐字）----

    @Test
    fun `服务端不响应_超时后重发`() {
        // 卡死最常见的样子：连接建上了、请求收下了，然后没有然后了
        server.plan(Reply.hang(), Reply.hang(), Reply.hang(), Reply.hang())

        val r = chat(client(maxAttempts = 2, timeoutMs = 1_500))

        assertTrue("应当是失败，实际：$r", r is LlmResult.Fail)
        assertEquals("试满两次", 2, server.served.get())
        val msg = (r as LlmResult.Fail).message
        assertTrue("原因里要出现「超时」，实际：$msg", msg.contains("超时"))
        assertTrue("要说明试了几次，实际：$msg", msg.contains("2 次"))
    }

    // ---- 不该重发的：外部已经叫停 ----

    @Test
    fun `已按急停_不再重发`() {
        server.plan(Reply.fail(503), Reply.fail(503), Reply.fail(503))

        val r = chat(client(), onRetry = { error("已叫停还去重发") }, abort = { true })

        assertTrue(r is LlmResult.Fail)
        assertEquals("一次都不该发", 0, server.served.get())
    }

    // ---- 中断：把卡住的请求掀掉 ----

    @Test
    fun `abort_能打断卡住的请求`() {
        // readTimeout 给到 60s，要是没有 abort 这条得等一分钟才能返回；
        // 这里要求它在 5 秒内回来 —— 打断生效了才会这么快
        server.plan(Reply.hang(), Reply.hang())
        val c = client(maxAttempts = 5, timeoutMs = 60_000)

        val done = CountDownLatch(1)
        var result: LlmResult? = null
        Thread {
            result = chat(c)
            done.countDown()
        }.start()

        // 等请求真的发出去、卡在等响应上了，再叫停
        assertTrue("请求没发出去", server.firstServed.await(5, TimeUnit.SECONDS))
        c.abort()

        assertTrue("abort 之后应当很快返回，实际等了 5 秒还没回", done.await(5, TimeUnit.SECONDS))
        assertTrue("应当是失败，实际：$result", result is LlmResult.Fail)
        assertEquals("abort 之后不该再重发", 1, server.served.get())
    }

    // ------------------------------------------------------------------
    // 最小 HTTP 服务端：够用就行，不引第三方
    // ------------------------------------------------------------------

    private sealed class Reply {
        class Ok(val body: String) : Reply()
        class Fail(val code: Int) : Reply()
        /** 收下请求但一直不回，用来构造"卡住" */
        class Hang : Reply()
        /** 用普通 HTTP 回应 TLS ClientHello，模拟地址协议配置错误 */
        class BadTls : Reply()

        companion object {
            fun ok(body: String) = Ok(body)
            fun fail(code: Int) = Fail(code)
            fun hang() = Hang()
            fun badTls() = BadTls()
        }
    }

    private class FakeServer {
        private val socket = ServerSocket(0)
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        private val plan = CopyOnWriteArrayList<Reply>()

        /** 服务端一共处理了几个请求（重发会让它涨） */
        val served = AtomicInteger(0)

        /** 第一个请求到达的信号，给中断测试用 */
        val firstServed = CountDownLatch(1)

        val port: Int get() = socket.localPort

        fun plan(vararg replies: Reply) {
            plan.clear()
            plan.addAll(replies)
        }

        init {
            pool.execute {
                while (!socket.isClosed) {
                    val conn = try {
                        socket.accept()
                    } catch (t: Throwable) {
                        return@execute
                    }
                    pool.execute { handle(conn) }
                }
            }
        }

        private fun handle(conn: Socket) {
            try {
                val n = served.getAndIncrement()
                firstServed.countDown()
                val reply = plan.getOrNull(n) ?: Reply.fail(500)
                if (reply !is Reply.BadTls) drainRequest(conn.getInputStream())
                when (val r = reply) {
                    is Reply.Ok -> respond(conn, 200, r.body)
                    is Reply.Fail -> respond(conn, r.code, """{"error":{"message":"stub"}}""")
                    // 挂住：什么都不写，也不关 —— 让客户端一直等
                    is Reply.Hang -> Thread.sleep(120_000)
                    is Reply.BadTls -> {
                        conn.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                        conn.getOutputStream().flush()
                    }
                }
            } catch (t: Throwable) {
                // 客户端断开（abort/超时）会走到这里，正常
            } finally {
                runCatching { conn.close() }
            }
        }

        /** 读干净请求（含 body），否则客户端会以为没发完 */
        private fun drainRequest(ins: InputStream) {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = ins.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val len = Regex("(?i)content-length:\\s*(\\d+)")
                .find(head)?.groupValues?.get(1)?.toInt() ?: 0
            var left = len
            val buf = ByteArray(8192)
            while (left > 0) {
                val got = ins.read(buf, 0, minOf(left, buf.size))
                if (got < 0) return
                left -= got
            }
        }

        private fun respond(conn: Socket, code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out: OutputStream = conn.getOutputStream()
            // 明说 close：每条请求一条新连接，服务端的"第几个请求"才好数
            out.write(
                (
                    "HTTP/1.1 $code X\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.write(bytes)
            out.flush()
        }

        fun close() {
            runCatching { socket.close() }
            pool.shutdownNow()
        }
    }
}
