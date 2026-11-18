package com.github.downace.stormfox.lint

import com.github.downace.stormfox.config.MagoConfigNavigation
import com.github.downace.stormfox.format.MAGO_NOTIFICATION_GROUP
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.codeInsight.highlighting.TooltipLinkHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MagoConfigTooltipLinkHandler : TooltipLinkHandler() {
    override fun getDescription(refSuffix: String, editor: Editor): String? = null
    override fun getDescriptionTitle(refSuffix: String, editor: Editor): String = "Open Mago config"

    override fun handleLink(refSuffix: String, editor: Editor): Boolean {
        val request = MagoDiagnosticTooltip.decodeConfig(refSuffix) ?: return false
        val project = editor.project ?: return false
        project.getService(MagoConfigNavigationService::class.java).open(request.first, request.second)
        return true
    }
}

@Service(Service.Level.PROJECT)
internal class MagoConfigNavigationService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    fun open(tool: String, code: String?) {
        val root = project.basePath?.let(::File) ?: return
        val settings = MagoSettings.getInstance(project).state
        val configPath = settings.configPath
        val includeGlobal = settings.executionRoot.isBlank()
        scope.launch(Dispatchers.IO) {
            val config = MagoConfigNavigation.discover(
                workingDirectory = root,
                configPath = configPath,
                includeGlobal = includeGlobal,
            )
            val virtualFile = config?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                if (virtualFile == null) {
                    NotificationGroupManager.getInstance().getNotificationGroup(MAGO_NOTIFICATION_GROUP)
                        .createNotification("Mago", "Mago config file was not found.", NotificationType.INFORMATION)
                        .notify(project)
                    return@withContext
                }
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@withContext
                val offset = MagoConfigNavigation.ruleOffset(document.text, virtualFile.name, tool, code)
                if (offset == null) {
                    project.getService(FileEditorManager::class.java).openFile(virtualFile, true)
                } else {
                    OpenFileDescriptor(project, virtualFile, offset).navigate(true)
                }
            }
        }
    }
}
