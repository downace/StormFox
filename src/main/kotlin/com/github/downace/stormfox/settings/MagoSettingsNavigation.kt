package com.github.downace.stormfox.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

internal object MagoSettingsNavigation {
    fun open(project: Project) {
        open(project) { targetProject, configurableClass ->
            ShowSettingsUtil.getInstance().showSettingsDialog(targetProject, configurableClass)
        }
    }

    internal fun open(project: Project, showSettings: (Project, Class<out Configurable>) -> Unit) {
        // The string overload selects a display name, not a configurable ID.
        showSettings(project, MagoConfigurable::class.java)
    }
}
