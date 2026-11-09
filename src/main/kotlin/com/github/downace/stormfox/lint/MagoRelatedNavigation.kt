package com.github.downace.stormfox.lint

import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModHighlight
import com.intellij.modcommand.ModNavigate
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import java.security.MessageDigest

internal object MagoRelatedNavigation {
    fun snapshotHash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun currentTargets(targets: List<Target>, file: VirtualFile?, text: String): List<Target> {
        val hash = snapshotHash(text)
        return targets.filter { it.file === file && it.snapshotHash == hash && it.range.endOffset <= text.length }
    }

    data class Target(val file: VirtualFile, val range: TextRange, val snapshotHash: String) {
        fun matches(file: VirtualFile?, text: String): Boolean =
            MagoRelatedNavigation.currentTargets(listOf(this), file, text).isNotEmpty()

        fun command(): ModCommand = ModNavigate(file, range.startOffset, range.endOffset, range.startOffset)
            .andThen(ModHighlight(file, listOf(ModHighlight.HighlightInfo(
                range, EditorColors.SEARCH_RESULT_ATTRIBUTES, true,
            ))))
    }
}
