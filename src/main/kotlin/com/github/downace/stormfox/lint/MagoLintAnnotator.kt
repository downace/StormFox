package com.github.downace.stormfox.lint

import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ModCommandAction
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.io.File

class MagoLintAnnotator : ExternalAnnotator<MagoLintAnnotator.Snapshot, MagoLintAnnotator.Report>() {
    data class Snapshot(
        val text: String,
        val logicalPath: String,
        val workingDirectory: File,
        val binary: String,
        val configPath: String,
    )

    class Report internal constructor(val snapshot: Snapshot, internal val result: MagoLintRunner.Result)

    override fun getPairedBatchInspectionShortName(): String = MagoLintInspection.SHORT_NAME

    override fun collectInformation(file: PsiFile): Snapshot? {
        if (inspection(file) == null) return null
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem || file.language.id != "PHP") return null
        val settings = MagoSettings.getInstance().state
        val workingDirectory = file.project.basePath?.let(::File) ?: File(virtualFile.path).parentFile ?: return null
        val text = PsiDocumentManager.getInstance(file.project).getDocument(file)?.text ?: file.text
        return Snapshot(text, virtualFile.path, workingDirectory, settings.magoPath.ifBlank { "mago" }, settings.configPath)
    }

    override fun doAnnotate(collectedInfo: Snapshot): Report = Report(
        collectedInfo,
        MagoLintRunner.lint(collectedInfo.binary, collectedInfo.workingDirectory, collectedInfo.configPath, collectedInfo.logicalPath, collectedInfo.text),
    )

    override fun apply(file: PsiFile, annotationResult: Report, holder: AnnotationHolder) {
        val currentText = PsiDocumentManager.getInstance(file.project).getDocument(file)?.text ?: file.text
        if (!isCurrent(annotationResult.snapshot, currentText)) return
        val (inspection, severity, key) = inspection(file) ?: return
        fun AnnotationBuilder.registerFix(action: ModCommandAction) {
            newFix(action).key(key).registerFix()
        }
        fun annotation(message: String, level: HighlightSeverity = severity) = holder.newAnnotation(level, "Mago Linter: $message")
        when (val result = annotationResult.result) {
            is MagoLintRunner.Result.Failure -> annotation(result.message).fileLevel().create()
            is MagoLintRunner.Result.Success -> {
                val batches = MagoLintBatchFixes.variants(currentText, result.diagnostics)
                val snapshotHash = MagoRelatedNavigation.snapshotHash(currentText)
                val ruleBatches = result.diagnostics.groupBy { it.code }.mapValues { (_, diagnostics) ->
                    MagoLintBatchFixes.variants(currentText, diagnostics)
                }
                fun batchAction(batch: MagoLintBatchFixes.Batch, label: String, options: List<IntentionAction> = emptyList(), showPreview: Boolean = false) =
                    MagoApplyLintFixAction(batch.fix, snapshotHash, file.virtualFile,
                        null, "", options, label, showPreview = showPreview, priority = PriorityAction.Priority.NORMAL)
                fun globalChildLabel(safety: MagoLintReport.Safety) = when (safety) {
                    MagoLintReport.Safety.SAFE -> "Fix all problems in file (safe only)"
                    MagoLintReport.Safety.POTENTIALLY_UNSAFE -> "Fix all problems in file (including potentially unsafe)"
                    MagoLintReport.Safety.UNSAFE -> "Fix all problems in file (including unsafe)"
                }
                val global = batches.firstOrNull()?.let { first ->
                    batchAction(first, MagoLintBatchFixes.globalLabel(first.fix.safety), batches.drop(1).map { batch ->
                        batchAction(batch, globalChildLabel(batch.fix.safety), showPreview = false).asIntention()
                    }, showPreview = false)
                }
                result.diagnostics.forEach {
                    val plainMessage = buildList {
                        add(it.message)
                        it.primaryMessage?.takeIf { detail -> detail.isNotBlank() && detail.trim() != it.message.trim() }
                            ?.let(::add)
                    }.joinToString("\n") + (it.code?.let { code -> " [$code]" } ?: "")
                    val builder = annotation(plainMessage,
                        inspection.severityFor(it.level, severity, file.project))
                        .range(it.range).tooltip(MagoLintTooltip.compact(it))
                    if (it.relatedLocations.any { location -> location.filePath != null && location.range != null }) {
                        builder.registerFix(MagoShowRelatedLocationsAction(it.relatedLocations, it.snapshotHash, file.virtualFile))
                    }
                    it.fix?.let { fix ->
                        val code = it.code?.takeIf { code -> code.isNotBlank() }
                        val sameRule = if (code == null) emptyList() else ruleBatches[code].orEmpty()
                        val options = sameRule.asReversed().map { batch ->
                            MagoApplyLintFixAction(batch.fix, snapshotHash, file.virtualFile, null, "",
                                baseLabel = "Fix all $code problems in file", showPreview = false,
                                priority = PriorityAction.Priority.NORMAL).asIntention()
                        }
                        builder.registerFix(MagoApplyLintFixAction(fix, it.snapshotHash, file.virtualFile, it.code, it.message,
                            options, priority = PriorityAction.Priority.HIGH))
                    }
                    global?.let { action -> builder.registerFix(action) }
                    builder.create()
                }
            }
        }
    }

    private fun inspection(file: PsiFile): Triple<MagoLintInspection, HighlightSeverity, HighlightDisplayKey>? {
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        val inspection = profile.getInspectionTool(MagoLintInspection.SHORT_NAME, file)?.tool as? MagoLintInspection ?: return null
        val key = HighlightDisplayKey.find(MagoLintInspection.SHORT_NAME) ?: return null
        return if (profile.isToolEnabled(key, file)) Triple(inspection, profile.getErrorLevel(key, file).severity, key) else null
    }

    companion object {
        internal fun isCurrent(snapshot: Snapshot, text: String): Boolean = snapshot.text == text
    }
}
