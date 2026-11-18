package com.github.downace.stormfox.lint

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** A deliberately conservative edit that keeps comments inside PHP and out of string literals. */
internal class MagoSuppressAction(
    private val diagnostic: MagoDiagnosticReport.Diagnostic,
    private val sourceFile: VirtualFile,
    private val mode: Mode,
    private val options: List<IntentionAction> = emptyList(),
    private val primary: Boolean = false,
) : ModCommandAction {
    enum class Mode(val pragma: String) { EXPECT("expect"), IGNORE("ignore") }
    private val label = (if (primary) "Mago: " else "") + "Suppress with @mago-${mode.pragma}"
    override fun getFamilyName(): String = "Mago"
    override fun asIntention(): IntentionAction = MagoFixIntention(
        this, super<ModCommandAction>.asIntention(), label, label.removePrefix("Mago: "), options,
        priority = PriorityAction.Priority.NORMAL,
    )

    private fun edit(file: PsiFile): Pair<Int, String>? {
        if (file.virtualFile != sourceFile) return null
        val text = PsiDocumentManager.getInstance(file.project).getDocument(file)?.text ?: file.text
        if (MagoRelatedNavigation.snapshotHash(text) != diagnostic.snapshotHash) return null
        return plan(text, diagnostic, mode, docblockStart = { end ->
            val leaf = file.findElementAt(end - 1)
            generateSequence(leaf) { it.parent }.firstOrNull {
                it.language.id == "PHP" && it.textRange.endOffset == end &&
                    it.text.startsWith("/**") && it.text.endsWith("*/")
            }?.textRange?.startOffset
        }) { probe ->
            val element = file.findElementAt(probe) ?: return@plan false
            val type = element.node.elementType.toString()
            element.language.id == "PHP" && element.textRange.startOffset >= probe &&
                !type.contains("COMMENT", ignoreCase = true) && !type.contains("STRING", ignoreCase = true)
        }
    }

    companion object {
        fun validCode(code: String?): Boolean =
            code != null && code != "all" && code.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))

        internal fun plan(
            text: String,
            diagnostic: MagoDiagnosticReport.Diagnostic,
            mode: Mode,
            docblockStart: (Int) -> Int? = { null },
            safePsi: (Int) -> Boolean,
        ): Pair<Int, String>? {
            val code = diagnostic.code
            if (!validCode(code)) return null
            val offset = diagnostic.range.startOffset
            if (offset !in text.indices) return null
            val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd).removeSuffix("\r")
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val first = lineStart + indent.length
            if (first >= lineEnd || offset < first) return null
            val content = line.substring(indent.length)
            val inlineOpening = content.startsWith("<?php") && content.length > 5 && content[5] in " \t"
            var insertion = if (inlineOpening) first + 5 else lineStart
            var insertionIndent = indent
            val probe = if (inlineOpening) {
                var i = insertion
                while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
                i
            } else first
            if (probe >= lineEnd || offset < probe || line.contains("?>") || line.contains("<?", ignoreCase = true) && !inlineOpening) return null
            if (!safePsi(probe)) return null
            val category = when (diagnostic.source) {
                MagoTool.LINT -> "lint"
                MagoTool.ANALYZE -> "analysis"
                MagoTool.GUARD -> "guard"
                MagoTool.TWIG_LINT -> return null
            }
            if (lineStart > 0) {
                val previousEnd = lineStart - 1
                val previousStart = text.lastIndexOf('\n', (previousEnd - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                val previous = text.substring(previousStart, previousEnd).removeSuffix("\r")
                val trimmed = previous.trimEnd()
                if (trimmed.endsWith("*/")) {
                    val end = previousStart + trimmed.length
                    val start = docblockStart(end) ?: return null
                    val startLine = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                    val before = text.substring(startLine, start)
                    if (before.any { it != ' ' && it != '\t' }) return null
                    insertion = startLine
                    insertionIndent = before
                }
            }
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            val comment = "// @mago-${mode.pragma} $category:$code"
            return if (inlineOpening) insertion to "$newline$indent$comment$newline$indent"
            else insertion to "$insertionIndent$comment$newline"
        }
    }

    override fun getPresentation(context: ActionContext): Presentation? =
        if (edit(context.file) != null) Presentation.of(label) else null

    override fun perform(context: ActionContext): ModCommand {
        val (offset, insertion) = edit(context.file) ?: return ModCommand.nop()
        return ModCommand.psiUpdate(context) { updater ->
            updater.getWritable(context.file).viewProvider.document.insertString(offset, insertion)
        }
    }
}
