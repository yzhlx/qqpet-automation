package com.ynk.virtualdisplay.automation

import android.content.Context
import android.provider.Settings

/** 设备身份：只有同一启动周期、同一安装版本内的能力检测结果才可复用。 */
data class EnvironmentIdentity(
    val bootCount: Int,
    val appUpdatedAt: Long,
) {
    val isStable: Boolean get() = bootCount >= 0 && appUpdatedAt > 0

    companion object {
        val UNKNOWN = EnvironmentIdentity(bootCount = -1, appUpdatedAt = -1L)

        fun read(context: Context): EnvironmentIdentity {
            val bootCount = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1,
            )
            val updatedAt = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(-1L)
            return EnvironmentIdentity(bootCount = bootCount, appUpdatedAt = updatedAt)
        }
    }
}

data class CapabilityCheckPass(
    val bootCount: Int,
    val appUpdatedAt: Long,
    val passedAt: Long,
)

/** 能力检测通过记录。只在设备重启、应用升级或服务断连后失效。 */
interface CapabilityCheckCache {
    fun load(): CapabilityCheckPass?
    fun save(pass: CapabilityCheckPass)
    fun clear()
}

class SharedPreferencesCapabilityCheckCache(context: Context) : CapabilityCheckCache {
    private val prefs = context.getSharedPreferences("crab_capability_check", Context.MODE_PRIVATE)

    override fun load(): CapabilityCheckPass? {
        if (!prefs.contains(KEY_PASSED_AT)) return null
        return CapabilityCheckPass(
            bootCount = prefs.getInt(KEY_BOOT_COUNT, -1),
            appUpdatedAt = prefs.getLong(KEY_APP_UPDATED_AT, -1L),
            passedAt = prefs.getLong(KEY_PASSED_AT, 0L),
        )
    }

    override fun save(pass: CapabilityCheckPass) {
        prefs.edit()
            .putInt(KEY_BOOT_COUNT, pass.bootCount)
            .putLong(KEY_APP_UPDATED_AT, pass.appUpdatedAt)
            .putLong(KEY_PASSED_AT, pass.passedAt)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_APP_UPDATED_AT = "app_updated_at"
        const val KEY_PASSED_AT = "passed_at"
    }
}
