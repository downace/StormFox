package com.github.downace.stormfox.lint

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

internal class MagoApplyLintFixAction(
    private val fix: MagoLintReport.Fix,
    private val snapshotHash: String,
    private val sourceFile: VirtualFile,
    code: String?,
    message: String,
    private val options: List<IntentionAction> = emptyList(),
    overrideLabel: String? = null,
    baseLabel: String = "Mago Linter: Fix ${code ?: message}",
    private val showPreview: Boolean = true,
    private val priority: PriorityAction.Priority = PriorityAction.Priority.HIGH,
) : ModCommandAction {
    internal val label = overrideLabel ?: (baseLabel + when (fix.safety) {
        MagoLintReport.Safety.SAFE -> ""
        MagoLintReport.Safety.POTENTIALLY_UNSAFE -> " (potentially unsafe)"
        MagoLintReport.Safety.UNSAFE -> " (unsafe)"
    })

    override fun getFamilyName(): String = "Mago Linter"

    override fun asIntention(): IntentionAction = MagoLintFixIntention(
        this,
        super<ModCommandAction>.asIntention(),
        label,
        label.removePrefix("Mago Linter: "),
        options,
        if (showPreview) null else IntentionPreviewInfo.EMPTY,
        priority,
    )

    private fun isCurrent(context: ActionContext): Boolean {
        if (context.file.virtualFile != sourceFile) return false
        val text = PsiDocumentManager.getInstance(context.project).getDocument(context.file)?.text ?: context.file.text
        return MagoRelatedNavigation.snapshotHash(text) == snapshotHash
    }

    override fun getPresentation(context: ActionContext): Presentation? =
        if (isCurrent(context)) Presentation.of(label) else null

    override fun generatePreview(context: ActionContext): IntentionPreviewInfo =
        if (showPreview) super<ModCommandAction>.generatePreview(context) else IntentionPreviewInfo.EMPTY

    override fun perform(context: ActionContext): ModCommand {
        if (!isCurrent(context)) return ModCommand.nop()
        return ModCommand.psiUpdate(context) { updater ->
            val file = updater.getWritable(context.file)
            file.viewProvider.document.setText(fix.text)
        }
    }
}
