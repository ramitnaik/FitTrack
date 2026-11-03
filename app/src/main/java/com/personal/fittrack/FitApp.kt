package com.personal.fittrack

import android.app.Application
import com.personal.fittrack.data.AppContainer

class FitApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
