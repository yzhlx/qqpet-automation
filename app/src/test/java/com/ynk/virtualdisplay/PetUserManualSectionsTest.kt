package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.ui.screens.ManualSection
import org.junit.Assert.*
import org.junit.Test

class PetUserManualSectionsTest {

    @Test
    fun manualSectionsCoverAllRequiredDomains() {
        val sections = ManualSection.entries.map { it.name }.toSet()
        val required = setOf(
            "INSTALL_AUTH",
            "CORE_ACTIONS",
            "PLAN_CONFIG",
            "PAUSE_RESUME",
            "TROUBLESHOOTING",
            "LIMITS",
        )
        assertEquals("使用攻略必须完整覆盖 6 大核心用户目标与场景", required, sections)
    }

    @Test
    fun manualSectionsHaveUserFacingTitlesAndGlyphs() {
        ManualSection.entries.forEach { sec ->
            assertFalse("攻略分类标题不能为空", sec.title.isBlank())
            assertNotNull("攻略分类需配置对应图标", sec.glyph)
        }
    }
}
