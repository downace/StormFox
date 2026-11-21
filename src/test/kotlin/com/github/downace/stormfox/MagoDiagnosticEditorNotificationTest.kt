package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticActivity
import com.github.downace.stormfox.lint.MagoDiagnosticEditorNotificationProvider
import com.github.downace.stormfox.lint.MagoDiagnosticService
import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.github.downace.stormfox.lint.MagoTool
import com.github.downace.stormfox.lint.MagoLintInspection
import com.github.downace.stormfox.lint.MagoAnalyzeInspection
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorNotificationPanel

class MagoDiagnosticEditorNotificationTest : BasePlatformTestCase() {
    fun testShowsEveryToolFailureWithSettingsActionAndClearsWhenActivityClears() {
        myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection())
        val psiFile = myFixture.addFileToProject("src/Sample.php", "<?php echo 1;")
        val file = psiFile.virtualFile
        val service = project.getService(MagoDiagnosticService::class.java)
        val activity = service.activity
        activity.begin(MagoTool.LINT, file.path, 1)
        activity.finish(MagoTool.LINT, file.path, 1, MagoDiagnosticRunner.Result.Failure("Invalid command"))
        activity.begin(MagoTool.ANALYZE, file.path, 2)
        activity.finish(MagoTool.ANALYZE, file.path, 2, MagoDiagnosticRunner.Result.Failure("Could not start"))

        val provider = MagoDiagnosticEditorNotificationProvider()
        val data = provider.collectNotificationData(project, file)
        assertNotNull(data)
        val editor = FileEditorManager.getInstance(project).openFile(file, true).firstOrNull()
        assertNotNull(editor)
        val panel = data!!.apply(editor!!) as EditorNotificationPanel
        assertTrue(panel.text.contains("Mago Linter validation: Invalid command"))
        assertTrue(panel.text.contains("Mago Analyzer validation: Could not start"))
        val action = panel.findLabelByName("Open settings")
        assertNotNull(action)
        assertEquals("Open settings", action!!.text)
        activity.clear()
        assertNull(provider.collectNotificationData(project, file))
        val otherFile = myFixture.addFileToProject("Other.php", "<?php echo 2;").virtualFile
        assertNull(provider.collectNotificationData(project, otherFile))
    }

    fun testDisabledInspectionFailureIsHiddenAndReturnsWhenEnabled() {
        myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection())
        val file = myFixture.addFileToProject("src/Failures.php", "<?php echo 1;").virtualFile
        val activity = project.getService(MagoDiagnosticService::class.java).activity
        activity.begin(MagoTool.LINT, file.path, 1)
        activity.finish(MagoTool.LINT, file.path, 1, MagoDiagnosticRunner.Result.Failure("lint failure"))
        activity.begin(MagoTool.ANALYZE, file.path, 2)
        activity.finish(MagoTool.ANALYZE, file.path, 2, MagoDiagnosticRunner.Result.Failure("analyze failure"))
        val provider = MagoDiagnosticEditorNotificationProvider()
        val editor = FileEditorManager.getInstance(project).openFile(file, true).first()
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as InspectionProfileImpl
        try {
            profile.setToolEnabled(MagoTool.LINT.shortName, false, project)
            val panel = provider.collectNotificationData(project, file)!!.apply(editor) as EditorNotificationPanel
            assertFalse(panel.text.contains("lint failure"))
            assertTrue(panel.text.contains("analyze failure"))
            profile.setToolEnabled(MagoTool.ANALYZE.shortName, false, project)
            assertNull(provider.collectNotificationData(project, file))
            profile.setToolEnabled(MagoTool.LINT.shortName, true, project)
            val restored = provider.collectNotificationData(project, file)!!.apply(editor) as EditorNotificationPanel
            assertTrue(restored.text.contains("lint failure"))
            assertFalse(restored.text.contains("analyze failure"))
        } finally {
            profile.setToolEnabled(MagoTool.LINT.shortName, true, project)
            profile.setToolEnabled(MagoTool.ANALYZE.shortName, true, project)
        }
    }
}
