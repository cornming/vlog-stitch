package tw.cornming.vlogstitch

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把影片的聲音解出來，轉成 ML Kit 要的格式：
 * 無檔頭的 16-bit PCM、單聲道、16 kHz。
 *
 * 邊解邊吐，不整段存在記憶體裡——43 分鐘的素材光 PCM 就有 80 MB 以上。
 */
object AudioPcm {

    const val RATE = 16000
    const val BYTES_PER_SEC = RATE * 2

    /** 逐塊回呼；回傳 false 代表要中止。 */
    fun decodeTo16kMono(
        ctx: Context,
        uri: Uri,
        log: (String) -> Unit,
        onChunk: (ByteArray) -> Boolean
    ) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) { log("這一段沒有聲音軌"); return }
            ex.selectTrack(track)

            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            log("解碼聲音 ${format.getString(MediaFormat.KEY_MIME)} ${srcRate}Hz ${srcCh}ch → ${RATE}Hz 1ch")

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val resampler = Resampler(srcRate, RATE)
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val mono = toMono(out, srcCh)
                            val res = resampler.process(mono)
                            if (res.isNotEmpty() && !onChunk(toBytes(res))) {
                                codec.releaseOutputBuffer(outIdx, false)
                                return
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (sawInputEnd) { /* 等 */ }
                }
            }
        } catch (e: Exception) {
            log("聲音解碼失敗：${e.javaClass.simpleName} ${e.message}")
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    /** 多聲道取平均降成單聲道 */
    private fun toMono(buf: ByteBuffer, channels: Int): ShortArray {
        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val total = sb.remaining()
        if (channels <= 1) {
            val out = ShortArray(total); sb.get(out); return out
        }
        val frames = total / channels
        val out = ShortArray(frames)
        val tmp = ShortArray(total); sb.get(tmp)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += tmp[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    private fun toBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            b[i * 2] = (s[i].toInt() and 0xFF).toByte()
            b[i * 2 + 1] = ((s[i].toInt() shr 8) and 0xFF).toByte()
        }
        return b
    }

    /** 線性內插重取樣。跨塊要保留小數位置與上一個取樣，否則接縫會有雜訊。 */
    private class Resampler(val from: Int, val to: Int) {
        private val step = from.toDouble() / to
        private var pos = 0.0
        private var prev: Short = 0
        private var hasPrev = false

        fun process(input: ShortArray): ShortArray {
            if (input.isEmpty()) return ShortArray(0)
            if (from == to) return input
            val src = if (hasPrev) ShortArray(input.size + 1).also {
                it[0] = prev; System.arraycopy(input, 0, it, 1, input.size)
            } else input
            val out = ArrayList<Short>((src.size / step).toInt() + 2)
            var p = pos
            while (p < src.size - 1) {
                val i = p.toInt()
                val f = p - i
                out.add((src[i] * (1 - f) + src[i + 1] * f).toInt().toShort())
                p += step
            }
            pos = p - (src.size - 1)
            prev = src[src.size - 1]
            hasPrev = true
            return out.toShortArray()
        }
    }
}
