package tw.cornming.vlogstitch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tw.cornming.vlogstitch.databinding.ActivityMainBinding
import tw.cornming.vlogstitch.databinding.ItemClipBinding
import java.io.File

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val clips = ArrayList<Uri>()
    private val adapter = ClipAdapter()
    private var exporter: Exporter? = null
    private var busy = false

    private val pick = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            clips.add(uri)
        }
        adapter.notifyDataSetChanged()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnPick.setOnClickListener { pick.launch(arrayOf("video/*")) }
        b.btnExport.setOnClickListener { if (busy) cancelExport() else startExport() }

        b.version.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        refresh()
        checkUpdate(silent = true)
    }

    private fun refresh() {
        b.empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        b.btnExport.isEnabled = clips.isNotEmpty() || busy
        b.count.text = getString(R.string.clip_count, clips.size)
    }

    private fun log(line: String) {
        runOnUiThread {
            b.log.append(line + "\n")
            b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---------------- 匯出 ----------------

    private fun startExport() {
        busy = true
        b.btnExport.text = getString(R.string.stop)
        b.btnPick.isEnabled = false
        b.modeGroup.isEnabled = false
        b.progress.progress = 0
        b.log.text = ""
        log("開始匯出")

        val ex = Exporter(this)
        exporter = ex
        val mode = when {
            b.modeMux.isChecked -> Exporter.Mode.TRANSMUX_ONLY
            b.modeEnc.isChecked -> Exporter.Mode.REENCODE_SDR
            else -> Exporter.Mode.AUTO
        }
        ex.start(clips, mode, object : Exporter.Callback {
            override fun onProgress(percent: Int) {
                runOnUiThread {
                    b.progress.progress = percent
                    b.status.text = getString(R.string.exporting, percent)
                }
            }

            override fun onLog(line: String) = log(line)

            override fun onDone(outputUri: Uri?, file: File, millis: Long) {
                runOnUiThread {
                    b.progress.progress = 100
                    b.status.text = getString(R.string.done_fmt, millis / 1000.0)
                    finishExport()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
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
                .setPositiveButton(R.string.download) { _, _ ->
                    val link = newer.apkUrl ?: newer.pageUrl
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }
                .setNeutralButton(R.string.update_history) { _, _ ->
                    startActivity(Intent(this@MainActivity, UpdateHistoryActivity::class.java))
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    // ---------------- 片段清單 ----------------

    private inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.VH>() {
        inner class VH(val v: ItemClipBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemClipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = clips.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val uri = clips[pos]
            h.v.ord.text = (pos + 1).toString()
            h.v.name.text = displayName(uri)
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
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment ?: "?"
        } ?: (uri.lastPathSegment ?: "?")
    } catch (e: Exception) {
        uri.lastPathSegment ?: "?"
    }
}
