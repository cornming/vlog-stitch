package tw.cornming.vlogstitch

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
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
 * 用 Media3 Transformer 把多段影片接成一支。
 *
 * Transformer 底層走 MediaCodec，用的是系統自己的硬體編解碼器，
 * 跟瀏覽器版卡住的 WebCodecs 不是同一條路；規格一致時它會自動只做
 * 轉封裝而不重新編碼。
 */
@UnstableApi
class Exporter(private val context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onLog(line: String)
        fun onDone(outputUri: Uri?, file: File, millis: Long)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null
    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    fun cancel() {
        polling = false
        transformer?.cancel()
        transformer = null
    }

    fun start(uris: List<Uri>, cb: Callback) {
        if (uris.isEmpty()) {
            cb.onError("還沒有選影片")
            return
        }
        val started = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "vlog-$stamp.mp4")

        cb.onLog("片段 ${uris.size} 段")
        val items = uris.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val sequence = EditedMediaItemSequence.Builder(items).build()
        val composition = Composition.Builder(sequence).build()

        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    polling = false
                    cb.onLog("Transformer 完成，長度 ${result.durationMs}ms，大小 ${result.fileSizeBytes} bytes")
                    cb.onLog("影像編碼 ${result.videoEncoderName ?: "未重新編碼"}，聲音編碼 ${result.audioEncoderName ?: "未重新編碼"}")
                    val uri = saveToGallery(outFile, "vlog-$stamp.mp4", cb)
                    cb.onDone(uri, outFile, System.currentTimeMillis() - started)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    polling = false
                    cb.onLog("錯誤代碼 ${exception.errorCode}")
                    cb.onError(exception.message ?: "匯出失敗")
                }
            })
            .build()

        transformer = t
        t.start(composition, outFile.absolutePath)
        pollProgress(cb)
    }

    private fun pollProgress(cb: Callback) {
        polling = true
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (!polling) return
                val t = transformer ?: return
                val state = t.getProgress(holder)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    cb.onProgress(holder.progress)
                }
                main.postDelayed(this, 400)
            }
        }
        main.postDelayed(tick, 400)
    }

    /** 匯出到 cacheDir 之後再搬進相簿的 Movies，這樣不需要儲存權限。 */
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
            cb.onLog("寫不進相簿，檔案留在 App 快取")
            null
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
