package dev.imaizentarou.autotube

import android.app.Application
import dev.imaizentarou.autotube.player.PlayerEngine
import dev.imaizentarou.autotube.queue.QueueStore
import dev.imaizentarou.autotube.youtube.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class AutoTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)
        NewPipe.init(OkHttpDownloader(), Localization("ja", "JP"), ContentCountry("JP"))
        Settings.initialize(this)
        QueueStore.initialize(this)
        PlayerEngine.initialize(this)
    }
}
