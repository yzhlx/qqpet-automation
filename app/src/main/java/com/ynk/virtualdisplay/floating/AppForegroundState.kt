package com.ynk.virtualdisplay.floating

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether the QQPet app itself is in the foreground.
 *
 * The in-app PiP already shows the preview while the app is open, so the cross-app capsule only
 * needs to float above OTHER apps. This mirrors how Mika hides its capsule in-app.
 */
object AppForegroundState : Application.ActivityLifecycleCallbacks {
    @Volatile
    var isForeground: Boolean = false
        private set

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        isForeground = true
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        isForeground = false
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
