package com.aiphone.assistant.log

/** 截图来源可能给 JPEG 或 PNG；落盘文件名必须与实际字节格式一致。 */
internal object ImageFormat {
    fun extension(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "png"
        else -> "bin"
    }
}
