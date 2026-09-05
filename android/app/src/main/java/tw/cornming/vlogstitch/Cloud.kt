package tw.cornming.vlogstitch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Azure AI Speech 的 Fast transcription。
 *
 * 選它而不是傳統批次轉錄，是因為批次要求音訊放在 Blob Storage 並提供 URL，
 * 手機端還得多開一個儲存體帳戶；fast transcription 直接 multipart 上傳、
 * 同步回結果，一次 POST 就完成，而且一樣遠快於即時。
 */
object Cloud {

    private const val API_VERSION = "2025-10-15"
    private const val PREFS = "vlog-stitch"

    data class Config(
        val endpoint: String, val key: String, val locale: String,
        /** true 送 WAV（相容性最好），false 送原始 AAC 音軌（檔案小但服務端可能不收） */
        val useWav: Boolean = true
    )

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            p.getString("az_endpoint", "").orEmpty(),
            p.getString("az_key", "").orEmpty(),
            // 預設用台灣繁體國語。Fast transcription 的官方清單只列到 zh-CN，
            // 但批次轉錄支援 zh-TW，實際能不能用以服務回報為準，不能用會退回 zh-CN。
            p.getString("az_locale", "zh-TW").orEmpty(),
            p.getBoolean("az_wav", true)
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("az_endpoint", c.endpoint.trim())
            .putString("az_key", c.key.trim())
            .putString("az_locale", c.locale.trim())
            .putBoolean("az_wav", c.useWav)
            .apply()
    }

    /** portal 給的可能是完整網址或只有區域名稱，兩種都接受 */
    private fun host(endpoint: String): String {
        var e = endpoint.trim().removeSuffix("/")
        if (e.isEmpty()) throw IllegalArgumentException("尚未設定端點")
        if (!e.startsWith("http")) {
            e = if (e.contains(".")) "https://$e"
            else "https://$e.api.cognitive.microsoft.com"
        }
        return URL(e).let { "${it.protocol}://${it.host}" }
    }

    /** 文件的語言清單可能落後，直接跟服務要一份最新的 */
    suspend fun listLocales(cfg: Config, log: (String) -> Unit): List<String> =
        withContext(Dispatchers.IO) {
            val url = "${host(cfg.endpoint)}/speechtotext/transcriptions:listSupportedLocales" +
                "?api-version=$API_VERSION"
            log("查詢支援語言…")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Ocp-Apim-Subscription-Key", cfg.key)
                connectTimeout = 15000; readTimeout = 20000
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) { log("HTTP $code：${body.take(200)}"); return@withContext emptyList() }
                val arr = try { JSONArray(body) } catch (e: Exception) {
                    JSONObject(body).optJSONArray("values") ?: JSONArray()
                }
                (0 until arr.length()).map { arr.getString(it) }
            } finally { conn.disconnect() }
        }

    /** 上傳音訊，直接拿回帶時間碼的結果 */
    suspend fun transcribe(
        cfg: Config,
        audio: File,
        onRaw: (String) -> Unit = {},
        log: (String) -> Unit
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val url = "${host(cfg.endpoint)}/speechtotext/transcriptions:transcribe?api-version=$API_VERSION"
        val boundary = "----vlogstitch${System.currentTimeMillis()}"
        val definition = JSONObject()
            .put("locales", JSONArray().put(cfg.locale))
            .toString()

        // 先把 multipart 的前後段算出來，才能用固定長度上傳。
        // 用 chunked 傳輸時伺服器不知道預期大小，上傳被截斷也不會發現，
        // 而 mp4 的索引寫在檔尾，少一點點就整個解不開。
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"definition\"\r\n\r\n" +
            definition + "\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"audio\"; filename=\"${audio.name}\"\r\n" +
            "Content-Type: ${if (audio.name.endsWith(".wav")) "audio/wav" else "audio/mp4"}\r\n\r\n")
            .toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val total = head.size + audio.length() + tail.size

        log("上傳 ${audio.length() / 1024} KB（含表單共 ${total / 1024} KB）")
        log("語言 ${cfg.locale}")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Ocp-Apim-Subscription-Key", cfg.key)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30000
            readTimeout = 15 * 60 * 1000   // 長音訊要等，但仍遠快於即時
        }
        try {
            conn.setFixedLengthStreamingMode(total)
            DataOutputStream(conn.outputStream).use { out ->
                out.write(head)
                var sent = 0L
                val b = ByteArray(1 shl 16)
                audio.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        sent += n
                    }
                }
                out.write(tail)
                out.flush()
                if (sent != audio.length())
                    throw Exception("上傳不完整：$sent / ${audio.length()}")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            log("HTTP $code，回應 ${body.length} 字元")
            onRaw(body)
            if (code !in 200..299) throw Exception("HTTP $code：${body.take(300)}")
            parse(body, log)
        } finally { conn.disconnect() }
    }

    /** 一行字幕的目標長度。中文字幕一行大約十幾個字比較好讀。 */
    private const val LINE_CHARS = 18

    /**
     * 服務回傳的 phrases 顆粒很粗，一句可能長達數十秒，那是段落不是字幕。
     * 有 words 就用逐字時間切成適合閱讀的短句；沒有就依標點切、再按字數分配時間。
     */
    private fun parse(body: String, log: (String) -> Unit): List<Subtitle> {
        val root = JSONObject(body)
        log("回應欄位：" + root.keys().asSequence().joinToString("、"))
        val phrases = root.optJSONArray("phrases")
        val out = ArrayList<Subtitle>()

        if (phrases != null && phrases.length() > 0) {
            log("原始段落 ${phrases.length()} 段")
            val first = phrases.optJSONObject(0)
            if (first != null) log("段落欄位：" + first.keys().asSequence().joinToString("、"))
            var usedWords = 0
            for (i in 0 until phrases.length()) {
                val p = phrases.getJSONObject(i)
                val text = p.optString("text").trim()
                if (text.isEmpty()) continue
                val start = p.optLong("offsetMilliseconds", 0L)
                val dur = p.optLong("durationMilliseconds", 1500L)
                val words = p.optJSONArray("words")
                if (words != null && words.length() > 0) {
                    usedWords++
                    out.addAll(byWords(words))
                } else {
                    out.addAll(byPunctuation(text, start, dur))
                }
            }
            log("切成 ${out.size} 句" + if (usedWords > 0) "（$usedWords 段有逐字時間）" else "（依標點切分）")
        } else {
            val combined = root.optJSONArray("combinedPhrases")
            val text = if (combined != null && combined.length() > 0)
                combined.getJSONObject(0).optString("text") else ""
            if (text.isNotBlank()) {
                out.addAll(byPunctuation(text.trim(), 0,
                    root.optLong("durationMilliseconds", 5000L)))
                log("只有整段文字，依標點切成 ${out.size} 句")
            } else log("回應裡沒有可用的內容")
        }
        return out
    }

    /** 用逐字時間累積，遇到標點或長度到了就斷句 */
    private fun byWords(words: JSONArray): List<Subtitle> {
        val out = ArrayList<Subtitle>()
        val sb = StringBuilder()
        var start = -1L
        var end = 0L
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val t = w.optString("text")
            if (t.isBlank()) continue
            val ws = w.optLong("offsetMilliseconds", end)
            val we = ws + w.optLong("durationMilliseconds", 200L)
            if (start < 0) start = ws
            if (sb.isNotEmpty() && needsSpace(sb.last(), t.first())) sb.append(' ')
            sb.append(t)
            end = we
            val hardBreak = t.last() in "。！？!?；;"
            if (hardBreak || sb.length >= LINE_CHARS) {
                out.add(Subtitle(start, maxOf(end, start + 400), sb.toString().trim()))
                sb.setLength(0); start = -1
            }
        }
        if (sb.isNotEmpty()) out.add(Subtitle(maxOf(start, 0), maxOf(end, start + 400), sb.toString().trim()))
        return out
    }

    private fun needsSpace(a: Char, b: Char): Boolean {
        fun latin(c: Char) = c.isLetterOrDigit() && c.code < 0x2E80
        return latin(a) && latin(b)
    }

    /** 沒有逐字時間時的退路：依標點切，時間按字數比例分配 */
    private fun byPunctuation(text: String, start: Long, dur: Long): List<Subtitle> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c in "。！？!?；;，,、" && sb.length >= LINE_CHARS / 2) {
                parts.add(sb.toString().trim()); sb.setLength(0)
            } else if (sb.length >= LINE_CHARS * 2) {
                parts.add(sb.toString().trim()); sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) parts.add(sb.toString().trim())
        val clean = parts.filter { it.isNotBlank() }
        if (clean.isEmpty()) return emptyList()
        val total = clean.sumOf { it.length }.coerceAtLeast(1)
        val out = ArrayList<Subtitle>()
        var t = start
        for (p in clean) {
            val d = maxOf(400L, dur * p.length / total)
            out.add(Subtitle(t, t + d, p))
            t += d
        }
        return out
    }

    /** 服務端的單檔上限，超過要先切開 */
    const val MAX_BYTES = 200L * 1024 * 1024
    const val MAX_MS = 2L * 60 * 60 * 1000
}
