package tw.cornming.vlogstitch

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 用 ML Kit GenAI 的裝置端語音辨識產生字幕。
 *
 * 兩個限制來自官方文件，不是我們能繞過的：
 *  1. 音訊必須以「即時速率」餵進去，不支援用檔案描述符全速讀。也就是說
 *     20 分鐘的影片就得辨識 20 分鐘。
 *  2. 推論只能在前景執行，App 切到背景會被擋下。
 *
 * 另外它回的是文字串流，沒有時間碼。我們自己以固定速率餵，所以可以用
 * 「已餵入的位元組數」換算出時間，誤差在一秒內，做字幕夠用。
 */
object Transcriber {

    private const val PACE_MS = 200L
    private val PACE_BYTES = AudioPcm.BYTES_PER_SEC * PACE_MS.toInt() / 1000

    data class Progress(val fedMs: Long, val totalMs: Long, val lines: Int)

    class Failed(msg: String) : Exception(msg)

    fun advancedSupported(): Boolean =
        android.os.Build.MANUFACTURER.equals("Google", true) &&
            Regex("Pixel 1[01]").containsMatchIn(android.os.Build.MODEL)

    /**
     * @param clips 依序要辨識的片段，時間軸會接續累加
     * @return 產生的字幕
     */
    suspend fun run(
        ctx: Context,
        clips: List<Uri>,
        totalMs: Long,
        locale: Locale,
        advanced: Boolean,
        log: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        cancelled: AtomicBoolean
    ): List<Subtitle> = withContext(Dispatchers.IO) {

        log("裝置 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        log("模式 ${if (advanced) "Advanced（Gemini Nano）" else "Basic"}　語言 $locale")

        val options: SpeechRecognizerOptions = speechRecognizerOptions {
            this.locale = locale
            preferredMode = if (advanced) SpeechRecognizerOptions.Mode.MODE_ADVANCED
            else SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val recognizer: SpeechRecognizer = SpeechRecognition.getClient(options)

        try {
            when (val status = recognizer.checkStatus()) {
                FeatureStatus.AVAILABLE -> log("模型已就緒")
                FeatureStatus.DOWNLOADABLE -> {
                    log("需要下載模型…")
                    var totalBytes = 0L
                    recognizer.download().collect { d ->
                        when (d) {
                            is DownloadStatus.DownloadStarted -> {
                                totalBytes = d.bytesToDownload
                                log("開始下載模型，共 ${totalBytes / 1048576} MB")
                            }
                            is DownloadStatus.DownloadCompleted -> log("模型下載完成")
                            is DownloadStatus.DownloadFailed -> throw Failed("模型下載失敗")
                            else -> {}
                        }
                    }
                }
                FeatureStatus.DOWNLOADING -> log("模型正在下載，請稍後再試")
                else -> throw Failed(
                    "這台裝置無法使用（狀態碼 $status）。" +
                        if (advanced) "Advanced 模式只支援 Pixel 10 與 Pixel 11。" else ""
                )
            }

            val pipe = ParcelFileDescriptor.createPipe()
            val fed = AtomicLong(0)
            val queue = ArrayBlockingQueue<ByteArray>(48)
            val done = ByteArray(0)

            // 解碼執行緒：邊解邊塞進佇列，滿了就等，記憶體才不會爆
            val decoder = Thread {
                try {
                    for (u in clips) {
                        if (cancelled.get()) break
                        AudioPcm.decodeTo16kMono(ctx, u, log) { chunk ->
                            if (cancelled.get()) return@decodeTo16kMono false
                            var off = 0
                            while (off < chunk.size) {
                                val n = minOf(PACE_BYTES, chunk.size - off)
                                val part = chunk.copyOfRange(off, off + n)
                                while (!queue.offer(part, 500, TimeUnit.MILLISECONDS)) {
                                    if (cancelled.get()) return@decodeTo16kMono false
                                }
                                off += n
                            }
                            true
                        }
                    }
                } catch (e: Exception) {
                    log("解碼中斷：${e.message}")
                } finally {
                    try { queue.put(done) } catch (_: Exception) {}
                }
            }.apply { isDaemon = true; start() }

            // 餵食執行緒：嚴格照即時速率寫，這是 API 的硬性要求
            val feeder = Thread {
                FileOutputStream(pipe[1].fileDescriptor).use { os ->
                    val t0 = System.nanoTime()
                    try {
                        while (true) {
                            val chunk = queue.poll(2, TimeUnit.SECONDS) ?: continue
                            if (chunk.isEmpty() || cancelled.get()) break
                            os.write(chunk)
                            val total = fed.addAndGet(chunk.size.toLong())
                            val shouldMs = total * 1000 / AudioPcm.BYTES_PER_SEC
                            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                            val wait = shouldMs - elapsedMs
                            if (wait > 0) Thread.sleep(wait)
                        }
                    } catch (e: Exception) {
                        log("餵食中斷：${e.message}")
                    }
                }
                try { pipe[1].close() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            val subs = ArrayList<Subtitle>()
            var lastEndMs = 0L
            val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(pipe[0]) }

            recognizer.startRecognition(request).collect { resp ->
                when (resp) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        onProgress(Progress(fedMs(fed), totalMs, subs.size))
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        val text = resp.text.trim()
                        val now = fedMs(fed)
                        if (text.isNotEmpty()) {
                            val start = lastEndMs
                            val end = maxOf(now, start + 600)
                            subs.add(Subtitle(start, end, text))
                            lastEndMs = end
                        }
                        onProgress(Progress(now, totalMs, subs.size))
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> log("辨識完成")
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        log("辨識錯誤 ${resp.e.errorCode}：${resp.e.message}")
                        throw Failed(resp.e.message ?: "辨識失敗")
                    }
                    else -> {}
                }
                if (cancelled.get()) throw Failed("已取消")
            }

            decoder.join(2000); feeder.join(2000)
            try { pipe[0].close() } catch (_: Exception) {}
            log("共產生 ${subs.size} 句")
            subs
        } finally {
            try { recognizer.stopRecognition() } catch (_: Exception) {}
            try { recognizer.close() } catch (_: Exception) {}
        }
    }

    private fun fedMs(fed: AtomicLong) = fed.get() * 1000 / AudioPcm.BYTES_PER_SEC
}
