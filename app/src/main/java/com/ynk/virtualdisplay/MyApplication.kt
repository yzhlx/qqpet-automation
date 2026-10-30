package com.ynk.virtualdisplay

import android.app.Application
import android.util.Log
import com.ynk.virtualdisplay.floating.AppForegroundState
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.ShizukuDisplayRepository

class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"
    }

    lateinit var displayRepository: IDisplayRepository
        private set

    private var repositoryLeaseCount: Int = 0

    @Synchronized
    fun acquireDisplayRepository(owner: String) {
        repositoryLeaseCount += 1
        Log.i(TAG, "repository lease +1 owner=$owner count=$repositoryLeaseCount")
        if (repositoryLeaseCount == 1) displayRepository.bindService(this)
    }

    @Synchronized
    fun releaseDisplayRepository(owner: String) {
        if (repositoryLeaseCount <= 0) {
            Log.w(TAG, "repository lease underflow owner=$owner")
            repositoryLeaseCount = 0
            return
        }
        repositoryLeaseCount -= 1
        Log.i(TAG, "repository lease -1 owner=$owner count=$repositoryLeaseCount")
        if (repositoryLeaseCount == 0) displayRepository.unbindService()
    }

    override fun onCreate() {
        super.onCreate()
        // Lets the overlay capsule know when QQPet itself is visible.
        registerActivityLifecycleCallbacks(AppForegroundState)
        Log.i(TAG, "MyApplication onCreate, initializing ShizukuDisplayRepository")
        displayRepository = ShizukuDisplayRepository(this)
    }
}
