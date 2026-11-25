package com.github.downace.stormfox.settings

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.profile.codeInspection.ui.InspectionProfileActionProvider
import com.intellij.profile.codeInspection.ui.SingleInspectionProfilePanel

class MagoInspectionProfileActionProvider : InspectionProfileActionProvider() {
    override fun getProfilePanelActions(panel: SingleInspectionProfilePanel): List<ProfilePanelAction> =
        listOf(action(panel, "stormfox.magoLint", "MagoLint"), action(panel, "stormfox.magoTwigLint", "MagoTwigLint"))

    private fun action(panel: SingleInspectionProfilePanel, id: String, target: String): ProfilePanelAction =
        ProfilePanelAction(object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                // The platform selects only nodes currently visible in the filtered tree.
                // Leave filters and the editable profile untouched if the target is hidden.
                panel.selectInspectionTool(target)
                if (panel.selectedTool?.shortName != target) {
                    Messages.showInfoMessage(panel.project, "The inspection is not visible. Clear the inspection list filters and try again.", "Inspection Not Visible")
                }
            }
        }, id)
}
