package io.github.artemimennoi.awgbar

import java.nio.ByteBuffer
import java.util.Base64

/**
 * Collects QR codes as AmneziaVPN shows them. A long config comes as a series: each code is
 * base64url of a QDataStream with magic 1984 (int16), the chunk count and index (uint8 each) and
 * a QByteArray (uint32 length + bytes). Any other code is a whole payload on its own.
 */
class QrSeries {
    private val chunks = mutableMapOf<Int, ByteArray>()
    var total = 0
        private set
    val received get() = chunks.size

    /** Returns the complete payload once every chunk (or a single plain code) has been seen. */
    fun add(text: String): ByteArray? {
        val chunk = decodeChunk(text) ?: return text.toByteArray()
        val (count, index, data) = chunk
        if (count != total) { chunks.clear(); total = count }
        chunks[index] = data
        if (chunks.size < total) return null
        return (0 until total).map { chunks[it] ?: return null }.reduce { a, b -> a + b }
    }

    companion object {
        private const val MAGIC: Short = 1984

        fun decodeChunk(text: String): Triple<Int, Int, ByteArray>? = runCatching {
            val buf = ByteBuffer.wrap(Base64.getUrlDecoder().decode(text.trim().trimEnd('=')))
            if (buf.short != MAGIC) return null
            val count = buf.get().toInt() and 0xff
            val index = buf.get().toInt() and 0xff
            val len = buf.int
            if (count == 0 || index >= count || len < 0 || len > buf.remaining()) return null
            Triple(count, index, ByteArray(len).also { buf.get(it) })
        }.getOrNull()
    }
}
