package com.homs.autoreply

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecorderAndStateTest {
    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** الاختبار 1: تسجيل الصوت وحفظه */
    @Test
    fun recordAndSave() {
        val r = VoiceRecorder(ctx)
        assertTrue(r.start())
        Thread.sleep(1500)
        assertTrue(r.stop())
        assertTrue(r.tempFile.length() > 0)
        assertTrue(r.save())
        assertTrue(r.savedFile.length() > 0)
    }

    /** الاختبار 2: حالة التفعيل والسجل تُحفظ */
    @Test
    fun enabledFlagAndLog() {
        AppState.init(ctx)
        AppState.setEnabled(true)
        assertTrue(AppState.isEnabled())
        AppState.setEnabled(false)
        assertTrue(!AppState.isEnabled())
        AppState.clearLog()
        AppState.addLog("اختبار")
        assertEquals("اختبار", AppState.log.value.first().result)
    }
}
