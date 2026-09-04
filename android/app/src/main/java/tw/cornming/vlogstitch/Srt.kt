package tw.cornming.vlogstitch

import org.json.JSONArray
import org.json.JSONObject

data class Subtitle(
    var startMs: Long,
    var endMs: Long,
    var text: String,
    var selected: Boolean = false
)

/** SRT 的讀寫。也吃 WebVTT，因為 YouTube 下載下來常常是那個格式。 */
object Srt {

    private val TIME = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val INDEX_ONLY = Regex("""^\d+$""")

    fun parse(raw: String): List<Subtitle> {
        val out = ArrayList<Subtitle>()
        val body = raw.replace("\r\n", "\n").replace('\r', '\n')
            .removePrefix("\uFEFF")
            .let { if (it.startsWith("WEBVTT")) it.substringAfter('\n') else it }
        for (block in body.split(Regex("\n{2,}"))) {
            val m = TIME.find(block) ?: continue
            val g = m.groupValues
            val start = g[1].toLong() * 3600000 + g[2].toLong() * 60000 +
                g[3].toLong() * 1000 + pad(g[4])
            val end = g[5].toLong() * 3600000 + g[6].toLong() * 60000 +
                g[7].toLong() * 1000 + pad(g[8])
            val text = block.split('\n')
                .filter { !TIME.containsMatchIn(it) && !INDEX_ONLY.matches(it.trim()) }
                .joinToString(" ") { it.trim() }
                .trim()
            if (text.isNotEmpty()) out.add(Subtitle(start, maxOf(end, start + 300), text))
        }
        return out.sortedBy { it.startMs }
    }

    /** 毫秒欄位可能是 1 到 3 位，要補齊才不會差一個數量級 */
    private fun pad(ms: String): Long = when (ms.length) {
        1 -> ms.toLong() * 100
        2 -> ms.toLong() * 10
        else -> ms.toLong()
    }

    fun format(list: List<Subtitle>): String {
        val sorted = list.sortedBy { it.startMs }
        return buildString {
            sorted.forEachIndexed { i, s ->
                append(i + 1).append('\n')
                append(stamp(s.startMs)).append(" --> ").append(stamp(s.endMs)).append('\n')
                append(s.text).append("\n\n")
            }
        }.trimEnd() + "\n"
    }

    fun stamp(ms: Long): String {
        val t = maxOf(0L, ms)
        return String.format(
            "%02d:%02d:%02d,%03d",
            t / 3600000, t % 3600000 / 60000, t % 60000 / 1000, t % 1000
        )
    }

    /** 給畫面用的短格式 */
    fun short(ms: Long): String {
        val t = maxOf(0L, ms)
        return String.format("%d:%02d.%d", t / 60000, t % 60000 / 1000, t % 1000 / 100)
    }

    fun toJson(list: List<Subtitle>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text))
        }
        return arr.toString()
    }

    fun fromJson(raw: String?): MutableList<Subtitle> {
        if (raw.isNullOrBlank()) return ArrayList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Subtitle>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Subtitle(o.getLong("s"), o.getLong("e"), o.optString("t")))
            }
            out
        } catch (e: Exception) {
            ArrayList()
        }
    }
}
