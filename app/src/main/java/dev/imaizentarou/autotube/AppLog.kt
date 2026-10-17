package dev.imaizentarou.autotube

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * アプリ内ログ。logcat に出しつつ、直近をメモリと `filesDir/logs/app.log` に残す。
 * 車で何かあったときに、スマホの「ログ」画面から共有して原因を追えるようにするため。
 */
object AppLog {
    private const val MAX_LINES = 3000
    private const val MAX_FILE_BYTES = 1_000_000L

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var directory: File? = null
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        directory = File(app.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("Crash", "uncaught exception in ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        i("App", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append("W", tag, message + throwable.describe())
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, message + throwable.describe())
    }

    /** 新しい順ではなく時系列（古い→新しい）。 */
    fun recent(maxLines: Int = MAX_LINES): String = synchronized(lines) {
        lines.toList().takeLast(maxLines).joinToString("\n")
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        directory?.let { File(it, "app.log").delete() }
        i("App", "log cleared")
    }

    /** ログをファイルにして共有シートを開く。 */
    fun shareIntent(context: Context): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "autotube-log.txt")
        val fileLog = directory?.let { File(it, "app.log") }?.takeIf { it.exists() }?.readText().orEmpty()
        file.writeText(fileLog.takeLast(MAX_FILE_BYTES.toInt()) + "\n----- memory -----\n" + recent())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AutoTube log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun Throwable?.describe(): String {
        if (this == null) return ""
        val chain = generateSequence(this) { it.cause }.take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
        return " [$chain]"
    }

    private fun append(level: String, tag: String, message: String) {
        val line = "${timestamp.format(Date())} $level/$tag: $message"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        val dir = directory ?: return
        try {
            val file = File(dir, "app.log")
            if (file.length() > MAX_FILE_BYTES) {
                file.renameTo(File(dir, "app.1.log"))
            }
            file.appendText(line + "\n")
        } catch (_: Exception) {
            // ログの書き込み失敗で本体を止めない。
        }
    }
}
