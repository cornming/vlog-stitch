package tw.cornming.vlogstitch

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import tw.cornming.vlogstitch.databinding.ActivitySubtitlesBinding
import tw.cornming.vlogstitch.databinding.ItemSubtitleBinding

/**
 * 字幕編輯。時間軸是「成品」的時間軸，也就是接起來之後的時間，
 * 不是各段影片自己的時間。
 */
class SubtitleActivity : AppCompatActivity() {

    private lateinit var b: ActivitySubtitlesBinding
    private val subs = ArrayList<Subtitle>()
    private val adapter = Adapter()
    private var totalMs = 0L
    private var clipUris = ArrayList<Uri>()
    private var asrJob: Job? = null
    private val asrCancel = AtomicBoolean(false)
    private var pendingAsrAction: (() -> Unit)? = null

    private val askMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            asrLog("已取得錄音權限")
            pendingAsrAction?.invoke()
        } else {
            asrLog("錄音權限被拒。語音辨識引擎需要這個權限才會啟動，" +
                "即使音訊來自檔案而不是麥克風。")
        }
        pendingAsrAction = null
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** 引擎會檢查 RECORD_AUDIO，沒有就回 ERROR_TYPE_INSUFFICIENT_PERMISSION */
    private fun withMic(action: () -> Unit) {
        if (hasMic()) { action(); return }
        asrLog("尚未取得錄音權限，向系統要求…")
        pendingAsrAction = action
        askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private val importSrt = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val raw = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val list = Srt.parse(raw)
            if (list.isEmpty()) { toast(getString(R.string.srt_empty)); return@registerForActivityResult }
            subs.addAll(list)
            sortAndRefresh()
            toast(getString(R.string.srt_imported, list.size))
        } catch (e: Exception) {
            toast(getString(R.string.srt_read_failed, e.message ?: ""))
        }
    }

    private val exportSrt = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)!!.use {
                it.write(Srt.format(subs).toByteArray())
            }
            toast(getString(R.string.srt_saved))
        } catch (e: Exception) {
            toast(getString(R.string.srt_save_failed, e.message ?: ""))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySubtitlesBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { done() }

        totalMs = intent.getLongExtra(EXTRA_TOTAL, 0L)
        intent.getStringArrayListExtra(EXTRA_CLIPS)?.forEach { clipUris.add(Uri.parse(it)) }
        subs.addAll(Store.subs(this))

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnAdd.setOnClickListener { addNew() }
        b.btnImport.setOnClickListener { importSrt.launch(arrayOf("*/*")) }
        b.btnExport.setOnClickListener {
            if (subs.isEmpty()) { toast(getString(R.string.srt_none)); return@setOnClickListener }
            exportSrt.launch("subtitles.srt")
        }
        b.btnSelectAll.setOnClickListener { setAll(true) }
        b.btnSelectNone.setOnClickListener { setAll(false) }
        b.btnDelete.setOnClickListener { deleteSelected() }

        b.btnAsr.setOnClickListener {
            if (asrJob != null) stopAsr() else { b.asrLog.text = ""; withMic { startAsr() } }
        }
        b.btnAsrCheck.setOnClickListener { b.asrLog.text = ""; withMic { checkAsr() } }
        b.btnAsrCopy.setOnClickListener { copyAsrLog() }
        b.btnAsrWav.setOnClickListener { b.asrLog.text = ""; dumpWav() }
        b.btnCloudSetup.setOnClickListener { cloudSetup() }
        b.btnCloudLocales.setOnClickListener { b.asrLog.text = ""; cloudLocales() }
        b.btnCloud.setOnClickListener { b.asrLog.text = ""; cloudRun() }
        setupAsrBox()
        refresh()
    }

    private fun setupAsrBox() {
        val adv = Transcriber.advancedSupported()
        b.asrHint.text = when {
            clipUris.isEmpty() -> getString(R.string.asr_hint_noclip)
            adv -> getString(R.string.asr_hint_ok, Media.fmtDuration(totalMs))
            else -> getString(R.string.asr_hint_basic)
        }
        b.btnAsr.isEnabled = clipUris.isNotEmpty()
    }

    private fun asrLog(line: String) {
        runOnUiThread {
            b.asrLogScroll.visibility = View.VISIBLE
            b.asrLog.append(line + "\n")
            b.asrLogScroll.post { b.asrLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyAsrLog() {
        val t = b.asrLog.text.toString()
        if (t.isBlank()) { toast(getString(R.string.log_empty)); return }
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("asr log", t))
        toast(getString(R.string.log_copied))
    }

    /** 只測 API，不碰音訊，用來確認問題出在哪一端 */
    private fun checkAsr() {
        lifecycleScope.launch {
            asrLog("錄音權限：${if (hasMic()) "已授予" else "未授予"}")
            try {
                Transcriber.checkOnly(
                    Transcriber.advancedSupported(),
                    Locale.forLanguageTag("cmn-Hant-TW")
                ) { line -> asrLog(line) }
                if (Transcriber.advancedSupported()) {
                    asrLog("──── 再測 Basic 模式 ────")
                    Transcriber.checkOnly(false, Locale.forLanguageTag("cmn-Hant-TW")) { l -> asrLog(l) }
                }
            } catch (t: Throwable) {
                asrLog("檢查本身出錯：${t.javaClass.name} ${t.message ?: ""}")
            }
        }
    }

    /**
     * 把實際會送進辨識器的那份音訊存成 WAV 並分享出去。
     * 播起來如果是雜訊或變調，就證明問題在解碼管線而不是模型。
     */
    private fun dumpWav() {
        if (clipUris.isEmpty()) { toast(getString(R.string.asr_hint_noclip)); return }
        asrLog(getString(R.string.asr_wav_doing))
        lifecycleScope.launch {
            try {
                val wav = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
                    dir.mkdirs()
                    val raw = java.io.File(dir, "asr-input.pcm")
                    val out = java.io.File(dir, "asr-input.wav")
                    java.io.FileOutputStream(raw).use { os ->
                        // 只取前兩分鐘就夠判斷，檔案也不會太大
                        var written = 0L
                        val limit = AudioPcm.BYTES_PER_SEC.toLong() * 120
                        for (u in clipUris) {
                            if (written >= limit) break
                            AudioPcm.decodeTo16kMono(this@SubtitleActivity, u,
                                { l -> asrLog(l) }) { chunk ->
                                os.write(chunk); written += chunk.size
                                written < limit
                            }
                        }
                    }
                    AudioPcm.writeWav(raw, out)
                    raw.delete()
                    out
                }
                asrLog("已產生 ${wav.name}，大小 ${wav.length() / 1024} KB")
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@SubtitleActivity, "$packageName.fileprovider", wav)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.asr_wav)))
            } catch (t: Throwable) {
                asrLog("匯出音訊失敗：${t.javaClass.simpleName} ${t.message ?: ""}")
            }
        }
    }

    // ---------------- 雲端辨識 ----------------

    private fun cloudSetup() {
        val cfg = Cloud.load(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
        }
        val ep = EditText(this).apply {
            setText(cfg.endpoint); hint = getString(R.string.cloud_endpoint_hint); setSingleLine()
        }
        val key = EditText(this).apply {
            setText(cfg.key); hint = getString(R.string.cloud_key_hint); setSingleLine()
        }
        val loc = EditText(this).apply {
            setText(cfg.locale); hint = getString(R.string.cloud_locale_hint); setSingleLine()
        }
        box.addView(ep); box.addView(key); box.addView(loc)
        AlertDialog.Builder(this)
            .setTitle(R.string.cloud_dialog)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Cloud.save(this, Cloud.Config(
                    ep.text.toString(), key.text.toString(),
                    loc.text.toString().ifBlank { "zh-CN" }))
                toast("已儲存")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cloudReady(): Cloud.Config? {
        val cfg = Cloud.load(this)
        if (cfg.endpoint.isBlank() || cfg.key.isBlank()) {
            asrLog(getString(R.string.cloud_need_setup)); cloudSetup(); return null
        }
        return cfg
    }

    /** 文件的語言清單可能落後，直接用你的金鑰跟服務問一次 */
    private fun cloudLocales() {
        val cfg = cloudReady() ?: return
        lifecycleScope.launch {
            try {
                val list = Cloud.listLocales(cfg) { l -> asrLog(l) }
                if (list.isEmpty()) asrLog("查不到清單，看上面的錯誤訊息")
                else {
                    asrLog("服務回報支援 ${list.size} 種語言：")
                    asrLog(list.joinToString("、"))
                    val tw = list.filter { it.startsWith("zh") || it.contains("Hant", true) }
                    asrLog(if (tw.isEmpty()) "→ 沒有任何中文語系" else "→ 中文相關：${tw.joinToString("、")}")
                }
            } catch (t: Throwable) {
                asrLog("查詢失敗：${t.javaClass.simpleName} ${t.message ?: ""}")
            }
        }
    }

    private fun cloudRun() {
        if (clipUris.isEmpty()) { toast(getString(R.string.asr_hint_noclip)); return }
        val cfg = cloudReady() ?: return
        val before = subs.size
        b.cloudProgress.visibility = View.VISIBLE
        b.btnCloud.isEnabled = false
        lifecycleScope.launch {
            try {
                asrLog(getString(R.string.cloud_extracting))
                val m4a = withContext(Dispatchers.IO) {
                    val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
                    dir.mkdirs()
                    val f = java.io.File(dir, "asr-audio.m4a")
                    if (f.exists()) f.delete()
                    AudioTrack.extract(this@SubtitleActivity, clipUris, f) { l -> asrLog(l) }
                }
                asrLog(getString(R.string.cloud_uploading))
                val got = Cloud.transcribe(cfg, m4a) { l -> asrLog(l) }
                if (got.isEmpty()) { asrLog("沒有取得任何字幕"); return@launch }
                if (before > 0) {
                    AlertDialog.Builder(this@SubtitleActivity)
                        .setMessage(getString(R.string.asr_replace, before))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            subs.clear(); subs.addAll(got); sortAndRefresh()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            subs.addAll(got); sortAndRefresh()
                        }.show()
                } else { subs.addAll(got); sortAndRefresh() }
                asrLog("完成，新增 ${got.size} 句")
            } catch (t: Throwable) {
                asrLog("雲端辨識失敗：${t.javaClass.simpleName}")
                asrLog(t.message ?: "（沒有訊息）")
            } finally {
                b.cloudProgress.visibility = View.GONE
                b.btnCloud.isEnabled = true
            }
        }
    }

    private fun startAsr() {
        asrCancel.set(false)
        b.btnAsr.setText(R.string.asr_stop)
        b.asrProgress.visibility = View.VISIBLE
        b.asrProgress.progress = 0
        b.asrStatus.text = ""
        asrLog("== 開始 ==")
        asrLog("錄音權限：${if (hasMic()) "已授予" else "未授予"}")
        val before = subs.size
        asrJob = lifecycleScope.launch {
            try {
                val got = Transcriber.run(
                    ctx = this@SubtitleActivity,
                    clips = clipUris,
                    totalMs = totalMs,
                    locale = Locale.forLanguageTag("cmn-Hant-TW"),
                    advanced = Transcriber.advancedSupported(),
                    log = { line -> asrLog(line) },
                    onProgress = { p ->
                        runOnUiThread {
                            if (totalMs > 0)
                                b.asrProgress.progress = ((p.fedMs * 100) / totalMs).toInt().coerceIn(0, 100)
                            b.asrStatus.text = getString(
                                R.string.asr_running,
                                Media.fmtDuration(p.fedMs), Media.fmtDuration(totalMs), p.lines
                            )
                        }
                    },
                    cancelled = asrCancel
                )
                if (got.isEmpty()) {
                    b.asrStatus.text = getString(R.string.asr_done, 0)
                } else if (before > 0) {
                    AlertDialog.Builder(this@SubtitleActivity)
                        .setMessage(getString(R.string.asr_replace, before))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            subs.clear(); subs.addAll(got); sortAndRefresh()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            subs.addAll(got); sortAndRefresh()
                        }
                        .show()
                    b.asrStatus.text = getString(R.string.asr_done, got.size)
                } else {
                    subs.addAll(got); sortAndRefresh()
                    b.asrStatus.text = getString(R.string.asr_done, got.size)
                }
            } catch (t: Throwable) {
                // 一定要接 Throwable：AICore 沒裝好時丟的是 Error，不是 Exception
                asrLog("!! ${t.javaClass.name}")
                asrLog(t.message ?: "（沒有訊息）")
                t.stackTrace.take(6).forEach { asrLog("  at $it") }
                b.asrStatus.text = getString(R.string.asr_failed,
                    "${t.javaClass.simpleName} ${t.message ?: ""}")
            } finally {
                asrLog("== 結束 ==")
                asrJob = null
                b.btnAsr.setText(R.string.asr_start)
                b.asrProgress.visibility = View.GONE
            }
        }
    }

    private fun stopAsr() {
        asrCancel.set(true)
        asrJob?.cancel()
        asrJob = null
        b.btnAsr.setText(R.string.asr_start)
        b.asrProgress.visibility = View.GONE
    }

    override fun onBackPressed() { done(); }

    private fun done() {
        Store.setSubs(this, subs)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun setAll(v: Boolean) {
        subs.forEach { it.selected = v }
        adapter.notifyDataSetChanged()
        refresh()
    }

    private fun deleteSelected() {
        val n = subs.count { it.selected }
        if (n == 0) { toast(getString(R.string.pick_first)); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_n, n))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                subs.removeAll { it.selected }
                sortAndRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addNew() {
        val start = subs.maxOfOrNull { it.endMs } ?: 0L
        val s = Subtitle(start, start + 2000, "")
        subs.add(s)
        sortAndRefresh()
        edit(subs.indexOf(s))
    }

    private fun sortAndRefresh() {
        subs.sortBy { it.startMs }
        adapter.notifyDataSetChanged()
        refresh()
    }

    private fun refresh() {
        b.count.text = getString(R.string.sub_count, subs.size)
        b.empty.visibility = if (subs.isEmpty()) View.VISIBLE else View.GONE
        b.hint.text = if (totalMs > 0)
            getString(R.string.sub_hint_total, Media.fmtDuration(totalMs)) else ""
    }

    /** 用對話框編輯，避免在 RecyclerView 裡放 EditText 造成的回收錯亂 */
    private fun edit(pos: Int) {
        if (pos < 0 || pos >= subs.size) return
        val s = subs[pos]
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val text = EditText(this).apply {
            setText(s.text); hint = getString(R.string.sub_text_hint); setSingleLine(false); maxLines = 4
        }
        val start = EditText(this).apply {
            setText(Srt.stamp(s.startMs)); hint = "00:00:00,000"; setSingleLine()
        }
        val end = EditText(this).apply {
            setText(Srt.stamp(s.endMs)); hint = "00:00:00,000"; setSingleLine()
        }
        box.addView(text); box.addView(start); box.addView(end)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_sub)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                s.text = text.text.toString().trim()
                parseStamp(start.text.toString())?.let { s.startMs = it }
                parseStamp(end.text.toString())?.let { s.endMs = it }
                if (s.endMs <= s.startMs) s.endMs = s.startMs + 500
                sortAndRefresh()
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                subs.remove(s); sortAndRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseStamp(v: String): Long? {
        val m = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""").find(v.trim()) ?: return null
        val g = m.groupValues
        val ms = g[4].padEnd(3, '0').take(3).toLong()
        return g[1].toLong() * 3600000 + g[2].toLong() * 60000 + g[3].toLong() * 1000 + ms
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.subtitles, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_subs -> {
            if (subs.isNotEmpty()) AlertDialog.Builder(this)
                .setTitle(R.string.clear_subs)
                .setMessage(getString(R.string.sub_count, subs.size))
                .setPositiveButton(android.R.string.ok) { _, _ -> subs.clear(); sortAndRefresh() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val v: ItemSubtitleBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSubtitleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = subs.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = subs[pos]
            h.v.time.text = "${Srt.short(s.startMs)} → ${Srt.short(s.endMs)}"
            h.v.text.text = s.text.ifBlank { getString(R.string.sub_blank) }
            h.v.check.setOnCheckedChangeListener(null)
            h.v.check.isChecked = s.selected
            h.v.check.setOnCheckedChangeListener { _, v ->
                val p = h.bindingAdapterPosition
                if (p >= 0) subs[p].selected = v
            }
            h.v.root.setOnClickListener { edit(h.bindingAdapterPosition) }
        }
    }

    companion object {
        const val EXTRA_TOTAL = "total_ms"
        const val EXTRA_CLIPS = "clip_uris"
    }
}
