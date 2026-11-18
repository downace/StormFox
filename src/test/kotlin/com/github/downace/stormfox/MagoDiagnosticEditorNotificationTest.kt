package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticActivity
import com.github.downace.stormfox.lint.MagoDiagnosticEditorNotificationProvider
import com.github.downace.stormfox.lint.MagoDiagnosticService
import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.github.downace.stormfox.lint.MagoTool
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorNotificationPanel

class MagoDiagnosticEditorNotificationTest : BasePlatformTestCase() {
    fun testShowsEveryToolFailureWithSettingsActionAndClearsWhenActivityClears() {
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
}
