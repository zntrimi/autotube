package dev.imaizentarou.autotube

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences の薄い包み。値は少ないので Flow にはせず、必要な場所で読む。 */
object Settings {
    private lateinit var preferences: SharedPreferences

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** 映像の高さの上限。360 / 720 / 1080。 */
    var maxHeight: Int
        get() = preferences.getInt("max_height", 720)
        set(value) = preferences.edit().putInt("max_height", value).apply()

    /** 車に繋いで AutoTube が開いたら、続きから自動で再生する。 */
    var autoPlayOnConnect: Boolean
        get() = preferences.getBoolean("auto_play_on_connect", true)
        set(value) = preferences.edit().putBoolean("auto_play_on_connect", value).apply()

    /** SponsorBlock で提供スポンサー枠などを飛ばす。 */
    var skipSponsors: Boolean
        get() = preferences.getBoolean("skip_sponsors", true)
        set(value) = preferences.edit().putBoolean("skip_sponsors", value).apply()

    /** 最後に再生していたキュー項目。次回の自動再生とスマホ側の表示に使う。 */
    var lastPlayedId: String?
        get() = preferences.getString("last_played_id", null)
        set(value) = preferences.edit().putString("last_played_id", value).apply()

    val maxHeightChoices = listOf(360, 720, 1080)
}
