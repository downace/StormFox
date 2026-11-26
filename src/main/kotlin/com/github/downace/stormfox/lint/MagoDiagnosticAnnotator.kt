package com.github.downace.stormfox.lint

import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.settings.MagoDiagnosticOptions
import com.github.downace.stormfox.MagoVersionService
import com.github.downace.stormfox.isTwigFile
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
        val executionRoot: String = "",
        val options: MagoDiagnosticOptions = MagoDiagnosticOptions(),
    )

    class Report internal constructor(val snapshot: Snapshot, internal val result: MagoDiagnosticRunner.Result,
                                      internal val retained: Boolean = false,
                                      internal val view: MagoDiagnosticState.View? = null)

    override fun getPairedBatchInspectionShortName(): String = source.shortName

    override fun collectInformation(file: PsiFile): Snapshot? {
        if (inspection(file, source, batchProfile) == null) return null
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem) return null
        val twig = isTwigFile(file)
        if (source == MagoTool.TWIG_LINT) {
            if (!twig || file.language != file.viewProvider.baseLanguage) return null
        } else if (twig || file.language.id != "PHP") return null
        val settings = MagoSettings.getInstance(file.project).state
        val workingDirectory = file.project.basePath?.let(::File) ?: File(virtualFile.path).parentFile ?: return null
        if (source == MagoTool.TWIG_LINT && !batch) {
            file.project.getService(MagoDiagnosticService::class.java)
            val versions = MagoVersionService.getInstance(file.project)
            versions.ensureInitialized(workingDirectory)
            if (versions.cachedVersion(settings.magoPath.ifBlank { "mago" }, workingDirectory, settings.executionRoot)?.supportsTwig != true) return null
        }
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        val text = document?.text ?: file.text
        return Snapshot(text, virtualFile.path, workingDirectory, settings.magoPath.ifBlank { "mago" }, settings.configPath,
            document?.modificationStamp ?: file.modificationStamp, file.project, virtualFile,
            file.project.getService(MagoDiagnosticService::class.java).epoch, executionRoot = settings.executionRoot,
            options = settings.diagnosticOptions())
    }

    override fun doAnnotate(collectedInfo: Snapshot): Report? {
        if (source == MagoTool.LINT) {
            collectedInfo.project?.getService(MagoRuleMetadataService::class.java)
                ?.request(collectedInfo.binary, collectedInfo.workingDirectory, collectedInfo.executionRoot)
        }
        if (batch) return Report(collectedInfo, MagoDiagnosticRunner.run(collectedInfo.binary, collectedInfo.workingDirectory,
            collectedInfo.configPath, collectedInfo.logicalPath, collectedInfo.text, source, executionRoot = collectedInfo.executionRoot,
            options = collectedInfo.options))
        else {
            val project = collectedInfo.project ?: return null
            val service = project.getService(MagoDiagnosticService::class.java)
            return service.withCurrentReport(collectedInfo, source) { it }
        }
    }

    override fun apply(file: PsiFile, annotationResult: Report, holder: AnnotationHolder) {
        val input = annotationResult.snapshot
        if (!batch && input.project != null) {
            if (input.file != file.virtualFile || input.project !== file.project) return
            input.project.getService(MagoDiagnosticService::class.java).withCurrentReport(input, source) {
                render(file, it, holder)
            }
            return
        }
        render(file, annotationResult, holder)
    }

    private fun render(file: PsiFile, annotationResult: Report, holder: AnnotationHolder) {
        val snapshot = annotationResult.snapshot
        val service = snapshot.project?.getService(MagoDiagnosticService::class.java)
        val metadata = if (source == MagoTool.LINT) snapshot.project?.getService(MagoRuleMetadataService::class.java) else null
        fun rule(code: String?) = metadata?.cached(code, snapshot.binary, snapshot.workingDirectory, snapshot.executionRoot)
        val details = "file=${snapshot.logicalPath} tool=$source revision=${snapshot.revision} generation=${snapshot.generation} slot=${annotationResult.view?.slotId ?: "-"} retained=${annotationResult.retained}"
        fun skip(reason: String) { service?.trace("apply-skip $details reason=$reason") }
        val currentText = PsiDocumentManager.getInstance(file.project).getDocument(file)?.text ?: file.text
        if (!isCurrent(snapshot, currentText)) { skip("text"); return }
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        if (snapshot.revision >= 0 && document?.modificationStamp != snapshot.revision) { skip("revision"); return }
        if (snapshot.project != null) {
            val settings = MagoSettings.getInstance(file.project).state
            if (snapshot.binary != settings.magoPath.ifBlank { "mago" } || snapshot.configPath != settings.configPath ||
                snapshot.executionRoot != settings.executionRoot || snapshot.options != settings.diagnosticOptions() ||
                snapshot.generation != file.project.getService(MagoDiagnosticService::class.java).epoch) { skip("settings-or-generation"); return }
        }
        val (inspection, severity, key) = inspection(file, source, batchProfile) ?: run { skip("inspection-disabled"); return }
        val status = when (val result = annotationResult.result) {
            is MagoDiagnosticRunner.Result.Success -> "success count=${result.diagnostics.size}"
            is MagoDiagnosticRunner.Result.Failure -> "failure"
        }
        service?.trace("apply $details $status")
        fun AnnotationBuilder.registerFix(action: ModCommandAction) {
            newFix(action).key(key).registerFix()
        }
        fun AnnotationBuilder.registerFix(action: IntentionAction) {
            newFix(action).key(key).registerFix()
        }
        fun annotation(message: String, level: HighlightSeverity = severity) = holder.newAnnotation(level, "Mago: $message")
        when (val result = annotationResult.result) {
            is MagoDiagnosticRunner.Result.Failure -> {
                // Editor failures are surfaced by MagoDiagnosticEditorNotificationProvider.
                // Keep the file-level diagnostic for batch inspection results.
                if (batch) annotation(result.message).fileLevel().create()
            }
            is MagoDiagnosticRunner.Result.Success -> {
                result.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                    val message = warnings.joinToString(" ")
                    val builder = annotation(message, HighlightSeverity.WARNING).fileLevel()
                    builder.registerFix(MagoWarningOpenConfigAction(source))
                    builder.registerFix(MagoWarningInspectionSettingsAction(source))
                    builder.registerFix(MagoWarningDisableInspectionAction(source))
                    builder.create()
                }
                if (annotationResult.retained) {
                    result.diagnostics.forEach {
                        val message = it.summary + (it.code?.let { code -> " [$code]" } ?: "")
                        annotation(message, inspection.severityFor(it.level, severity, file.project))
                            .range(it.range).tooltip(MagoDiagnosticTooltip.compact(it, rule(it.code))).create()
                    }
                    return
                }
                val batches = MagoDiagnosticBatchFixes.variants(currentText, result.diagnostics)
                val snapshotHash = MagoRelatedNavigation.snapshotHash(currentText)
                val ruleBatches = result.diagnostics.groupBy { it.code }.mapValues { (_, diagnostics) ->
                    MagoDiagnosticBatchFixes.variants(currentText, diagnostics)
                }
                fun batchAction(batch: MagoDiagnosticBatchFixes.Batch, label: String, options: List<IntentionAction> = emptyList(), showPreview: Boolean = false) =
                    MagoApplyFixAction(batch.fix, snapshotHash, file.virtualFile,
                        null, "", options, label, showPreview = showPreview, priority = PriorityAction.Priority.NORMAL,
                        diagnosticOptions = snapshot.options)
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
                        .range(it.range).tooltip(MagoDiagnosticTooltip.compact(it, rule(it.code)))
                    if (it.relatedLocations.any { location -> location.filePath != null && location.range != null }) {
                        builder.registerFix(MagoShowRelatedLocationsAction(it.relatedLocations, it.snapshotHash, file.virtualFile))
                    }
                    val suppressions = if (source != MagoTool.TWIG_LINT && MagoSuppressAction.validCode(it.code)) MagoSuppressAction.Mode.entries.map { mode ->
                        MagoSuppressAction(it, file.virtualFile, mode).asIntention()
                    } else emptyList()
                    it.fix?.let { fix ->
                        val code = it.code?.takeIf { code -> code.isNotBlank() }
                        val sameRule = if (code == null) emptyList() else ruleBatches[code].orEmpty()
                        val options = suppressions + sameRule.asReversed().map { batch ->
                            MagoApplyFixAction(batch.fix, snapshotHash, file.virtualFile, null, "",
                                baseLabel = "Fix all $code problems in file", showPreview = false,
                                priority = PriorityAction.Priority.NORMAL, diagnosticOptions = snapshot.options).asIntention()
                        }
                        builder.registerFix(MagoApplyFixAction(fix, it.snapshotHash, file.virtualFile, it.code, it.message,
                            options, priority = PriorityAction.Priority.HIGH, diagnosticOptions = snapshot.options))
                    }
                    if (it.fix == null && suppressions.isNotEmpty()) {
                        builder.registerFix(MagoSuppressAction(it, file.virtualFile, MagoSuppressAction.Mode.EXPECT, suppressions, primary = true))
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
class MagoTwigLintAnnotator : MagoDiagnosticAnnotator(MagoTool.TWIG_LINT)
