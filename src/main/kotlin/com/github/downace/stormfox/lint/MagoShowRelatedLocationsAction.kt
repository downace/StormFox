package com.github.downace.stormfox.lint

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.vfs.VirtualFile

internal class MagoShowRelatedLocationsAction(
    private val locations: List<MagoLintReport.RelatedLocation>,
    private val snapshotHash: String,
    private val sourceFile: VirtualFile,
) : ModCommandAction {
    override fun getFamilyName(): String = "Show related locations"

    override fun getPresentation(context: ActionContext): Presentation? {
        val targets = currentTargets(context)
        if (targets.isEmpty()) return null
        return Presentation.of(getFamilyName())
    }

    override fun perform(context: ActionContext): ModCommand {
        val validTargets = currentTargets(context).toSet()
        val steps = targets().filter { (_, target) -> target in validTargets }.map { (location, target) ->
            val label = location.message?.takeIf { it.isNotBlank() }?.let { "$it — ${location.path}" } ?: location.path
            RelatedLocationAction(label, target)
        }
        return if (steps.isEmpty()) ModCommand.nop() else ModCommand.chooseAction("Show related location", steps)
    }

    private fun currentTargets(context: ActionContext): List<MagoRelatedNavigation.Target> =
        MagoRelatedNavigation.currentTargets(
            targets().map { it.second }, context.file.virtualFile, context.file.text,
        )

    private fun targets() = locations.mapNotNull { location ->
        val range = location.range ?: return@mapNotNull null
        if (location.filePath != sourceFile.path) return@mapNotNull null
        location to MagoRelatedNavigation.Target(sourceFile, range, snapshotHash)
    }

    private class RelatedLocationAction(
        private val label: String,
        private val target: MagoRelatedNavigation.Target,
    ) : ModCommandAction {
        override fun getFamilyName(): String = "Show related location"

        override fun getPresentation(context: ActionContext): Presentation? {
            return if (target.matches(context.file.virtualFile, context.file.text)) {
                Presentation.of(label).withHighlighting(target.range)
            } else null
        }

        override fun perform(context: ActionContext): ModCommand {
            return if (target.matches(context.file.virtualFile, context.file.text)) target.command() else ModCommand.nop()
        }
    }
}
