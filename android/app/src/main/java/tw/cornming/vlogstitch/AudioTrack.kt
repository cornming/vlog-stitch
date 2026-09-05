package tw.cornming.vlogstitch

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * 把 mp4 的聲音軌原封不動抄出來，不解碼、不重取樣。
 *
 * 會切成多個小檔：mp4 的索引（moov）寫在檔尾，一次傳一個大檔時只要上傳被
 * 截斷，整個檔案就解不開；分段之後單檔小、失敗範圍也小，還能顯示進度。
 */
object AudioTrack {

    data class Segment(val file: File, val startMs: Long, val durationMs: Long)

    /**
     * @param segmentMs 每段的目標長度，實際會在取樣邊界切
     */
    fun extractSegments(
        ctx: Context,
        clips: List<Uri>,
        dir: File,
        segmentMs: Long,
        log: (String) -> Unit
    ): List<Segment> {
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name.startsWith("seg-")) it.delete() }

        val segments = ArrayList<Segment>()
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()

        var muxer: MediaMuxer? = null
        var outTrack = -1
        var segFile: File? = null
        var segStartMs = 0L          // 這一段在整體時間軸上的起點
        var segFirstUs = -1L         // 這一段第一個取樣的時間，用來歸零
        var segLastUs = 0L
        var globalMs = 0L            // 已經處理掉的總長度
        var fmt: MediaFormat? = null

        fun closeSegment() {
            val m = muxer ?: return
            try {
                m.stop()
            } catch (e: Exception) {
                // 這裡失敗代表 moov 沒寫進去，檔案是壞的，一定要講出來
                log("!! 收尾失敗，這一段可能不完整：${e.message}")
            } finally {
                try { m.release() } catch (_: Exception) {}
            }
            val f = segFile
            if (f != null && f.length() > 1024) {
                val dur = if (segFirstUs >= 0) (segLastUs - segFirstUs) / 1000 else 0L
                segments.add(Segment(f, segStartMs, dur))
                log("段 ${segments.size}：${f.length() / 1024} KB，" +
                    "${Media.fmtDuration(segStartMs)} 起，長 ${Media.fmtDuration(dur)}")
            }
            muxer = null; segFile = null; outTrack = -1; segFirstUs = -1; segLastUs = 0
        }

        fun openSegment(format: MediaFormat, startMs: Long) {
            val f = File(dir, "seg-%03d.m4a".format(segments.size + 1))
            val m = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            outTrack = m.addTrack(format)
            m.start()
            muxer = m; segFile = f; segStartMs = startMs; segFirstUs = -1; segLastUs = 0
        }

        try {
            for ((ci, uri) in clips.withIndex()) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(ctx, uri, null)
                    var track = -1
                    for (t in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(t)
                        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                            track = t; fmt = f; break
                        }
                    }
                    val format = fmt
                    if (track < 0 || format == null) { log("[${ci + 1}] 沒有聲音軌，略過"); continue }
                    if (ci == 0) log("聲音格式 ${format.getString(MediaFormat.KEY_MIME)} " +
                        "${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz " +
                        "${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
                    ex.selectTrack(track)

                    while (true) {
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val us = ex.sampleTime
                        if (muxer == null) openSegment(format, globalMs)
                        if (segFirstUs < 0) segFirstUs = us
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = us - segFirstUs      // 每段自己從 0 開始
                        info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer!!.writeSampleData(outTrack, buf, info)
                        segLastUs = us
                        ex.advance()

                        val segMs = (segLastUs - segFirstUs) / 1000
                        if (segMs >= segmentMs) {
                            globalMs += segMs
                            closeSegment()
                        }
                    }
                    if (muxer != null) {
                        globalMs += (segLastUs - segFirstUs) / 1000
                        closeSegment()
                    }
                } finally {
                    try { ex.release() } catch (_: Exception) {}
                }
            }
        } finally {
            if (muxer != null) closeSegment()
        }

        log("共 ${segments.size} 段，總長 ${Media.fmtDuration(globalMs)}")
        return segments
    }

    /** 抄完之後回頭讀一次，確認檔案真的能解析。壞掉的檔在這裡就會現形。 */
    fun verify(ctx: Context, f: File, log: (String) -> Unit): Boolean = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            var ok = false
            for (t in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(t)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION))
                        fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else -1
                    log("  驗證 ${f.name}：可解析，長 ${dur} ms")
                    ok = true
                }
            }
            if (!ok) log("  驗證 ${f.name}：找不到聲音軌")
            ok
        } finally { try { ex.release() } catch (_: Exception) {} }
    } catch (t: Throwable) {
        log("  驗證 ${f.name} 失敗：${t.javaClass.simpleName} ${t.message ?: ""}")
        false
    }
}
