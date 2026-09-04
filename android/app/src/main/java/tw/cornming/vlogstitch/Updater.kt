package tw.cornming.vlogstitch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自己下載 APK 並叫出安裝畫面。
 *
 * 原本用系統的 DownloadManager，失敗時什麼線索都沒有，而且要靠廣播回報，
 * App 被切走就收不到。改成自己拉，每一步都能記錄，出錯看得到原因。
 */
object Updater {

    class Failed(message: String) : Exception(message)

    /** GitHub 的下載網址會轉址到 objects.githubusercontent.com，要自己跟。 */
    suspend fun download(
        ctx: Context,
        url: String,
        fileName: String,
        log: (String) -> Unit,
        onProgress: (percent: Int, got: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw Failed("拿不到下載資料夾")
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
        val out = File(dir, fileName)

        var current = url
        var conn: HttpURLConnection? = null
        var hops = 0
        while (true) {
            log("連線 ${current.take(80)}")
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "vlog-stitch-android")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw Failed("轉址沒有給網址")
                conn.disconnect()
                current = loc
                if (++hops > 5) throw Failed("轉址太多次")
                continue
            }
            if (code !in 200..299) {
                val msg = "HTTP $code ${conn.responseMessage}"
                conn.disconnect()
                throw Failed(msg)
            }
            break
        }

        val c = conn ?: throw Failed("連線失敗")
        val total = c.contentLengthLong
        log("開始下載，總大小 ${total / 1048576} MB")
        var got = 0L
        var lastPct = -1
        try {
            c.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct, got, total) }
                        }
                    }
                    fos.fd.sync()
                }
            }
        } finally {
            c.disconnect()
        }

        if (total > 0 && got != total) throw Failed("下載不完整：$got / $total")
        if (out.length() < 1_000_000) throw Failed("檔案太小，可能不是 APK（${out.length()} bytes）")
        log("下載完成 ${out.length() / 1048576} MB → ${out.name}")
        out
    }

    /** 用 FileProvider 把檔案交給系統的安裝程式。 */
    fun install(ctx: Context, apk: File, log: (String) -> Unit): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        log("交給系統安裝：$uri")
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        log("叫不出安裝畫面：${e.javaClass.simpleName} ${e.message}")
        false
    }

    /** 沒有這個權限的話，安裝畫面按下去只會被擋掉。 */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openUnknownSourceSettings(ctx: Context) {
        val i = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
