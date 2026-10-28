package dev.ggoggam.hangar

import android.app.Application
import dev.ggoggam.hangar.app.di.initHangar
import dev.ggoggam.hangar.app.session.androidPlatformModule

class HangarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initHangar(androidPlatformModule(this, R.drawable.ic_notification))
    }
}
