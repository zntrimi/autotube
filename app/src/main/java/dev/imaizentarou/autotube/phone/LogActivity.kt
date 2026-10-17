package dev.imaizentarou.autotube.phone

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.imaizentarou.autotube.AppLog

/** アプリ内ログの表示・共有・消去。レイアウトはコードで組む（画面がこれだけなので）。 */
class LogActivity : ComponentActivity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(Button(this).apply { setText("更新"); setOnClickListener { refresh() } })
        buttons.addView(Button(this).apply { setText("共有"); setOnClickListener { startActivity(Intent.createChooser(AppLog.shareIntent(this@LogActivity), "ログを共有")) } })
        buttons.addView(Button(this).apply { setText("消去"); setOnClickListener { AppLog.clear(); refresh() } })
        root.addView(buttons)

        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(text) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        text.text = AppLog.recent(800)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
