package tw.cornming.vlogstitch

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用 Media3 Transformer 接片。
 *
 * 重點是「不要重新編碼」。Pixel 拍的是 10-bit HDR（BT.2020 + HLG）的 HEVC，
 * 交給編碼器重壓時，裝置上的 HEVC 編碼器可能根本初始化不了（錯誤代碼 4002）。
 * 同一支手機拍的素材規格一致，直接轉封裝就好，又快又無損。
 */
@UnstableApi
class Exporter(private val context: Context) {

    enum class Mode { AUTO, TRANSMUX_ONLY, REENCODE_SDR }

    interface Callback {
        fun onProgress(percent: Int)
        fun onSaving()
        fun onLog(line: String)
        fun onDone(outputUri: Uri?, file: File, millis: Long)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null
    private val main = Handler(Looper.getMainLooper())
    private var polling = false
    private var cancelled = false
    private var startedAt = 0L

    fun cancel() {
        cancelled = true
        polling = false
        transformer?.cancel()
        transformer = null
    }

    fun start(uris: List<Uri>, mode: Mode, cb: Callback) {
        if (uris.isEmpty()) { cb.onError("還沒有選影片"); return }
        cancelled = false
        startedAt = System.currentTimeMillis()
        cb.onLog("片段 ${uris.size} 段，模式 $mode")
        uris.forEachIndexed { i, u -> describe(u, i, cb) }

        when (mode) {
            Mode.REENCODE_SDR -> run(uris, transmux = false, cb = cb, allowFallback = false)
            Mode.TRANSMUX_ONLY -> run(uris, transmux = true, cb = cb, allowFallback = false)
            Mode.AUTO -> run(uris, transmux = true, cb = cb, allowFallback = true)
        }
    }

    /** 把每段的實際規格印出來，出問題時才知道是哪裡不一樣。 */
    private fun describe(uri: Uri, i: Int, cb: Callback) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                fun optInt(k: String) = if (f.containsKey(k)) f.getInteger(k) else -1
                if (mime.startsWith("video/")) {
                    val transfer = optInt(MediaFormat.KEY_COLOR_TRANSFER)
                    cb.onLog(
                        "[${i + 1}] 影像 $mime ${optInt(MediaFormat.KEY_WIDTH)}x${optInt(MediaFormat.KEY_HEIGHT)}" +
                            " ${optInt(MediaFormat.KEY_FRAME_RATE)}fps 旋轉${optInt("rotation-degrees")}deg " +
                            hdrLabel(transfer) + " (transfer=" + transfer +
                            " standard=" + optInt(MediaFormat.KEY_COLOR_STANDARD) + ")"
                    )
                } else if (mime.startsWith("audio/")) {
                    cb.onLog(
                        "[${i + 1}] 聲音 $mime ${optInt(MediaFormat.KEY_SAMPLE_RATE)}Hz " +
                            "${optInt(MediaFormat.KEY_CHANNEL_COUNT)}ch"
                    )
                }
            }
        } catch (e: Exception) {
            cb.onLog("[${i + 1}] 讀不到格式：${e.message}")
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
    }

    private fun hdrLabel(transfer: Int) = when (transfer) {
        MediaFormat.COLOR_TRANSFER_HLG -> "HDR/HLG"
        MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR/PQ"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
        else -> "color-unknown"
    }

    private fun run(uris: List<Uri>, transmux: Boolean, cb: Callback, allowFallback: Boolean) {
        if (cancelled) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "vlog-$stamp.mp4")

        val items = uris.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val sequence = EditedMediaItemSequence.Builder(items).build()

        val cb2 = Composition.Builder(sequence)
        if (transmux) {
            // 完全不碰編碼器：把原始編碼資料重新封裝進一個 mp4
            cb2.setTransmuxVideo(true)
            cb2.setTransmuxAudio(true)
        } else {
            // 真要重編時先把 HDR 轉成 SDR，否則編碼器多半起不來
            cb2.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        val composition = cb2.build()

        cb.onLog(if (transmux) "-> 嘗試轉封裝（不重新編碼）" else "-> 重新編碼，HDR 轉 SDR，輸出 H.264")

        val builder = Transformer.Builder(context)
        if (!transmux) {
            builder.setVideoMimeType(MimeTypes.VIDEO_H264)
            builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        }

        val t = builder.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                polling = false
                cb.onLog("完成：長度 ${result.durationMs}ms，大小 ${result.fileSizeBytes} bytes")
                cb.onLog("影像編碼器 " + (result.videoEncoderName ?: "無（轉封裝）") +
                    "，聲音編碼器 " + (result.audioEncoderName ?: "無（轉封裝）"))
                // 成品可能好幾 GB，複製到相簿一定要離開主執行緒，否則會 ANR
                cb.onSaving()
                Thread {
                    val uri = saveToGallery(outFile, "vlog-$stamp.mp4", cb)
                    val took = System.currentTimeMillis() - startedAt
                    main.post { cb.onDone(uri, outFile, took) }
                }.start()
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException
            ) {
                polling = false
                cb.onLog("錯誤代碼 ${exception.errorCode}：${exception.message}")
                outFile.delete()
                if (allowFallback && transmux && !cancelled) {
                    cb.onLog("轉封裝不行，改用重新編碼")
                    main.post { run(uris, transmux = false, cb = cb, allowFallback = false) }
                } else {
                    cb.onError(exception.message ?: "匯出失敗")
                }
            }
        }).build()

        transformer = t
        try {
            t.start(composition, outFile.absolutePath)
        } catch (e: Exception) {
            cb.onLog("啟動失敗：${e.message}")
            if (allowFallback && transmux) {
                main.post { run(uris, transmux = false, cb = cb, allowFallback = false) }
            } else {
                cb.onError(e.message ?: "無法啟動匯出")
            }
            return
        }
        pollProgress(cb)
    }

    private fun pollProgress(cb: Callback) {
        polling = true
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (!polling) return
                val t = transformer ?: return
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    cb.onProgress(holder.progress)
                }
                main.postDelayed(this, 400)
            }
        }
        main.postDelayed(tick, 400)
    }

    /** 先寫到 cacheDir，再搬進相簿的 Movies，這樣不需要儲存權限。 */
    private fun saveToGallery(file: File, name: String, cb: Callback): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/vlog-stitch")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            cb.onLog("寫不進相簿，檔案留在 App 快取"); null
        } else {
            resolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            cb.onLog("已存到 相簿 / Movies / vlog-stitch")
            uri
        }
    } catch (e: Exception) {
        cb.onLog("存到相簿失敗：${e.message}")
        null
    }
}
