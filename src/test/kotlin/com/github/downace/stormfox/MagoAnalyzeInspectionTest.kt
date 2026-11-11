package com.github.downace.stormfox

import com.github.downace.stormfox.lint.*
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class MagoAnalyzeInspectionTest : BasePlatformTestCase() {
    fun testSeparateDisabledInspectionAndIndependentSeverityOverrides() {
        val lint = MagoLintInspection()
        val analyze = MagoAnalyzeInspection()
        assertFalse(analyze.isEnabledByDefault)
        assertEquals("MagoAnalyze", analyze.shortName)
        assertEquals("Mago Analyzer validation", analyze.displayName)
        lint.overrideSeverityLevels = true
        analyze.overrideSeverityLevels = true
        lint.errorSeverity = "WARNING"
        analyze.errorSeverity = "WEAK WARNING"
        assertEquals(HighlightSeverity.WARNING, lint.severityFor("Error", HighlightSeverity.ERROR, project))
        assertEquals(HighlightSeverity.WEAK_WARNING, analyze.severityFor("Error", HighlightSeverity.ERROR, project))
        analyze.noteSeverity = "INFORMATION"
        assertEquals(HighlightSeverity.INFORMATION, analyze.severityFor("Note", HighlightSeverity.ERROR, project))
        assertEquals("TYPO", lint.noteSeverity)
    }

    fun testAnalyzeArgumentsUseResolvedSourceWorkspace() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "mago-source")
        val path = File(workspace, "src/Sample.php").absolutePath
        assertEquals(listOf("mago", "--config", "custom.toml", "analyze", "--stdin-input", "--reporting-format", "json", "--", "src/Sample.php"),
            MagoDiagnosticRunner.arguments("mago", "custom.toml", path, workspace, MagoTool.ANALYZE))
        assertTrue(MagoTool.ANALYZE.timeoutMillis > 30_000)
    }

    fun testRepresentativeAnalyzeReportAndToolSpecificDocumentation() {
        val text = "<?php function answer(): int { return \"wrong\"; }"
        val json = """{"issues":[{"level":"Error","code":"invalid-return-statement","message":"Invalid return type for function `answer`: expected `int`, but found `string('wrong')`.","help":"Change the return value to match `int`, or update the function's return type declaration.","annotations":[{"message":"This has type `string('wrong')`","kind":"Primary","span":{"file_id":{"name":"Sample.php","size":50,"file_type":"Host"},"start":{"offset":38,"line":0},"end":{"offset":45,"line":0}}}]}]}"""
        val diagnostic = MagoDiagnosticReport.parse(json, text, "Sample.php", File("."), MagoTool.ANALYZE).single()
        assertEquals("invalid-return-statement", diagnostic.code)
        assertEquals("Error", diagnostic.level)
        assertTrue(MagoDiagnosticTooltip.compact(diagnostic).startsWith("<html><b>Mago</b>:"))
        val details = MagoDiagnosticTooltip.expanded(diagnostic)!!
        assertTrue(details.contains("/tools/analyzer/overview/"))
        assertFalse(details.contains("/linter/rules/"))
        assertTrue(MagoDiagnosticTooltip.expanded(diagnostic.copy(source = MagoTool.LINT))!!.contains("/linter/rules/#invalid-return-statement"))
    }

    fun testBatchRunsToCompletionWithoutAnEditorCacheEntry() {
        val path = Files.createTempFile("stormfox-analyze-", ".php")
        val settings = MagoSettings.getInstance().state
        val previous = settings.magoPath
        try {
            Files.writeString(path, "<?php echo 1;")
            myFixture.configureFromExistingVirtualFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!)
            settings.magoPath = "stormfox-intentionally-missing-binary"
            val manager = InspectionManager.getInstance(project) as InspectionManagerEx
            val context = manager.createNewGlobalContext(false)
            val profile = com.intellij.codeInspection.ex.InspectionProfileImpl("Batch analysis only")
            profile.disableAllTools(project)
            profile.addTool(project, com.intellij.codeInspection.ex.LocalInspectionToolWrapper(MagoAnalyzeInspection()), null)
            profile.enableTool(MagoAnalyzeInspection.SHORT_NAME, project)
            (context as com.intellij.codeInspection.ex.GlobalInspectionContextBase).setExternalProfile(profile)
            val inspection = profile
                .getInspectionTool(MagoAnalyzeInspection.SHORT_NAME, myFixture.file)!!.tool as MagoAnalyzeInspection
            val file = myFixture.file
            val descriptors = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                inspection.checkFile(file, context, manager)
            }).get(20, TimeUnit.SECONDS)
            assertEquals(1, descriptors.size)
            assertTrue(descriptors.single().descriptionTemplate.contains("Cannot start Mago"))
        } finally { settings.magoPath = previous; Files.deleteIfExists(path) }
    }

    fun testAnalyzerEditsUseTheSharedFixParser() {
        val json = """{"issues":[{"level":"Warning","code":"redundant-expression","message":"Redundant expression","edits":[[{"name":"Sample.php"},[{"range":{"start":0,"end":1},"new_text":[120],"safety":"safe"}]]],"annotations":[{"kind":"Primary","span":{"file_id":{"name":"Sample.php"},"start":{"offset":0},"end":{"offset":1}}}]}]}"""
        val diagnostic = MagoDiagnosticReport.parse(json, "abc", "Sample.php", null, MagoTool.ANALYZE).single()
        assertEquals(MagoTool.ANALYZE, diagnostic.source)
        assertEquals("xbc", diagnostic.fix!!.text)
        assertEquals(MagoDiagnosticReport.Safety.SAFE, diagnostic.fix!!.safety)
    }

    fun testEditorAnnotationQueuesWorkInsteadOfWaitingForATool() {
        val path = Files.createTempFile("stormfox-async-", ".php")
        try {
            Files.writeString(path, "<?php echo 1;")
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            myFixture.configureFromExistingVirtualFile(file)
            val annotator = MagoAnalyzeAnnotator()
            assertNull(annotator.collectInformation(myFixture.file))
            myFixture.enableInspections(MagoAnalyzeInspection())
            val snapshot = annotator.collectInformation(myFixture.file)!!
            assertNull(annotator.doAnnotate(snapshot))
            project.getService(MagoDiagnosticService::class.java).state.invalidate(file)
        } finally { Files.deleteIfExists(path) }
    }
}
