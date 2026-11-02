package dev.localai.chat

import android.app.Application
import dev.localai.chat.data.AppContainer

class LocalAiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
