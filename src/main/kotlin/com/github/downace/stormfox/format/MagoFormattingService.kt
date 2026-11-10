package com.github.downace.stormfox.format

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.github.downace.stormfox.settings.MagoSettings
import java.io.File

class MagoFormattingService : AsyncDocumentFormattingService() {

    private val LOG = Logger.getInstance(MagoFormattingService::class.java)

    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean =
        MagoSettings.getInstance().state.enabled && file.language.id == PHP_LANGUAGE_ID

    override fun getName(): String = TOOL_NAME

    override fun getNotificationGroupId(): String = MAGO_NOTIFICATION_GROUP

    /**
     * The default implementation saves the document before formatting, because external formatters
     * are expected to read the file from disk. Mago gets the buffer through stdin instead, so saving
     * here would silently turn "Reformat Code" into a file save.
     */
    override fun prepareForFormatting(document: Document, formattingContext: FormattingContext) = Unit

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val settings = MagoSettings.getInstance().state
        if (settings.magoPath.isBlank()) {
            request.onError(TOOL_NAME, "The path to the mago binary is not configured. Set it in $SETTINGS_LOCATION.")
            return null
        }

        val logicalPath = request.context.containingFile.virtualFile?.path ?: request.ioFile?.path
        if (logicalPath == null) {
            request.onError(TOOL_NAME, "The file has no path on disk, mago cannot format it.")
            return null
        }

        val workingDirectory = request.context.project.basePath?.let(::File)
        LOG.info(
            "Mago: formatting $logicalPath with ${settings.magoPath} " +
                "(config ${settings.configPath.ifBlank { "<auto>" }})",
        )
        return MagoFormattingTask(
            request = request,
            binary = settings.magoPath,
            configPath = settings.configPath,
            workingDirectory = workingDirectory,
            logicalPath = logicalPath,
            originalText = request.documentText,
        )
    }

    /**
     * The task lives inside the service because [AsyncDocumentFormattingService.FormattingTask] was a
     * protected nested interface before build 261. A top level class in another package cannot
     * implement it, and the plugin has to compile against builds that still declare it that way.
     */
    internal class MagoFormattingTask(
        private val request: AsyncFormattingRequest,
        private val binary: String,
        private val configPath: String,
        private val workingDirectory: File?,
        private val logicalPath: String,
        private val originalText: String,
        private val format: (MagoRunner.Cancellation) -> MagoRunner.Result = { cancellation ->
            MagoRunner.format(binary, workingDirectory, configPath, logicalPath, originalText, cancellation)
        },
    ) : AsyncDocumentFormattingService.FormattingTask {

        private val LOG = Logger.getInstance(MagoFormattingTask::class.java)

        private val cancellation = MagoRunner.Cancellation()

        override fun run() {
            cancellation.checkCanceled()
            val result = format(cancellation)
            LOG.info(
                "Mago: formatted $logicalPath, result=${result::class.simpleName}, " +
                    "changed=${result is MagoRunner.Result.Success && result.text != originalText}",
            )
            cancellation.deliver {
                when (result) {
                    is MagoRunner.Result.Success -> {
                        if (result.text != originalText) {
                            request.onTextReady(result.text)
                        } else {
                            request.onTextReady(null)
                        }
                    }
                    is MagoRunner.Result.Failure -> {
                        notifyMagoError(request.context.project, result.message)
                        request.onTextReady(null)
                    }
                }
            }
        }

        override fun cancel(): Boolean {
            cancellation.cancel()
            return true
        }
    }
}
