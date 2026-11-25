package com.github.downace.stormfox.lint

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.UIManager

internal object MagoDiagnosticTooltip {
    const val PREFIX = "stormfox-mago-details:"
    const val CONFIG_PREFIX = "stormfox-mago-config:"

    fun compact(
        diagnostic: MagoDiagnosticReport.Diagnostic,
        metadata: MagoRuleMetadata? = null,
    ): String {
        val message = diagnostic.summary
        val code = diagnostic.code?.takeIf { it.isNotBlank() }?.let { " <code>${renderText(it)}</code>" }.orEmpty()
        val extra = expanded(diagnostic, matchingMetadata(diagnostic, metadata))
        val links = buildList {
            if (extra != null) add(detailsLink(extra))
            else add(configLink(diagnostic.source.command, diagnostic.code?.takeIf { it.isNotBlank() }))
        }.joinToString(" &nbsp; ")
        val details = " &nbsp; $links"
        return "<html><b>Mago</b>: ${renderText(message)}$code$details</html>"
    }

    fun expanded(
        diagnostic: MagoDiagnosticReport.Diagnostic,
        metadata: MagoRuleMetadata? = null,
    ): String? = buildString {
        var hasContent = false
        fun section(content: String) {
            append(content)
            hasContent = true
        }
        diagnostic.message.takeIf {
            it.isNotBlank() && it.trim() != diagnostic.summary.trim()
        }?.let {
            section("<p>${renderText(it)}</p>")
        }
        val help = diagnostic.help?.takeIf { it.isNotBlank() }?.let(::renderText)
        val notes = diagnostic.notes.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.joinToString("<br>") { renderText(it) }
        help?.let {
            section("<p><icon src=\"com.intellij.icons.AllIcons.Actions.IntentionBulb\" alt=\"Help\"/>&nbsp;&nbsp;$it</p>")
        }
        notes?.let {
            section("<p><icon src=\"com.intellij.icons.AllIcons.General.Information\" alt=\"Notes\"/>&nbsp;&nbsp;$it</p>")
        }
        matchingMetadata(diagnostic, metadata)?.let {
            renderRuleMetadata(it).takeIf(String::isNotBlank)?.let(::section)
        }
        diagnostic.code?.takeIf { it.isNotBlank() }?.let {
            val source = diagnostic.source
            val url = source.documentationUrl + if (source.documentationCodeAnchor) encodeFragment(it) else ""
            section("<p><a href=\"$url\">${source.documentationLabel}</a></p>")
        }
        if (!hasContent) return null
        section("<p>${configLink(diagnostic.source.command, diagnostic.code?.takeIf { it.isNotBlank() })}</p>")
    }.let { "<html><body>$it</body></html>" }

    fun decode(refSuffix: String): String? {
        return runCatching {
            String(Base64.getUrlDecoder().decode(refSuffix), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun detailsUrl(html: String): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(html.toByteArray(StandardCharsets.UTF_8))

    fun configUrl(tool: String, code: String?): String {
        val payload = listOf(tool, code ?: "").joinToString("\u0000")
        return CONFIG_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun decodeConfig(refSuffix: String): Pair<String, String?>? = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(refSuffix), StandardCharsets.UTF_8)
        val parts = decoded.split('\u0000')
        if (parts.size != 2 || parts[0] !in MagoTool.entries.map { it.command } ||
            parts[1].contains('\u0000')) return null
        parts[0] to parts[1].ifEmpty { null }
    }.getOrNull()

    private fun detailsLink(html: String): String =
        "<a href=\"${detailsUrl(html)}\">Details</a>"

    private fun configLink(tool: String, code: String?): String =
        "<a href=\"${configUrl(tool, code)}\">Open Mago config</a>"

    private fun matchingMetadata(
        diagnostic: MagoDiagnosticReport.Diagnostic,
        metadata: MagoRuleMetadata?,
    ): MagoRuleMetadata? = metadata?.takeIf {
        diagnostic.source == MagoTool.LINT && diagnostic.code?.isNotBlank() == true && diagnostic.code == it.code
    }

    private fun renderRuleMetadata(metadata: MagoRuleMetadata): String {
        val category = metadata.category.takeIf(String::isNotBlank)
        val level = metadata.level.takeIf(String::isNotBlank)
        val description = metadata.description.takeIf(String::isNotBlank)
        if (metadata.name.isBlank() && category == null && level == null && description == null) return ""

        return buildString {
            val name = metadata.name.takeIf(String::isNotBlank)
            if (name != null || category != null || level != null) {
                append("<hr>")
                append("<table cellspacing=\"4\" cellpadding=\"3\"><tr>")
                name?.let { append("<td><b><font size=\"+1\">${renderText(it)}</font></b></td>") }
                val border = metadataBorderColor()
                category?.let { appendMetadataLabel(it, categoryIcon(it), border) }
                level?.let { appendMetadataLabel(it, levelIcon(it), border) }
                append("</tr></table>")
            }
            description?.let { append("<p>${renderRuleDescription(it)}</p>") }
        }
    }

    private fun StringBuilder.appendMetadataLabel(label: String, icon: String?, border: String) {
        append("<td style=\"border:1px solid $border\">")
        icon?.let { append("<icon src=\"$it\" alt=\"\"/> ") }
        append(renderText(label))
        append("</td>")
    }

    private fun metadataBorderColor(): String {
        val color = UIManager.getColor("Component.borderColor")
            ?: UIManager.getColor("Separator.foreground")
            ?: java.awt.Color.GRAY
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    private fun categoryIcon(category: String): String? = when (category.filter(Char::isLetterOrDigit).lowercase()) {
        "bestpractices" -> "com.intellij.icons.AllIcons.Actions.IntentionBulb"
        "clarity" -> "com.intellij.icons.AllIcons.General.InspectionsEye"
        "consistency" -> "com.intellij.icons.AllIcons.Actions.ReformatCode"
        "correctness" -> "com.intellij.icons.AllIcons.General.GreenCheckmark"
        "deprecation" -> "com.intellij.icons.AllIcons.General.History"
        "maintainability" -> "com.intellij.icons.AllIcons.Actions.RefactoringBulb"
        "redundancy" -> "com.intellij.icons.AllIcons.Actions.Copy"
        "safety" -> "com.intellij.icons.AllIcons.Nodes.SecurityRole"
        "security" -> "com.intellij.icons.AllIcons.Nodes.Locked"
        else -> null
    }

    private fun levelIcon(level: String): String? = when (level.lowercase()) {
        "error" -> "com.intellij.icons.AllIcons.General.Error"
        "warning" -> "com.intellij.icons.AllIcons.General.Warning"
        "help" -> "com.intellij.icons.AllIcons.General.ContextHelp"
        "note", "information", "info" -> "com.intellij.icons.AllIcons.General.Information"
        else -> null
    }

    private fun renderRuleDescription(value: String): String = buildString {
        // Isolate code and URLs before applying emphasis: URL punctuation (notably underscores)
        // is literal metadata, while code must remain inert rather than becoming a link.
        val token = Regex("`[^`]+`|https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
        var offset = 0
        for (match in token.findAll(value)) {
            append(renderRuleDescriptionText(value.substring(offset, match.range.first)))
            val part = match.value
            if (part.startsWith('`')) {
                append(renderText(part))
            } else {
                val url = part.trimEnd('.', ',', ';', ':', '!', '?')
                    .let(::trimUnmatchedUrlClosers)
                val suffix = part.substring(url.length)
                append("<a href=\"${escapeHtml(url)}\">${escapeHtml(url)}</a>")
                append(escapeHtml(suffix))
            }
            offset = match.range.last + 1
        }
        append(renderRuleDescriptionText(value.substring(offset)))
    }

    private fun trimUnmatchedUrlClosers(value: String): String {
        var result = value
        while (result.endsWith(')') && result.count { it == ')' } > result.count { it == '(' } ||
            result.endsWith(']') && result.count { it == ']' } > result.count { it == '[' } ||
            result.endsWith('}') && result.count { it == '}' } > result.count { it == '{' }) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun renderRuleDescriptionText(value: String): String =
        Regex("\\*\\*(.+?)\\*\\*").replace(renderText(value)) { "<b>${it.groupValues[1]}</b>" }
            .replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) { "<i>${it.groupValues[1]}</i>" }

    private fun renderText(value: String): String {
        val escaped = escapeHtml(value)
        return Regex("`([^`]+)`").replace(escaped) { "<code>${it.groupValues[1]}</code>" }
            .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>")
    }

    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun encodeFragment(value: String): String = value.toByteArray(StandardCharsets.UTF_8).joinToString("") {
        val byte = it.toInt() and 0xff
        if ((byte in 'a'.code..'z'.code) || (byte in 'A'.code..'Z'.code) || (byte in '0'.code..'9'.code) ||
            byte == '-'.code || byte == '_'.code || byte == '.'.code || byte == '~'.code
        ) byte.toChar().toString() else "%%%02X".format(byte)
    }
}
