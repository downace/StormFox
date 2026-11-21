package com.github.downace.stormfox.status

import com.github.downace.stormfox.lint.MagoTool
import com.github.downace.stormfox.settings.MagoSettingsNavigation
import com.intellij.codeInspection.ex.EditInspectionToolsSettingsAction
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager

internal object MagoStatusSettingsNavigation {
    fun openPlugin(project: Project) {
        MagoSettingsNavigation.open(project)
    }

    internal fun openPlugin(project: Project, showSettings: (Project, Class<out Configurable>) -> Unit) {
        MagoSettingsNavigation.open(project, showSettings)
    }

    fun openInspection(project: Project, tool: MagoTool) {
        openInspection(project, tool, EditInspectionToolsSettingsAction::editToolSettings)
    }

    internal fun openInspection(
        project: Project,
        tool: MagoTool,
        editSettings: (Project, InspectionProfileImpl, String) -> Boolean,
    ) {
        editSettings(project, InspectionProjectProfileManager.getInstance(project).currentProfile, tool.shortName)
    }
}
