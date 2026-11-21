package com.github.downace.stormfox.lint

import com.github.downace.stormfox.status.MagoStatusSettingsNavigation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.ProfileChangeAdapter
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import javax.swing.JComponent

class MagoDiagnosticEditorNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (!file.isInLocalFileSystem) return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val failures = project.getService(MagoDiagnosticEditorNotificationService::class.java)
            .failures(file.path)
            .filter { MagoDiagnosticAnnotator.inspection(psiFile, it.tool) != null }
        if (failures.isEmpty()) return null

        val message = failures.joinToString("  ·  ") { "${it.tool.displayName}: ${it.error}" }
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = message
                createActionLabel("Open settings") {
                    MagoStatusSettingsNavigation.openPlugin(project)
                }
            }
        }
    }
}

@Service(Service.Level.PROJECT)
internal class MagoDiagnosticEditorNotificationService(
    private val project: Project,
) : Disposable {
    private val refreshQueued = AtomicBoolean()

    init {
        val activity = project.getService(MagoDiagnosticService::class.java).activity
        activity.addListener(this) { refreshLater() }
        project.messageBus.connect(this).subscribe(ProfileChangeAdapter.TOPIC, object : ProfileChangeAdapter {
            override fun profileChanged(profile: com.intellij.codeInspection.InspectionProfile) = refreshLater()
            override fun profileActivated(
                oldProfile: com.intellij.codeInspection.InspectionProfile?,
                profile: com.intellij.codeInspection.InspectionProfile?,
            ) = refreshLater()
            override fun profilesInitialized() = refreshLater()
        })
    }

    private fun refreshLater() {
        if (refreshQueued.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater({
                refreshQueued.set(false)
                if (project.isDisposed) return@invokeLater
                val notifications = com.intellij.ui.EditorNotifications.getInstance(project)
                project.getService(FileEditorManager::class.java).openFiles
                    .filter { it.isValid && it.isInLocalFileSystem }
                    .forEach(notifications::updateNotifications)
            }, ModalityState.any())
        }
    }

    fun failures(path: String): List<MagoDiagnosticActivity.Entry> =
        project.getService(MagoDiagnosticService::class.java).activity.snapshot().failures
            .filter { it.logicalPath == path && !it.error.isNullOrBlank() }
            .sortedBy { it.tool.ordinal }

    override fun dispose() = Unit
}
