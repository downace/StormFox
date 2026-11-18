package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.lint.MagoTwigLintInspection
import com.github.downace.stormfox.lint.MagoTwigLintAnnotator
import com.github.downace.stormfox.lint.MagoDiagnosticService
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.lang.Language
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MagoTwigEditorIntegrationTest {
    @Test fun daemonAndEditorReformat() {
        val binary = System.getenv("STORMFOX_MAGO_TWIG_TEST_BINARY")
        assumeTrue("STORMFOX_MAGO_TWIG_TEST_BINARY is not set", !binary.isNullOrBlank())
        object : Fixture(binary!!) {}.apply { name = "testEditor" }.runBare()
    }

    abstract class Fixture(private val binary: String) : BasePlatformTestCase() {
        fun testEditor() {
            val settings = MagoSettings.getInstance().state
            val previousPath = settings.magoPath
            val previousConfig = settings.configPath
            val previousRoot = settings.executionRoot
            val previousEnabled = settings.enabled
            val previousTwigEnabled = settings.twigEnabled
            val workspace = Files.createTempDirectory("stormfox-twig-editor-").toFile()
            try {
                val original = "{{  dump(value)  }}"
                val source = File(workspace, "editor.twig").apply { writeText(original) }
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source.toPath())!!
                val config = File(workspace, "mago.toml").apply {
                    writeText("[source]\nworkspace = '${workspace.path.replace('\\', '/')}'\npaths = ['.']\n")
                }
                settings.magoPath = binary
                settings.configPath = config.path
                settings.executionRoot = ""
                settings.enabled = true
                settings.twigEnabled = true
                val versions = MagoVersionService.getInstance()
                val directory = project.basePath?.let(::File) ?: workspace
                val token = versions.beginValidation(binary, directory)!!
                versions.finish(token, binary, MagoValidation.Result.Success("mago 1.60.0"), directory)
                myFixture.enableInspections(MagoTwigLintInspection())
                myFixture.configureFromExistingVirtualFile(virtualFile)
                println("Twig integration PSI language=${myFixture.file.language.id}, baseLanguage=${myFixture.file.viewProvider.baseLanguage.id}, fileType=${virtualFile.fileType.name}")
                val annotator = ExtensionPointName<LanguageExtensionPoint<Any>>("com.intellij.externalAnnotator").extensionList
                    .single { it.implementationClass == MagoTwigLintAnnotator::class.java.name }
                assertEquals(Language.ANY.id, annotator.language)
                var findings = 0
                for (attempt in 1..100) {
                    findings = myFixture.doHighlighting().count { it.description?.contains("twig-no-dump") == true }
                    if (findings > 0) break
                    val activity = project.getService(MagoDiagnosticService::class.java).activity
                    for (wait in 1..200) {
                        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                        val state = activity.snapshot()
                        if (state.queued.isEmpty() && state.running.isEmpty()) break
                        Thread.sleep(20)
                    }
                    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                }
                assertEquals("Expected exactly one twig-no-dump finding for ${myFixture.file.language.id}", 1, findings)
                myFixture.performEditorAction("ReformatCode")
                for (attempt in 1..100) {
                    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                    if (myFixture.editor.document.text != original) break
                    Thread.sleep(20)
                }
                assertTrue("Registered formatter did not format Twig", original != myFixture.editor.document.text)
                assertEquals(original, String(virtualFile.contentsToByteArray(), Charsets.UTF_8))
            } finally {
                settings.magoPath = previousPath
                settings.configPath = previousConfig
                settings.executionRoot = previousRoot
                settings.enabled = previousEnabled
                settings.twigEnabled = previousTwigEnabled
                workspace.deleteRecursively()
            }
        }
    }
}
