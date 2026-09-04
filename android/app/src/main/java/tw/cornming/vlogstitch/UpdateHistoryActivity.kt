package tw.cornming.vlogstitch

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tw.cornming.vlogstitch.databinding.ActivityUpdatesBinding

/** 列出歷次發佈與更新說明，目前安裝的那一版會標出來。 */
class UpdateHistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityUpdatesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityUpdatesBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val list = Updates.fetchAll()
            b.loading.visibility = View.GONE
            if (list.isEmpty()) {
                b.body.text = getString(R.string.no_releases)
                return@launch
            }
            val sb = StringBuilder()
            for (r in list) {
                val mark = when {
                    r.versionCode == BuildConfig.VERSION_CODE -> "  ← 目前安裝"
                    r.versionCode > BuildConfig.VERSION_CODE -> "  ← 有更新"
                    else -> ""
                }
                sb.append("■ ").append(r.versionName).append(mark).append('\n')
                sb.append(r.localTime()).append('\n')
                sb.append(r.notes.ifBlank { getString(R.string.no_notes) }).append("\n\n")
            }
            b.body.text = sb.toString().trimEnd()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
