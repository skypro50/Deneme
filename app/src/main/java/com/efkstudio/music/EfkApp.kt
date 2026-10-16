package com.efkstudio.music

import android.app.Application

class EfkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Notifications.createChannels(this)
        Library.init(this)
        Engine.init(this)
    }
}
