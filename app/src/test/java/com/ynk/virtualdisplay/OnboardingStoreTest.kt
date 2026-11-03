package com.ynk.virtualdisplay

import android.content.SharedPreferences
import com.ynk.virtualdisplay.ui.screens.OnboardingProgress
import com.ynk.virtualdisplay.ui.screens.OnboardingStep
import com.ynk.virtualdisplay.ui.screens.OnboardingStore
import org.junit.Assert.*
import org.junit.Test

class OnboardingStoreTest {

    private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { pending[key] = values?.toSet(); return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removes.add(key); return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) data.clear()
            removes.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }

    private class FakeSharedPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (map[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    @Test
    fun defaultStoreHasEmptyProgress() {
        val prefs = FakeSharedPrefs()
        val store = OnboardingStore(prefs)
        val p = store.load()
        assertFalse(p.welcomed)
        assertFalse(p.completed)
        assertFalse(p.previewMode)
        assertFalse(p.tutorialCompleted)
        assertTrue(p.setAsideSteps.isEmpty())
        assertTrue(p.userConfirmedKeys.isEmpty())
    }

    @Test
    fun saveWelcomedAndSetAsidePersistsAcrossReloads() {
        val prefs = FakeSharedPrefs()
        val store = OnboardingStore(prefs)
        store.saveWelcomed(true)
        store.setAside(OnboardingStep.OVERLAY)
        store.setAside(OnboardingStep.NOTIFICATION)

        val reloaded = OnboardingStore(prefs).load()
        assertTrue(reloaded.welcomed)
        assertEquals(setOf(OnboardingStep.OVERLAY, OnboardingStep.NOTIFICATION), reloaded.setAsideSteps)
        assertFalse(reloaded.completed)
    }

    @Test
    fun saveCompletedMarksCompletedAndWelcomed() {
        val prefs = FakeSharedPrefs()
        val store = OnboardingStore(prefs)
        store.savePreviewMode(true)
        store.saveCompleted(true)

        val p = store.load()
        assertTrue(p.completed)
        assertTrue(p.welcomed)
        assertFalse("完成引导后应自动解除预览模式", p.previewMode)
    }

    @Test
    fun saveTutorialCompletedPersists() {
        val prefs = FakeSharedPrefs()
        val store = OnboardingStore(prefs)
        store.saveTutorialCompleted(true)

        val p = store.load()
        assertTrue(p.tutorialCompleted)
    }

    @Test
    fun confirmKeySavesKeyAndPersists() {
        val prefs = FakeSharedPrefs()
        val store = OnboardingStore(prefs)
        store.confirmKey("developer_options")
        store.confirmKey("wireless_debug")

        val p = store.load()
        assertTrue(p.userConfirmedKeys.contains("developer_options"))
        assertTrue(p.userConfirmedKeys.contains("wireless_debug"))
    }

    @Test
    fun resetClearsAllOnboardingProgress() {
        val prefs = FakeSharedPrefs()
        val store = OnboardingStore(prefs)
        store.saveCompleted(true)
        store.confirmKey("developer_options")
        store.saveTutorialCompleted(true)

        store.reset()

        val p = store.load()
        assertFalse(p.completed)
        assertFalse(p.welcomed)
        assertFalse(p.tutorialCompleted)
        assertTrue(p.userConfirmedKeys.isEmpty())
    }
}
