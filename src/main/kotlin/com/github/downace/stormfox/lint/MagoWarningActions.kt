package com.github.downace.stormfox.lint

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.ex.EditInspectionToolsSettingsAction
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile

internal abstract class MagoWarningAction(
    protected val tool: MagoTool,
    private val label: String,
) : IntentionAction {
    override fun getText(): String = label
    override fun getFamilyName(): String = label
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = !project.isDisposed
    override fun startInWriteAction(): Boolean = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY
}

internal class MagoWarningOpenConfigAction(tool: MagoTool) : MagoWarningAction(tool, "Open Mago config") {
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        project.getService(MagoConfigNavigationService::class.java).open(tool.command, null)
    }
}

internal class MagoWarningInspectionSettingsAction(tool: MagoTool) :
    MagoWarningAction(tool, "Open inspection settings") {
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        EditInspectionToolsSettingsAction.editToolSettings(
            project, InspectionProjectProfileManager.getInstance(project).currentProfile, tool.shortName)
    }
}

internal class MagoWarningDisableInspectionAction(tool: MagoTool) :
    MagoWarningAction(tool, "Disable inspection") {
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as? InspectionProfileImpl ?: return
        profile.setToolEnabled(tool.shortName, false, project)
    }
}
