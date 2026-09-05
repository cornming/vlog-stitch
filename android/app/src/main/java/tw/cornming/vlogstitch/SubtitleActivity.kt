package tw.cornming.vlogstitch

import android.app.Activity
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
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

        b.btnAsr.setOnClickListener { if (asrJob != null) stopAsr() else startAsr() }
        b.btnAsrCheck.setOnClickListener { checkAsr() }
        b.btnAsrCopy.setOnClickListener { copyAsrLog() }
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
        b.asrLog.text = ""
        lifecycleScope.launch {
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

    private fun startAsr() {
        asrCancel.set(false)
        b.btnAsr.setText(R.string.asr_stop)
        b.asrProgress.visibility = View.VISIBLE
        b.asrProgress.progress = 0
        b.asrStatus.text = ""
        b.asrLog.text = ""
        asrLog("== 開始 ==")
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
