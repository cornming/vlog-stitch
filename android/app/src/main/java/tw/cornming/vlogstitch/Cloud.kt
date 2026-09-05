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

    data class Config(val endpoint: String, val key: String, val locale: String)

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            p.getString("az_endpoint", "").orEmpty(),
            p.getString("az_key", "").orEmpty(),
            p.getString("az_locale", "zh-CN").orEmpty()
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("az_endpoint", c.endpoint.trim())
            .putString("az_key", c.key.trim())
            .putString("az_locale", c.locale.trim())
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
        log: (String) -> Unit
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val url = "${host(cfg.endpoint)}/speechtotext/transcriptions:transcribe?api-version=$API_VERSION"
        val boundary = "----vlogstitch${System.currentTimeMillis()}"
        val definition = JSONObject()
            .put("locales", JSONArray().put(cfg.locale))
            .toString()

        log("上傳 ${audio.length() / 1024} KB 到 ${host(cfg.endpoint)}")
        log("語言 ${cfg.locale}")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Ocp-Apim-Subscription-Key", cfg.key)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30000
            readTimeout = 15 * 60 * 1000   // 長音訊要等，但仍遠快於即時
            setChunkedStreamingMode(1 shl 16)
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"definition\"\r\n\r\n")
                out.write(definition.toByteArray(Charsets.UTF_8))
                out.writeBytes("\r\n--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"audio\"; filename=\"${audio.name}\"\r\n"
                )
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                audio.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            log("HTTP $code，回應 ${body.length} 字元")
            if (code !in 200..299) throw Exception("HTTP $code：${body.take(300)}")
            parse(body, log)
        } finally { conn.disconnect() }
    }

    /** 回應裡的 phrases 帶 offsetMilliseconds / durationMilliseconds，時間碼直接用 */
    private fun parse(body: String, log: (String) -> Unit): List<Subtitle> {
        val root = JSONObject(body)
        val phrases = root.optJSONArray("phrases")
        val out = ArrayList<Subtitle>()
        if (phrases != null && phrases.length() > 0) {
            for (i in 0 until phrases.length()) {
                val p = phrases.getJSONObject(i)
                val text = p.optString("text").trim()
                if (text.isEmpty()) continue
                val start = p.optLong("offsetMilliseconds", 0L)
                val dur = p.optLong("durationMilliseconds", 1500L)
                out.add(Subtitle(start, start + maxOf(dur, 400L), text))
            }
            log("取得 ${out.size} 句，含時間碼")
        } else {
            val combined = root.optJSONArray("combinedPhrases")
            val text = if (combined != null && combined.length() > 0)
                combined.getJSONObject(0).optString("text") else ""
            if (text.isNotBlank()) {
                out.add(Subtitle(0, root.optLong("durationMilliseconds", 5000L), text.trim()))
                log("只拿到整段文字，沒有分句時間碼")
            } else log("回應裡沒有可用的內容")
        }
        return out
    }
}
