package com.ynk.virtualdisplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every template the automation can load must exist in src/main/assets.
 *
 * 2026-11-06: build 1.0.195 shipped the probe PetPageProbes.qqSearchHistoryPet without its PNG.
 * TemplateMatcher reads the asset lazily, so the JVM suite stayed green while every real session
 * died on its first frame with FileNotFoundException (the asset path became the stop reason).
 * This test reads the probe declarations and every literal asset path in the main sources and
 * fails at build time when one of them has no file behind it.
 */
class PetProbeAssetsTest {
    private val sources = File("src/main/java/com/ynk/virtualdisplay")
    private val assets = File("src/main/assets")

    private fun kotlinFiles() = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** `experimental("name", ...)` / `mvp("name", ...)` calls in PetPageProbes. */
    private fun declaredProbeAssets(): Set<String> {
        val text = File(sources, "automation/PetPageProbes.kt").readText()
        val helper = Regex("""\b(experimental|mvp)\("([A-Za-z0-9_]+)"""")
        return helper.findAll(text).map { m ->
            val dir = if (m.groupValues[1] == "experimental") "pet_experimental" else "pet_mvp"
            dir + "/" + m.groupValues[2] + ".png"
        }.toSet()
    }

    /** Literal `"pet_xxx/name.png|txt"` asset paths anywhere in the main sources. */
    private fun literalAssets(): Set<String> {
        val literal = Regex(""""(pet_(?:mvp|experimental|health|exchange)/[A-Za-z0-9_]+\.(?:png|txt))"""")
        return kotlinFiles().flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
    }

    @Test fun sourcesAndAssetsAreWhereTheTestExpects() {
        assertTrue("run the JVM suite from the app module: " + sources.absolutePath, sources.isDirectory)
        assertTrue("assets folder missing: " + assets.absolutePath, assets.isDirectory)
    }

    @Test fun everyDeclaredProbeHasItsTemplate() {
        val declared = declaredProbeAssets()
        assertTrue("PetPageProbes declares at least the room probe", "pet_experimental/room.png" in declared)
        val missing = declared.filterNot { File(assets, it).isFile }.sorted()
        assertEquals("probe templates declared in PetPageProbes but missing from assets", emptyList<String>(), missing)
    }

    @Test fun everyLiteralAssetPathResolves() {
        val literal = literalAssets()
        assertTrue("the source scan found asset paths", literal.size >= 8)
        val missing = literal.filterNot { File(assets, it).isFile }.sorted()
        assertEquals("asset paths named in the sources but missing from assets", emptyList<String>(), missing)
    }

    @Test fun theQqEntryChainTemplatesAreShipped() {
        listOf("qq_message_search_box", "qq_message_search_box_compact", "qq_search_history_pet",
            "qq_host_search", "qq_search_pet_row").forEach {
            assertTrue("cold-start template missing: $it", File(assets, "pet_experimental/$it.png").isFile)
        }
    }
}
