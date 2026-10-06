package com.aiphone.assistant.data

import android.content.Context
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.log.AppLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 历史里的截图**跨任务要能原样回来**。
 *
 * 为什么值得单独钉：缓存前缀单元需要完整匹配，而历史里只要有一条消息
 * 带图、下一次任务读回来时图没了，相关单元就无法命中 —— 之后的消息整段
 * 按未命中价重算。实测过一次：上一轮用过图之后，下一次任务的 24 条历史
 * 里只有 5 条能复用。
 *
 * 这一组用例守住三件事：
 *   1. 字节必须**一字不差**地往返（重新编码一次都不行）；
 *   2. 图片标识由内容决定，跨实例稳定（指纹靠它，不靠对象的身份码）；
 *   3. 文件丢失时降级为纯文本，而不是让整段历史载入失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextStoreHistoryImageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ContextStore.clear(context)
    }

    /** 假的"截图"。这里不关心它是不是合法 JPEG，只关心字节能不能原样回来。 */
    private fun fakeImage(seed: Int, size: Int = 4096): ByteArray =
        ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun imageFiles(): List<File> =
        File(AppLog.rootDir(context), "images").listFiles()?.toList() ?: emptyList()

    private fun history(vararg turns: ChatTurn) =
        turns.toList()

    @Test
    fun `带图历史往返后字节一字不差`() {
        val img = fakeImage(1)
        val saved = history(
            ChatTurn(ChatTurn.USER, "把设置里的字体调大"),
            ChatTurn(ChatTurn.ASSISTANT, "打开设置"),
            ChatTurn(ChatTurn.USER, "界面元素：…", img),
        )

        ContextStore.save(context, emptyList(), emptyList(), 1L, history = saved)
        val loaded = ContextStore.load(context)!!.history

        assertEquals(3, loaded.size)
        assertArrayEquals(img, loaded[2].imageBytes)
        // 文本也要一起留住 —— 图丢了还能退回纯文本，文本丢了就没救了
        assertEquals("界面元素：…", loaded[2].text)
    }

    @Test
    fun `图片标识由内容决定，跨实例稳定`() {
        // 这是"跨任务前缀不断"的根：从磁盘读回来的是**新数组**，
        // System.identityHashCode 必然不同，只有内容标识不变
        assertEquals(ChatTurn.imageIdOf(fakeImage(1)), ChatTurn.imageIdOf(fakeImage(1)))
        assertNotEquals(ChatTurn.imageIdOf(fakeImage(1)), ChatTurn.imageIdOf(fakeImage(2)))
    }

    @Test
    fun `载入后重新构造的 ChatTurn 与原来的标识一致`() {
        val img = fakeImage(7)
        val original = ChatTurn(ChatTurn.USER, "看一眼这屏", img)
        ContextStore.save(context, emptyList(), emptyList(), 1L, history = history(original))

        val reloaded = ContextStore.load(context)!!.history.single()

        // 不比对字节，比对**标识**：指纹就是靠它判断"还是不是同一张图"
        assertEquals(original.imageId, reloaded.imageId)
    }

    @Test
    fun `图片文件丢失时降级为纯文本，不丢消息也不抛异常`() {
        val img = fakeImage(3)
        ContextStore.save(
            context, emptyList(), emptyList(), 1L,
            history = history(ChatTurn(ChatTurn.USER, "界面元素：…", img)),
        )

        // 模拟图被清理掉/写盘失败
        imageFiles().forEach { it.delete() }

        val loaded = ContextStore.load(context)!!.history.single()
        assertNull(loaded.imageBytes)
        assertEquals("界面元素：…", loaded.text)
    }

    @Test
    fun `同一张图被多条消息引用时只存一份`() {
        val img = fakeImage(9)
        ContextStore.save(
            context, emptyList(), emptyList(), 1L,
            history = history(
                ChatTurn(ChatTurn.USER, "第一屏", img),
                ChatTurn(ChatTurn.USER, "第二屏", img),
            ),
        )

        assertEquals(1, imageFiles().size)
        val loaded = ContextStore.load(context)!!.history
        assertArrayEquals(img, loaded[0].imageBytes)
        assertArrayEquals(img, loaded[1].imageBytes)
    }

    @Test
    fun `不再被引用的图会被清掉，还在用的留着`() {
        val old = fakeImage(11)
        ContextStore.save(
            context, emptyList(), emptyList(), 1L,
            history = history(ChatTurn(ChatTurn.USER, "旧的", old)),
        )
        assertEquals(1, imageFiles().size)

        // 换一段不含旧图的历史（等价于裁剪掉了引用它的那条消息）
        val fresh = fakeImage(12)
        ContextStore.save(
            context, emptyList(), emptyList(), 2L,
            history = history(ChatTurn(ChatTurn.USER, "新的", fresh)),
        )

        val names = imageFiles().map { it.name }
        assertEquals(1, names.size)
        assertEquals(ChatTurn.imageIdOf(fresh), names.single())
    }

    @Test
    fun `清空上下文会连图片一起清掉`() {
        ContextStore.save(
            context, emptyList(), emptyList(), 1L,
            history = history(ChatTurn(ChatTurn.USER, "看一眼", fakeImage(13))),
        )
        assertEquals(1, imageFiles().size)

        ContextStore.clear(context)

        assertEquals(0, imageFiles().size)
        assertNull(ContextStore.load(context))
    }
}
