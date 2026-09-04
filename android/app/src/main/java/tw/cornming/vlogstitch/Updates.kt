package tw.cornming.vlogstitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 一次發佈的紀錄。tag 的格式固定是 app-v<versionCode>，
 * 版本比對就直接看那個數字，不需要另外維護版本檔。
 */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val publishedAt: String,
    val apkUrl: String?,
    val pageUrl: String
) {
    /** 2026-09-04T14:00:00Z -> 2026-09-04 22:00 */
    fun localTime(): String = try {
        val src = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        out.format(src.parse(publishedAt)!!)
    } catch (e: Exception) {
        publishedAt
    }
}

object Updates {

    private val TAG_RE = Regex("""^app-v(\d+)$""")

    /** 取回全部發佈紀錄，新的在前。網路有問題就回空清單，不讓它把畫面弄壞。 */
    suspend fun fetchAll(): List<Release> = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.REPO}/releases?per_page=30")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "vlog-stitch-android")
            connectTimeout = 12000
            readTimeout = 12000
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val out = ArrayList<Release>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("draft", false)) continue
                val tag = o.optString("tag_name", "")
                val code = TAG_RE.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue

                var apk: String? = null
                val assets = o.optJSONArray("assets")
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        if (a.optString("name").endsWith(".apk")) {
                            apk = a.optString("browser_download_url"); break
                        }
                    }
                }
                out.add(
                    Release(
                        versionCode = code,
                        versionName = o.optString("name").ifBlank { tag },
                        notes = o.optString("body").trim(),
                        publishedAt = o.optString("published_at"),
                        apkUrl = apk,
                        pageUrl = o.optString("html_url")
                    )
                )
            }
            out.sortedByDescending { it.versionCode }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** 找出比目前版本新的那一版，沒有就回 null。 */
    suspend fun findNewer(current: Int): Release? =
        fetchAll().firstOrNull { it.versionCode > current }
}
