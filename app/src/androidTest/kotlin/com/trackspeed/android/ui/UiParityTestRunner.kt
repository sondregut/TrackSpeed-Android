package com.trackspeed.android.ui

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** UI-only fixtures: never initialize analytics, billing or cloud upload services. */
class UiParityTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
