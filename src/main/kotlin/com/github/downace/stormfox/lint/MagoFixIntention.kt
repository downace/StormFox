package com.github.downace.stormfox.lint

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.codeInsight.intention.CustomizableIntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.modcommand.ModCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

internal class MagoFixIntention(
    private val action: ModCommandAction,
    private val delegate: IntentionAction,
    private val label: String,
    private val tooltipText: String,
    private val options: List<IntentionAction> = emptyList(),
    private val preview: IntentionPreviewInfo? = null,
    private val priority: PriorityAction.Priority = PriorityAction.Priority.NORMAL,
) : IntentionAction, IntentionActionWithOptions, PriorityAction, CustomizableIntentionAction, Iconable {
    override fun getIcon(flags: Int) = AllIcons.Actions.IntentionBulb

    override fun getText(): String = label

    override fun getTooltipText(): String = tooltipText

    override fun getFamilyName(): String = action.familyName

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile): Boolean =
        delegate.isAvailable(project, editor, file)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile) = delegate.invoke(project, editor, file)

    override fun startInWriteAction(): Boolean = delegate.startInWriteAction()

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        preview ?: delegate.generatePreview(project, editor, file)

    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? =
        delegate.getElementToMakeWritable(currentFile)

    override fun getOptions(): List<IntentionAction> = options

    override fun getCombiningPolicy(): IntentionActionWithOptions.CombiningPolicy =
        if (options.isEmpty()) IntentionActionWithOptions.CombiningPolicy.InspectionOptionsOnly
        else IntentionActionWithOptions.CombiningPolicy.IntentionOptionsOnly

    override fun getPriority(): PriorityAction.Priority = priority

    override fun asModCommandAction(): ModCommandAction = action
}
