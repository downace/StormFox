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
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.codeInspection.InspectionProfile

open class MagoDiagnosticAnnotator(
    private val source: MagoTool = MagoTool.LINT,
    private val batch: Boolean = false,
    private val batchProfile: InspectionProfile? = null,
) : ExternalAnnotator<MagoDiagnosticAnnotator.Snapshot, MagoDiagnosticAnnotator.Report>() {
    data class Snapshot(
        val text: String,
        val logicalPath: String,
        val workingDirectory: File,
        val binary: String,
        val configPath: String,
        val revision: Long = -1,
        val project: Project? = null,
        val file: VirtualFile? = null,
        val generation: Long = 0,
        val environment: Map<String, String> = System.getenv(),
    )

    class Report internal constructor(val snapshot: Snapshot, internal val result: MagoDiagnosticRunner.Result)

    override fun getPairedBatchInspectionShortName(): String = source.shortName

    override fun collectInformation(file: PsiFile): Snapshot? {
        if (inspection(file, source, batchProfile) == null) return null
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem || file.language.id != "PHP") return null
        val settings = MagoSettings.getInstance().state
        val workingDirectory = file.project.basePath?.let(::File) ?: File(virtualFile.path).parentFile ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        val text = document?.text ?: file.text
        return Snapshot(text, virtualFile.path, workingDirectory, settings.magoPath.ifBlank { "mago" }, settings.configPath,
            document?.modificationStamp ?: file.modificationStamp, file.project, virtualFile,
            file.project.getService(MagoDiagnosticService::class.java).epoch)
    }

    override fun doAnnotate(collectedInfo: Snapshot): Report? {
        val result = if (batch) MagoDiagnosticRunner.run(collectedInfo.binary, collectedInfo.workingDirectory,
            collectedInfo.configPath, collectedInfo.logicalPath, collectedInfo.text, source)
        else {
            val project = collectedInfo.project ?: return null
            val file = collectedInfo.file ?: return null
            project.getService(MagoDiagnosticService::class.java).state.request(file, source, collectedInfo.workerSnapshot()) ?: return null
        }
        return Report(collectedInfo, result)
    }

    private fun Snapshot.workerSnapshot() = MagoSnapshot(revision, text, logicalPath, workingDirectory, binary, configPath, environment, generation)

    override fun apply(file: PsiFile, annotationResult: Report, holder: AnnotationHolder) {
        val currentText = PsiDocumentManager.getInstance(file.project).getDocument(file)?.text ?: file.text
        if (!isCurrent(annotationResult.snapshot, currentText)) return
        val snapshot = annotationResult.snapshot
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        if (snapshot.revision >= 0 && document?.modificationStamp != snapshot.revision) return
        if (snapshot.project != null) {
            val settings = MagoSettings.getInstance().state
            if (snapshot.binary != settings.magoPath.ifBlank { "mago" } || snapshot.configPath != settings.configPath ||
                snapshot.generation != file.project.getService(MagoDiagnosticService::class.java).epoch) return
        }
        val (inspection, severity, key) = inspection(file, source, batchProfile) ?: return
        fun AnnotationBuilder.registerFix(action: ModCommandAction) {
            newFix(action).key(key).registerFix()
        }
        fun annotation(message: String, level: HighlightSeverity = severity) = holder.newAnnotation(level, "Mago: $message")
        when (val result = annotationResult.result) {
            is MagoDiagnosticRunner.Result.Failure -> annotation(result.message).fileLevel().create()
            is MagoDiagnosticRunner.Result.Success -> {
                val batches = MagoDiagnosticBatchFixes.variants(currentText, result.diagnostics)
                val snapshotHash = MagoRelatedNavigation.snapshotHash(currentText)
                val ruleBatches = result.diagnostics.groupBy { it.code }.mapValues { (_, diagnostics) ->
                    MagoDiagnosticBatchFixes.variants(currentText, diagnostics)
                }
                fun batchAction(batch: MagoDiagnosticBatchFixes.Batch, label: String, options: List<IntentionAction> = emptyList(), showPreview: Boolean = false) =
                    MagoApplyFixAction(batch.fix, snapshotHash, file.virtualFile,
                        null, "", options, label, showPreview = showPreview, priority = PriorityAction.Priority.NORMAL)
                fun globalChildLabel(safety: MagoDiagnosticReport.Safety) = when (safety) {
                    MagoDiagnosticReport.Safety.SAFE -> "Fix all problems in file (safe only)"
                    MagoDiagnosticReport.Safety.POTENTIALLY_UNSAFE -> "Fix all problems in file (including potentially unsafe)"
                    MagoDiagnosticReport.Safety.UNSAFE -> "Fix all problems in file (including unsafe)"
                }
                val global = batches.firstOrNull()?.let { first ->
                    batchAction(first, MagoDiagnosticBatchFixes.globalLabel(first.fix.safety), batches.drop(1).map { batch ->
                        batchAction(batch, globalChildLabel(batch.fix.safety), showPreview = false).asIntention()
                    }, showPreview = false)
                }
                result.diagnostics.forEach {
                    val plainMessage = it.summary + (it.code?.let { code -> " [$code]" } ?: "")
                    val builder = annotation(plainMessage,
                        inspection.severityFor(it.level, severity, file.project))
                        .range(it.range).tooltip(MagoDiagnosticTooltip.compact(it))
                    if (it.relatedLocations.any { location -> location.filePath != null && location.range != null }) {
                        builder.registerFix(MagoShowRelatedLocationsAction(it.relatedLocations, it.snapshotHash, file.virtualFile))
                    }
                    it.fix?.let { fix ->
                        val code = it.code?.takeIf { code -> code.isNotBlank() }
                        val sameRule = if (code == null) emptyList() else ruleBatches[code].orEmpty()
                        val options = sameRule.asReversed().map { batch ->
                            MagoApplyFixAction(batch.fix, snapshotHash, file.virtualFile, null, "",
                                baseLabel = "Fix all $code problems in file", showPreview = false,
                                priority = PriorityAction.Priority.NORMAL).asIntention()
                        }
                        builder.registerFix(MagoApplyFixAction(fix, it.snapshotHash, file.virtualFile, it.code, it.message,
                            options, priority = PriorityAction.Priority.HIGH))
                    }
                    global?.let { action -> builder.registerFix(action) }
                    builder.create()
                }
            }
        }
    }

    companion object {
    internal fun inspection(file: PsiFile, source: MagoTool, selectedProfile: InspectionProfile? = null): Triple<MagoDiagnosticInspection, HighlightSeverity, HighlightDisplayKey>? {
        val profile = selectedProfile ?: InspectionProjectProfileManager.getInstance(file.project).currentProfile
        val inspection = profile.getInspectionTool(source.shortName, file)?.tool as? MagoDiagnosticInspection ?: return null
        val key = HighlightDisplayKey.find(source.shortName) ?: return null
        return if (profile.isToolEnabled(key, file)) Triple(inspection, profile.getErrorLevel(key, file).severity, key) else null
    }

        internal fun isCurrent(snapshot: Snapshot, text: String): Boolean = snapshot.text == text
    }
}

class MagoLintAnnotator : MagoDiagnosticAnnotator(MagoTool.LINT)
class MagoAnalyzeAnnotator : MagoDiagnosticAnnotator(MagoTool.ANALYZE)
class MagoGuardAnnotator : MagoDiagnosticAnnotator(MagoTool.GUARD)
