package tw.cornming.vlogstitch

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.cornming.vlogstitch.databinding.ActivityMainBinding
import tw.cornming.vlogstitch.databinding.ItemClipBinding
import java.io.File

data class Clip(val uri: Uri, var name: String, var durationMs: Long = 0L)

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val clips = ArrayList<Clip>()
    private val adapter = ClipAdapter()
    private var exporter: Exporter? = null
    private var busy = false
    private var downloadId = -1L

    private val pick = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            clips.add(Clip(uri, uri.lastPathSegment ?: "?"))
        }
        adapter.notifyDataSetChanged()
        refresh()
        loadMeta()
    }

    /** APK 下載完就直接叫出安裝畫面，不用經過瀏覽器 */
    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return
            val dm = getSystemService(DownloadManager::class.java)
            val uri = dm.getUriForDownloadedFile(id) ?: return
            val path = dm.getUriForDownloadedFile(id)
            val file = resolveDownloadedFile()
            val apkUri = if (file != null && file.exists())
                FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            else path
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(install)
            } catch (e: Exception) {
                log("叫不出安裝畫面：${e.message}")
            }
        }
    }

    private fun resolveDownloadedFile(): File? {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return dir.listFiles()?.filter { it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.isNestedScrollingEnabled = false

        b.btnPick.setOnClickListener { pick.launch(arrayOf("video/*")) }
        b.btnExport.setOnClickListener { if (busy) cancelExport() else startExport() }
        b.logToggle.setOnClickListener { toggleLog() }
        b.modeGroup.setOnCheckedStateChangeListener { _, _ -> updateModeHint() }

        b.version.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        updateModeHint()
        refresh()

        ContextCompat.registerReceiver(
            this, downloadDone,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        checkUpdate(silent = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadDone) } catch (_: Exception) {}
    }

    // ---------------- 畫面 ----------------

    private fun refresh() {
        b.empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        b.btnExport.isEnabled = clips.isNotEmpty() || busy
        b.count.text = getString(R.string.clip_count, clips.size)
        val total = clips.sumOf { it.durationMs }
        b.total.text = fmt(total)
        drawTrack(total)
    }

    /** 依各段長度比例畫出時間軸，跟網頁版一樣 */
    private fun drawTrack(total: Long) {
        b.track.removeAllViews()
        if (total <= 0L) return
        clips.forEachIndexed { i, c ->
            if (c.durationMs <= 0) return@forEachIndexed
            val v = View(this)
            v.background = ContextCompat.getDrawable(this, R.drawable.seg_bg)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT,
                c.durationMs.toFloat())
            if (i > 0) lp.marginStart = 2
            b.track.addView(v, lp)
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun updateModeHint() {
        b.modeHint.setText(
            when {
                b.modeMux.isChecked -> R.string.hint_mux
                b.modeEnc.isChecked -> R.string.hint_enc
                else -> R.string.hint_auto
            }
        )
    }

    private fun toggleLog() {
        val show = b.logScroll.visibility != View.VISIBLE
        b.logScroll.visibility = if (show) View.VISIBLE else View.GONE
        b.logToggle.setText(if (show) R.string.hide_log else R.string.show_log)
    }

    private fun log(line: String) {
        runOnUiThread {
            b.log.append(line + "\n")
            b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 檔名與長度在背景讀，不擋畫面 */
    private fun loadMeta() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                clips.forEach { c ->
                    if (c.durationMs > 0L) return@forEach
                    c.name = displayName(c.uri)
                    c.durationMs = try {
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(this@MainActivity, c.uri)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                        }
                    } catch (e: Exception) {
                        0L
                    }
                }
            }
            adapter.notifyDataSetChanged()
            refresh()
        }
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment ?: "?"
        } ?: (uri.lastPathSegment ?: "?")
    } catch (e: Exception) {
        uri.lastPathSegment ?: "?"
    }

    // ---------------- 匯出 ----------------

    private fun startExport() {
        busy = true
        b.btnExport.text = getString(R.string.stop)
        b.btnPick.isEnabled = false
        b.modeGroup.isEnabled = false
        b.progress.progress = 0
        b.log.text = ""
        if (b.logScroll.visibility != View.VISIBLE) toggleLog()
        log("開始匯出")

        val mode = when {
            b.modeMux.isChecked -> Exporter.Mode.TRANSMUX_ONLY
            b.modeEnc.isChecked -> Exporter.Mode.REENCODE_SDR
            else -> Exporter.Mode.AUTO
        }
        val ex = Exporter(this)
        exporter = ex
        ex.start(clips.map { it.uri }, mode, object : Exporter.Callback {
            override fun onProgress(percent: Int) {
                runOnUiThread {
                    b.progress.setProgressCompat(percent, true)
                    b.status.text = getString(R.string.exporting, percent)
                }
            }

            override fun onSaving() {
                runOnUiThread {
                    b.progress.isIndeterminate = true
                    b.status.text = getString(R.string.saving)
                }
            }

            override fun onLog(line: String) = log(line)

            override fun onDone(outputUri: Uri?, file: File, millis: Long) {
                runOnUiThread {
                    b.progress.isIndeterminate = false
                    b.progress.progress = 100
                    b.status.text = getString(R.string.done_fmt, millis / 1000.0)
                    finishExport()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    b.progress.isIndeterminate = false
                    b.status.text = getString(R.string.failed_fmt, message)
                    log("失敗：$message")
                    finishExport()
                }
            }
        })
    }

    private fun cancelExport() {
        exporter?.cancel()
        log("已取消")
        b.progress.isIndeterminate = false
        b.status.text = getString(R.string.cancelled)
        finishExport()
    }

    private fun finishExport() {
        busy = false
        exporter = null
        b.btnExport.text = getString(R.string.export)
        b.btnPick.isEnabled = true
        b.modeGroup.isEnabled = true
        refresh()
    }

    // ---------------- 更新 ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_check_update -> { checkUpdate(silent = false); true }
        R.id.action_history -> {
            startActivity(Intent(this, UpdateHistoryActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun checkUpdate(silent: Boolean) {
        lifecycleScope.launch {
            val newer = Updates.findNewer(BuildConfig.VERSION_CODE)
            if (newer == null) {
                if (!silent) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.no_update)
                        .setMessage(getString(R.string.already_latest, BuildConfig.VERSION_NAME))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.update_available, newer.versionName))
                .setMessage(
                    getString(R.string.update_body, newer.localTime(),
                        newer.notes.ifBlank { getString(R.string.no_notes) })
                )
                .setPositiveButton(R.string.download) { _, _ -> downloadUpdate(newer) }
                .setNeutralButton(R.string.update_history) { _, _ ->
                    startActivity(Intent(this@MainActivity, UpdateHistoryActivity::class.java))
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    /**
     * 用系統的 DownloadManager 抓 APK，抓完直接叫安裝畫面。
     * 先前用 ACTION_VIEW 開網址會被 App 內建瀏覽器接走而下載失敗。
     */
    private fun downloadUpdate(r: Release) {
        val url = r.apkUrl
        if (url == null) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl)))
            return
        }
        val name = "vlog-stitch-${r.versionName}.apk"
        try {
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription(getString(R.string.app_name))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            downloadId = getSystemService(DownloadManager::class.java).enqueue(req)
            b.status.text = getString(R.string.downloading)
            log("下載更新 $name")
        } catch (e: Exception) {
            log("下載失敗：${e.message}")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl)))
        }
    }

    // ---------------- 片段清單 ----------------

    private inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.VH>() {
        inner class VH(val v: ItemClipBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemClipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = clips.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = clips[pos]
            h.v.ord.text = (pos + 1).toString()
            h.v.name.text = c.name
            h.v.meta.text = if (c.durationMs > 0) fmt(c.durationMs) else "讀取中…"
            h.v.up.isEnabled = pos > 0
            h.v.down.isEnabled = pos < clips.size - 1
            h.v.up.setOnClickListener { move(pos, -1) }
            h.v.down.setOnClickListener { move(pos, 1) }
            h.v.remove.setOnClickListener {
                clips.removeAt(pos); notifyDataSetChanged(); refresh()
            }
        }
    }

    private fun move(pos: Int, d: Int) {
        val j = pos + d
        if (j < 0 || j >= clips.size) return
        val tmp = clips[pos]; clips[pos] = clips[j]; clips[j] = tmp
        adapter.notifyDataSetChanged()
        refresh()
    }
}
