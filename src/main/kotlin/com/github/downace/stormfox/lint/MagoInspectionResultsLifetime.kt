package com.github.downace.stormfox.lint

import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.APP)
class MagoInspectionResultsLifetime(private val application: Application) : Disposable {
    override fun dispose() {
        if (application.isDisposed) return
        application.assertIsDispatchThread()
        val projects = application.getServiceIfCreated(ProjectManager::class.java)?.openProjects ?: return
        projects.forEach(::closeMagoResults)
    }

    internal fun closeMagoResults(project: Project) {
        if (project.isDisposed) return
        val manager = project.getServiceIfCreated(ToolWindowManager::class.java) ?: return
        val contents = manager.toolWindowIds.flatMap { id ->
            manager.getToolWindow(id)?.contentManagerIfCreated?.contentsRecursively.orEmpty()
        }.toList()
        for (content in contents) {
            if (content.manager == null) continue
            val view = content.component as? InspectionResultsView ?: continue
            if (!hasMagoResults(view)) continue
            val context: GlobalInspectionContextBase = view.globalInspectionContext
            context.close(true)
        }
    }

    internal fun hasMagoResults(view: InspectionResultsView): Boolean {
        if (view.isDisposed || view.isUpdating) return false
        val context: GlobalInspectionContextBase = view.globalInspectionContext
        if (context.currentScope !== view.scope) return false
        val tools = try {
            context.tools
        } catch (_: IllegalStateException) {
            return false
        }
        return MagoTool.entries.any { tools.containsKey(it.shortName) }
    }
}
