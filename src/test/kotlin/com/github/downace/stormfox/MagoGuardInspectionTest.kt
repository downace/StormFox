package com.github.downace.stormfox

import com.github.downace.stormfox.lint.*
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class MagoGuardInspectionTest : BasePlatformTestCase() {
    fun testOptionalCliHonorsStructuralConfigurationAndReadsSnapshotWithoutWriting() {
        val binary = System.getenv("STORMFOX_MAGO_TEST_BINARY") ?: return
        val directory = Files.createTempDirectory("stormfox-guard-cli-")
        val sourceDirectory = Files.createDirectory(directory.resolve("src"))
        val path = sourceDirectory.resolve("Sample.php")
        val config = directory.resolve("custom.toml")
        val diskText = "<?php namespace App; final class Sample {}"
        try {
            Files.writeString(path, diskText)
            Files.writeString(config, """
                [source]
                paths = ['src']
                [[guard.structural.rules]]
                on = 'App\**'
                target = 'class'
                must-be-final = true
            """.trimIndent())
            val result = MagoDiagnosticRunner.run(binary, directory.toFile(), config.toString(), path.toString(),
                "<?php namespace App; class Sample {}", MagoTool.GUARD)
            assertTrue("Expected a valid JSON report, got $result", result is MagoDiagnosticRunner.Result.Success)
            val diagnostic = (result as MagoDiagnosticRunner.Result.Success).diagnostics.single()
            assertEquals("must-be-final", diagnostic.code)
            assertEquals("Error", diagnostic.level)
            assertEquals(MagoTool.GUARD, diagnostic.source)
            assertEquals(TextRange(27, 33), diagnostic.range)
            val clean = MagoDiagnosticRunner.run(binary, directory.toFile(), config.toString(), path.toString(), diskText, MagoTool.GUARD)
            assertTrue("Expected successful JSON despite stderr warnings, got $clean", clean is MagoDiagnosticRunner.Result.Success)
            assertEmpty((clean as MagoDiagnosticRunner.Result.Success).diagnostics)
            assertEquals(diskText, Files.readString(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(config)
            Files.deleteIfExists(sourceDirectory)
            Files.deleteIfExists(directory)
        }
    }

    fun testRegisteredDisabledWithIndependentProfileSeverity() {
        val guard = MagoGuardInspection()
        assertFalse(guard.isEnabledByDefault)
        assertEquals("MagoGuard", guard.shortName)
        assertEquals("Mago Guard validation", guard.displayName)
        assertEquals(listOf("PHP", "Quality tools"), guard.groupPath.toList())
        assertTrue(ExtensionPointName<com.intellij.codeInspection.LocalInspectionEP>("com.intellij.localInspection").extensionList.any {
            it.implementationClass == MagoGuardInspection::class.java.name
        })
        assertTrue(ExtensionPointName<com.intellij.lang.LanguageExtensionPoint<Any>>("com.intellij.externalAnnotator").extensionList.any {
            it.implementationClass == MagoGuardAnnotator::class.java.name
        })
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection(), guard)
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val tools = MagoTool.entries.associateWith {
            profile.getInspectionTool(it.shortName, myFixture.file)!!.tool as MagoDiagnosticInspection
        }
        tools.getValue(MagoTool.GUARD).apply {
            overrideSeverityLevels = true
            errorSeverity = "WEAK WARNING"
        }
        assertEquals(HighlightSeverity.WEAK_WARNING,
            tools.getValue(MagoTool.GUARD).severityFor("Error", HighlightSeverity.ERROR, project))
        for (tool in listOf(MagoTool.LINT, MagoTool.ANALYZE)) {
            assertFalse(tools.getValue(tool).overrideSeverityLevels)
            assertEquals(HighlightSeverity.ERROR, tools.getValue(tool).severityFor("Error", HighlightSeverity.ERROR, project))
        }
    }

    fun testArgumentsHonorConfiguredModesAndNeverWrite() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "mago-guard-source")
        val path = File(workspace, "app/Models/Shipment.php").absolutePath
        assertEquals(listOf("mago", "--config", "custom.toml", "guard", "--stdin-input", "--reporting-format", "json", "--", "app/Models/Shipment.php"),
            MagoDiagnosticRunner.arguments("mago", "custom.toml", path, workspace, MagoTool.GUARD))
        assertEquals(300_000, MagoTool.GUARD.timeoutMillis)
    }

    fun testRepresentativeGuardFixturePreservesLocationNotesAndDocumentation() {
        val text = " ".repeat(1009) + "Shipment" + " ".repeat(9634 - 1017)
        val json = """{"issues":[{"level":"Error","code":"must-be-final","message":"Structural flaw in `App\\Models\\Shipment`","notes":["Тест интеграции: все модели должны быть final."],"help":"Declare this class as `final`.","annotations":[{"message":"This must be declared as `final`","kind":"Primary","span":{"file_id":{"name":"app/Models/Shipment.php","size":9634,"file_type":"Host"},"start":{"offset":1009,"line":27},"end":{"offset":1017,"line":27}}}]}]}"""
        val diagnostic = MagoDiagnosticReport.parse(json, text, "app/Models/Shipment.php", null, MagoTool.GUARD).single()
        assertEquals(MagoTool.GUARD, diagnostic.source)
        assertEquals(TextRange(1009, 1017), diagnostic.range)
        assertEquals("Shipment", text.substring(diagnostic.range.startOffset, diagnostic.range.endOffset))
        assertEquals("must-be-final", diagnostic.code)
        assertEquals("Error", diagnostic.level)
        assertEquals("Structural flaw in `App\\Models\\Shipment`", diagnostic.message)
        assertEquals("This must be declared as `final`", diagnostic.primaryMessage)
        assertEquals(listOf("Тест интеграции: все модели должны быть final."), diagnostic.notes)
        assertEquals("Declare this class as `final`.", diagnostic.help)
        assertNull(diagnostic.fix)
        assertEmpty(diagnostic.relatedLocations)
        val details = MagoDiagnosticTooltip.expanded(diagnostic)!!
        assertTrue(details.contains("/tools/guard/overview/"))
        assertTrue(details.contains("Guard documentation"))
        assertFalse(details.contains("/tools/analyzer/"))
        assertFalse(details.contains("/linter/rules/"))
    }

    fun testBatchRunsToCompletionWithoutAnEditorCacheEntry() {
        val path = Files.createTempFile("stormfox-guard-", ".php")
        val settings = MagoSettings.getInstance().state
        val previous = settings.magoPath
        try {
            Files.writeString(path, "<?php echo 1;")
            myFixture.configureFromExistingVirtualFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!)
            settings.magoPath = "stormfox-intentionally-missing-binary"
            val manager = InspectionManager.getInstance(project) as InspectionManagerEx
            val context = manager.createNewGlobalContext(false)
            val profile = com.intellij.codeInspection.ex.InspectionProfileImpl("Batch guard only")
            profile.disableAllTools(project)
            profile.addTool(project, com.intellij.codeInspection.ex.LocalInspectionToolWrapper(MagoGuardInspection()), null)
            profile.enableTool(MagoGuardInspection.SHORT_NAME, project)
            (context as com.intellij.codeInspection.ex.GlobalInspectionContextBase).setExternalProfile(profile)
            val inspection = profile.getInspectionTool(MagoGuardInspection.SHORT_NAME, myFixture.file)!!.tool as MagoGuardInspection
            val file = myFixture.file
            val descriptors = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                inspection.checkFile(file, context, manager)
            }).get(20, TimeUnit.SECONDS)
            assertEquals(1, descriptors.size)
            assertTrue(descriptors.single().descriptionTemplate.contains("Cannot start Mago"))
        } finally { settings.magoPath = previous; Files.deleteIfExists(path) }
    }

    fun testEditorAnnotationQueuesSharedWorkerOnlyWhenEnabled() {
        val path = Files.createTempFile("stormfox-guard-async-", ".php")
        try {
            Files.writeString(path, "<?php echo 1;")
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            myFixture.configureFromExistingVirtualFile(file)
            val annotator = MagoGuardAnnotator()
            assertEquals(MagoGuardInspection.SHORT_NAME, annotator.pairedBatchInspectionShortName)
            assertNull(annotator.collectInformation(myFixture.file))
            myFixture.enableInspections(MagoGuardInspection())
            val snapshot = annotator.collectInformation(myFixture.file)!!
            assertNull(annotator.doAnnotate(snapshot))
            project.getService(MagoDiagnosticService::class.java).state.invalidate(file)
        } finally { Files.deleteIfExists(path) }
    }
}
