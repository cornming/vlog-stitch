package tw.cornming.vlogstitch

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * 把 mp4 裡的聲音軌「原封不動」抄成一個 m4a。
 *
 * 完全不解碼、不重取樣——雲端服務吃得下壓縮音訊，所以先前那套自己寫的
 * PCM 管線在這條路上完全用不到，也就不會再出錯。
 * 43 分鐘的 AAC 大約 40 MB，遠低於服務端的檔案上限。
 */
object AudioTrack {

    fun extract(ctx: Context, clips: List<Uri>, out: File, log: (String) -> Unit): File {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var outTrack = -1
        var started = false
        var offsetUs = 0L
        val buf = ByteBuffer.allocate(1 shl 20)

        try {
            for ((i, uri) in clips.withIndex()) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(ctx, uri, null)
                    var track = -1
                    var fmt: MediaFormat? = null
                    for (t in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(t)
                        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                            track = t; fmt = f; break
                        }
                    }
                    if (track < 0 || fmt == null) { log("[${i + 1}] 沒有聲音軌，略過"); continue }
                    if (!started) {
                        outTrack = muxer.addTrack(fmt)
                        muxer.start()
                        started = true
                        log("聲音格式 ${fmt.getString(MediaFormat.KEY_MIME)} " +
                            "${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz " +
                            "${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
                    }
                    ex.selectTrack(track)
                    val info = android.media.MediaCodec.BufferInfo()
                    var last = 0L
                    var n = 0
                    while (true) {
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = offsetUs + ex.sampleTime
                        info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(outTrack, buf, info)
                        last = ex.sampleTime
                        n++
                        ex.advance()
                    }
                    offsetUs += last + 20_000  // 補一格的長度，避免下一段時間戳重疊
                    log("[${i + 1}] 抄了 $n 個音訊封包，累計 ${offsetUs / 1000} ms")
                } finally {
                    try { ex.release() } catch (_: Exception) {}
                }
            }
        } finally {
            try { if (started) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
        log("音軌檔 ${out.length() / 1024} KB")
        return out
    }
}
