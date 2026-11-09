package com.github.downace.stormfox.lint

import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
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
        val (inspection, severity) = inspection(file) ?: return
        fun annotation(message: String, level: HighlightSeverity = severity) = holder.newAnnotation(level, "Mago Linter: $message")
        when (val result = annotationResult.result) {
            is MagoLintRunner.Result.Failure -> annotation(result.message).fileLevel().create()
            is MagoLintRunner.Result.Success -> result.diagnostics.forEach {
                val plainMessage = buildList {
                    add(it.message)
                    it.primaryMessage?.takeIf { detail -> detail.isNotBlank() && detail.trim() != it.message.trim() }
                        ?.let(::add)
                }.joinToString("\n") + (it.code?.let { code -> " [$code]" } ?: "")
                annotation(plainMessage,
                    inspection.severityFor(it.level, severity, file.project))
                    .range(it.range).tooltip(MagoLintTooltip.compact(it)).create()
            }
        }
    }

    private fun inspection(file: PsiFile): Pair<MagoLintInspection, HighlightSeverity>? {
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        val inspection = profile.getInspectionTool(MagoLintInspection.SHORT_NAME, file)?.tool as? MagoLintInspection ?: return null
        val key = HighlightDisplayKey.find(MagoLintInspection.SHORT_NAME) ?: return null
        return if (profile.isToolEnabled(key, file)) inspection to profile.getErrorLevel(key, file).severity else null
    }

    companion object {
        internal fun isCurrent(snapshot: Snapshot, text: String): Boolean = snapshot.text == text
    }
}
