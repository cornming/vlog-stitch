package tw.cornming.vlogstitch

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.cornming.vlogstitch.databinding.ActivityMainBinding
import tw.cornming.vlogstitch.databinding.ItemClipBinding
import java.io.File

class Clip(val uri: Uri) {
    var name: String = uri.lastPathSegment ?: "?"
    var info: Media.Info = Media.Info()
    val durationMs: Long get() = info.durationMs
}

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val clips = ArrayList<Clip>()
    private val adapter = ClipAdapter()
    private var exporter: Exporter? = null
    private var busy = false
    private var mode = Exporter.Mode.AUTO
    private var lastOutput: Uri? = null

    private val thumbs = object : LruCache<String, Bitmap>(24) {}
    private lateinit var touchHelper: ItemTouchHelper

    private val subEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

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
            clips.add(Clip(uri))
        }
        adapter.notifyDataSetChanged()
        refresh(); loadMeta(); save()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.isNestedScrollingEnabled = false
        attachDrag()

        b.btnPick.setOnClickListener { pick.launch(arrayOf("video/*")) }
        b.btnExport.setOnClickListener { if (busy) cancelExport() else startExport() }
        b.logToggle.setOnClickListener { toggleLog() }
        b.btnCopyLog.setOnClickListener { copyLog() }
        b.btnSaveLog.setOnClickListener { saveLog() }
        b.btnPlay.setOnClickListener { lastOutput?.let { openVideo(it) } }
        b.btnShare.setOnClickListener { lastOutput?.let { shareVideo(it) } }
        b.btnShareSrt.setOnClickListener { shareSrt() }
        b.subCard.setOnClickListener { openSubtitles() }

        b.version.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        mode = Store.mode(this)
        restore()
        refresh()
        checkUpdate(silent = true)
    }

    // ---------------- 片段的存與取 ----------------

    private fun save() = Store.save(this, clips.map { it.uri })

    private fun restore() {
        val saved = Store.load(this)
        if (saved.isEmpty()) return
        saved.forEach { clips.add(Clip(it)) }
        adapter.notifyDataSetChanged()
        loadMeta()
        toast(getString(R.string.restored, saved.size))
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---------------- 畫面 ----------------

    private fun openSubtitles() {
        val i = Intent(this, SubtitleActivity::class.java)
        i.putExtra(SubtitleActivity.EXTRA_TOTAL, clips.sumOf { it.durationMs })
        i.putStringArrayListExtra(
            SubtitleActivity.EXTRA_CLIPS, ArrayList(clips.map { it.uri.toString() }))
        subEditor.launch(i)
    }

    /** 字幕不必燒進畫面，另存一份 SRT 上傳 YouTube 就好，成品也不用重新編碼 */
    private fun shareSrt() {
        val subs = Store.subs(this)
        if (subs.isEmpty()) { toast(getString(R.string.srt_none)); return }
        try {
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
            dir.mkdirs()
            val f = File(dir, "subtitles.srt")
            f.writeText(Srt.format(subs))
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/x-subrip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.share_srt)))
        } catch (e: Exception) {
            toast(getString(R.string.srt_save_failed, e.message ?: ""))
        }
    }

    private fun refreshSubCard() {
        val n = Store.subs(this).size
        b.subCardBody.text = if (n == 0) getString(R.string.sub_card_none)
        else getString(R.string.sub_card_some, n)
        b.btnShareSrt.visibility = if (n > 0 && lastOutput != null) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        refreshSubCard()
        b.empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        b.btnExport.isEnabled = clips.isNotEmpty() || busy
        b.count.text = getString(R.string.clip_count, clips.size)
        val total = clips.sumOf { it.durationMs }
        b.total.text = Media.fmtDuration(total)
        drawTrack(total)
        updateBanner()
    }

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

    /** 把「轉封裝還是重新編碼」翻譯成使用者在意的後果 */
    private fun updateBanner() {
        if (clips.isEmpty()) { b.banner.visibility = View.GONE; return }
        b.banner.visibility = View.VISIBLE
        val infos = clips.map { it.info }
        if (infos.any { it.videoMime == null && it.error == null }) {
            b.banner.setBackgroundResource(R.drawable.banner_warn)
            b.bannerTitle.setTextColor(ContextCompat.getColor(this, R.color.dim))
            b.bannerTitle.text = getString(R.string.banner_checking)
            b.bannerBody.text = ""
            return
        }
        val blocker = Media.transmuxBlocker(infos)
        val total = Media.fmtDuration(clips.sumOf { it.durationMs })
        if (blocker == null && mode != Exporter.Mode.REENCODE_SDR) {
            b.banner.setBackgroundResource(R.drawable.banner_ok)
            b.bannerTitle.setTextColor(ContextCompat.getColor(this, R.color.ok))
            b.bannerTitle.text = getString(R.string.banner_ok)
            b.bannerBody.text = getString(R.string.banner_ok_body, clips.size, total)
        } else {
            b.banner.setBackgroundResource(R.drawable.banner_warn)
            b.bannerTitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
            b.bannerTitle.text = getString(R.string.banner_warn)
            b.bannerBody.text = getString(
                R.string.banner_warn_body,
                blocker ?: getString(R.string.mode_forced, modeLabel())
            )
        }
    }

    private fun modeLabel() = getString(
        when (mode) {
            Exporter.Mode.TRANSMUX_ONLY -> R.string.mode_mux
            Exporter.Mode.REENCODE_SDR -> R.string.mode_enc
            else -> R.string.mode_auto
        }
    )

    private fun toggleLog() {
        val show = b.logScroll.visibility != View.VISIBLE
        b.logScroll.visibility = if (show) View.VISIBLE else View.GONE
        b.logToggle.setText(if (show) R.string.hide_log else R.string.show_log)
    }

    private fun logText() = b.log.text.toString()

    private fun copyLog() {
        val t = logText()
        if (t.isBlank()) { toast(getString(R.string.log_empty)); return }
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("vlog-stitch log", t))
        toast(getString(R.string.log_copied))
    }

    /** 記錄很長時剪貼簿可能塞不下，另外給一個存檔＋分享的出口 */
    private fun saveLog() {
        val t = logText()
        if (t.isBlank()) { toast(getString(R.string.log_empty)); return }
        try {
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
            dir.mkdirs()
            val f = File(dir, "vlog-stitch-log.txt")
            f.writeText(t)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.save_log)))
        } catch (e: Exception) {
            toast("存檔失敗：${e.message}")
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            b.log.append(line + "\n")
            b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun loadMeta() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                clips.forEach { c ->
                    if (c.info.durationMs > 0L || c.info.error != null) return@forEach
                    c.name = Media.displayName(this@MainActivity, c.uri)
                    c.info = Media.probe(this@MainActivity, c.uri)
                }
            }
            adapter.notifyDataSetChanged()
            refresh()
        }
    }

    // ---------------- 拖曳排序 ----------------

    private fun attachDrag() {
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || busy) return false
                val item = clips.removeAt(from)
                clips.add(to, item)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.notifyDataSetChanged()
                refresh(); save()
            }
        })
        touchHelper.attachToRecyclerView(b.list)
    }

    // ---------------- 匯出 ----------------

    private fun startExport() {
        busy = true
        lastOutput = null
        b.resultCard.visibility = View.GONE
        b.btnExport.text = getString(R.string.stop)
        b.btnPick.isEnabled = false
        b.progress.progress = 0
        b.log.text = ""
        log("開始匯出")

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
                    lastOutput = outputUri
                    if (outputUri != null) {
                        b.resultCard.visibility = View.VISIBLE
                        b.resultName.text = getString(
                            R.string.result_fmt,
                            Media.fmtDuration(clips.sumOf { it.durationMs }),
                            millis / 1000.0
                        )
                    }
                    finishExport()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    b.progress.isIndeterminate = false
                    b.status.text = getString(R.string.failed_fmt, message)
                    log("失敗：$message")
                    if (b.logScroll.visibility != View.VISIBLE) toggleLog()
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
        refresh()
    }

    private fun openVideo(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            toast("找不到可以播放的 App")
        }
    }

    private fun shareVideo(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, getString(R.string.share)))
    }

    // ---------------- 選單 ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_mode -> { pickMode(); true }
        R.id.action_clear -> { clearAll(); true }
        R.id.action_check_update -> { checkUpdate(silent = false); true }
        R.id.action_history -> {
            startActivity(Intent(this, UpdateHistoryActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun pickMode() {
        val labels = arrayOf(
            getString(R.string.mode_auto), getString(R.string.mode_mux), getString(R.string.mode_enc)
        )
        val values = arrayOf(
            Exporter.Mode.AUTO, Exporter.Mode.TRANSMUX_ONLY, Exporter.Mode.REENCODE_SDR
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.mode_dialog)
            .setSingleChoiceItems(labels, values.indexOf(mode)) { d, which ->
                mode = values[which]
                Store.setMode(this, mode)
                refresh()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAll() {
        if (clips.isEmpty() || busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all)
            .setMessage(getString(R.string.clip_count, clips.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                clips.clear(); adapter.notifyDataSetChanged(); refresh(); save()
                b.resultCard.visibility = View.GONE
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- 更新 ----------------

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

    private fun downloadUpdate(r: Release) {
        val url = r.apkUrl
        if (url == null) { openInBrowser(r.pageUrl); return }
        if (b.logScroll.visibility != View.VISIBLE) toggleLog()

        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.need_install_perm)
                .setMessage(R.string.need_install_perm_body)
                .setPositiveButton(R.string.go_settings) { _, _ ->
                    Updater.openUnknownSourceSettings(this)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            log("尚未允許安裝未知來源，已導向設定")
            return
        }

        val name = "vlog-stitch-${r.versionName}.apk"
        lifecycleScope.launch {
            b.progress.isIndeterminate = false
            b.progress.progress = 0
            b.status.text = getString(R.string.downloading)
            try {
                val f = Updater.download(this@MainActivity, url, name, { line -> log(line) }) { pct, got, total ->
                    runOnUiThread {
                        b.progress.progress = pct
                        b.status.text = getString(R.string.downloading_fmt, pct,
                            got / 1048576.0, total / 1048576.0)
                    }
                }
                b.status.text = getString(R.string.download_ok)
                if (!Updater.install(this@MainActivity, f) { line -> log(line) }) offerBrowser(r)
            } catch (e: Exception) {
                log("下載失敗：${e.javaClass.simpleName} ${e.message}")
                b.status.text = getString(R.string.download_failed)
                offerBrowser(r)
            }
        }
    }

    private fun offerBrowser(r: Release) {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_failed)
            .setMessage(R.string.download_failed_body)
            .setPositiveButton(R.string.open_browser) { _, _ -> openInBrowser(r.pageUrl) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openInBrowser(url: String) {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)), getString(R.string.open_browser)))
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
            h.v.meta.text = when {
                c.info.error != null -> c.info.error
                c.durationMs > 0 -> buildString {
                    append(Media.fmtDuration(c.durationMs))
                    if (c.info.width > 0) append("　${c.info.width}×${c.info.height}")
                    if (c.info.isHdr) append("　HDR")
                }
                else -> "讀取中…"
            }
            h.v.remove.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) { clips.removeAt(p); notifyDataSetChanged(); refresh(); save() }
            }
            h.v.drag.setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && !busy)
                    touchHelper.startDrag(h)
                false
            }
            bindThumb(h, c)
        }

        private fun bindThumb(h: VH, c: Clip) {
            val key = c.uri.toString()
            val cached = thumbs.get(key)
            if (cached != null) { h.v.thumb.setImageBitmap(cached); return }
            h.v.thumb.setImageBitmap(null)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    Media.thumb(this@MainActivity, c.uri, 216, 156)
                }
                if (bmp != null) {
                    thumbs.put(key, bmp)
                    if (h.bindingAdapterPosition >= 0 &&
                        clips.getOrNull(h.bindingAdapterPosition)?.uri == c.uri
                    ) h.v.thumb.setImageBitmap(bmp)
                }
            }
        }
    }
}
