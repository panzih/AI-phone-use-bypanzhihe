package com.aiphone.assistant.a11y

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * 近黑帧判定（ROADMAP §0）：FLAG_SECURE 页面给全黑图不报错，
 * 必须按"截不到"处理，不能把黑图当"空屏"发给模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlackFrameTest {

    private fun pngOf(draw: (Bitmap) -> Unit): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        draw(bmp)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun `全黑图判定为近黑帧`() {
        // FLAG_SECURE 的典型现场：均匀的 0，一个亮点都没有
        val bytes = pngOf { bmp -> bmp.eraseColor(android.graphics.Color.BLACK) }
        assertTrue(BlackFrame.isMostlyBlack(bytes))
    }

    @Test
    fun `深色页面有一点内容就不算近黑`() {
        // 夜间模式的界面：底色很暗，但有状态栏图标/文字这些亮点
        val bytes = pngOf { bmp ->
            bmp.eraseColor(android.graphics.Color.rgb(10, 10, 12))
            bmp.setPixel(5, 5, android.graphics.Color.WHITE)
            bmp.setPixel(40, 30, android.graphics.Color.WHITE)
        }
        assertFalse(BlackFrame.isMostlyBlack(bytes))
    }

    @Test
    fun `正常浅色页面不算近黑`() {
        val bytes = pngOf { bmp -> bmp.eraseColor(android.graphics.Color.WHITE) }
        assertFalse(BlackFrame.isMostlyBlack(bytes))
    }

    @Test
    fun `空数据按不是黑帧处理`() {
        // 解码不出来另有报错路径；这里误判会把真图当黑图丢掉。
        // （Robolectric 的 shadow Bitmap 对任何字节都解得出图，
        //  "真解出 null" 这条只能在真机上验证，单测只钉空数据的兜底）
        assertFalse(BlackFrame.isMostlyBlack(ByteArray(0)))
    }
}
