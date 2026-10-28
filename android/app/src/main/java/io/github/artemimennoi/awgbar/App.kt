package io.github.artemimennoi.awgbar

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Vpn.init(this)
    }
}
