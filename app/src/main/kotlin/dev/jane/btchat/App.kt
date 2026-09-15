package dev.jane.btchat

import android.app.Application
import android.content.Context
import dev.jane.btchat.photo.AndroidPhotoStore
import dev.jane.btchat.photo.PhotoProcessor
import dev.jane.btchat.service.Notifications
import dev.jane.btchat.store.AppDatabase
import dev.jane.btchat.store.Settings

class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    lateinit var photoProcessor: PhotoProcessor
        private set
    lateinit var photoStore: AndroidPhotoStore
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.open(this)
        settings = Settings.forContext(this)
        photoProcessor = PhotoProcessor(this)
        photoStore = AndroidPhotoStore(this, photoProcessor)
        Notifications.createChannels(this)
    }

    companion object {
        fun get(context: Context): App = context.applicationContext as App
    }
}
