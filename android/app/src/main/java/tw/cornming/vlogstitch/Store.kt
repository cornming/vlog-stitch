package tw.cornming.vlogstitch

import android.content.Context
import android.net.Uri

/** 片段清單與偏好設定的存放處，App 被系統回收後回得來。 */
object Store {
    private const val FILE = "vlog-stitch"
    private const val KEY_CLIPS = "clips"
    private const val KEY_MODE = "mode"
    private const val KEY_SUBS = "subs"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(ctx: Context, uris: List<Uri>) {
        prefs(ctx).edit().putString(KEY_CLIPS, uris.joinToString("\n")).apply()
    }

    /** 回復時要確認權限還在，否則之後每一步都會失敗。 */
    fun load(ctx: Context): List<Uri> {
        val raw = prefs(ctx).getString(KEY_CLIPS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val held = ctx.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        return raw.split("\n").filter { it.isNotBlank() && it in held }.map { Uri.parse(it) }
    }

    fun mode(ctx: Context): Exporter.Mode = try {
        Exporter.Mode.valueOf(prefs(ctx).getString(KEY_MODE, null) ?: "AUTO")
    } catch (e: Exception) {
        Exporter.Mode.AUTO
    }

    fun setMode(ctx: Context, m: Exporter.Mode) {
        prefs(ctx).edit().putString(KEY_MODE, m.name).apply()
    }

    fun subs(ctx: Context): MutableList<Subtitle> =
        Srt.fromJson(prefs(ctx).getString(KEY_SUBS, null))

    fun setSubs(ctx: Context, list: List<Subtitle>) {
        prefs(ctx).edit().putString(KEY_SUBS, Srt.toJson(list)).apply()
    }
}
