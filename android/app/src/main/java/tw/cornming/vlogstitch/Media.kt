package tw.cornming.vlogstitch

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** 讀取影片規格與縮圖。判斷能不能免重編、以及畫面上要顯示什麼，都靠這裡。 */
object Media {

    data class Info(
        val durationMs: Long = 0,
        val videoMime: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0,
        val transfer: Int = -1,
        val audioMime: String? = null,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val error: String? = null
    ) {
        val isHdr: Boolean
            get() = transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                transfer == MediaFormat.COLOR_TRANSFER_ST2084

        /** 用來比對兩段能不能直接接在一起 */
        fun signature() = "$videoMime|$width x $height|$transfer|$audioMime|$sampleRate|$channels"
    }

    fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment ?: "?"
        } ?: (uri.lastPathSegment ?: "?")
    } catch (e: Exception) {
        uri.lastPathSegment ?: "?"
    }

    fun probe(ctx: Context, uri: Uri): Info {
        var info = Info()
        try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(ctx, uri)
                info = info.copy(
                    durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                )
            }
        } catch (e: Exception) {
            return Info(error = e.message ?: "讀不到檔案")
        }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                fun opt(k: String) = if (f.containsKey(k)) f.getInteger(k) else -1
                if (mime.startsWith("video/") && info.videoMime == null) {
                    info = info.copy(
                        videoMime = mime,
                        width = opt(MediaFormat.KEY_WIDTH),
                        height = opt(MediaFormat.KEY_HEIGHT),
                        rotation = opt("rotation-degrees").coerceAtLeast(0),
                        transfer = opt(MediaFormat.KEY_COLOR_TRANSFER)
                    )
                } else if (mime.startsWith("audio/") && info.audioMime == null) {
                    info = info.copy(
                        audioMime = mime,
                        sampleRate = opt(MediaFormat.KEY_SAMPLE_RATE),
                        channels = opt(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                }
            }
        } catch (e: Exception) {
            info = info.copy(error = e.message ?: "解析失敗")
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
        return info
    }

    /** 取一張縮圖。取靠近開頭但不是第一格，第一格常常是黑的。 */
    fun thumb(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val at = if (dur > 2000) 1_000_000L else dur * 500
            r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        }
    } catch (e: Exception) {
        null
    }

    /** 回傳 null 代表可以直接接合，否則回傳不能的原因。 */
    fun transmuxBlocker(list: List<Info>): String? {
        if (list.isEmpty()) return null
        list.firstOrNull { it.error != null }?.let { return "有片段讀不到內容" }
        list.firstOrNull { it.videoMime == null }?.let { return "有片段沒有影像軌" }
        val a = list[0]
        for (b in list.drop(1)) {
            if (a.videoMime != b.videoMime) return "影像編碼不同"
            if (a.width != b.width || a.height != b.height) return "解析度不同"
            if (a.rotation != b.rotation) return "拍攝方向不同"
            if (a.transfer != b.transfer) return "色彩格式不同（HDR 與 SDR 混用）"
            if (a.audioMime != b.audioMime) return "聲音編碼不同"
            if (a.sampleRate != b.sampleRate) return "取樣率不同"
            if (a.channels != b.channels) return "聲道數不同"
        }
        return null
    }

    fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }
}
