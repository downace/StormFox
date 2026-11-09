package com.github.downace.stormfox.lint

import java.nio.charset.StandardCharsets
import java.util.Base64

internal object MagoLintTooltip {
    const val PREFIX = "stormfox-mago-details:"
    private const val DOCUMENTATION = "https://mago.carthage.software/latest/en/tools/linter/rules/#"

    fun compact(diagnostic: MagoLintReport.Diagnostic): String {
        val message = buildList {
            add(diagnostic.message)
            diagnostic.primaryMessage?.takeIf { it.isNotBlank() && it.trim() != diagnostic.message.trim() }
                ?.let(::add)
        }.joinToString("<br>") { renderText(it) }
        val code = diagnostic.code?.takeIf { it.isNotBlank() }
        val label = code?.let { renderText(it) }.orEmpty()
        val extra = expanded(diagnostic)
        val details = if (extra == null) "" else " &nbsp; ${detailsLink(extra)}"
        return "<html>$message<br>$label${if (label.isNotEmpty()) details else details.trimStart()}</html>"
    }

    fun expanded(diagnostic: MagoLintReport.Diagnostic): String? = buildString {
        var hasContent = false
        fun section(content: String) {
            append(content)
            hasContent = true
        }
        diagnostic.help?.takeIf { it.isNotBlank() }?.let {
            section("<p><b>Help</b><br>${renderText(it)}</p>")
        }
        diagnostic.notes.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { notes ->
            section("<p><b>Notes</b><br>${notes.joinToString("<br>") { renderText(it) }}</p>")
        }
        diagnostic.relatedLocations.takeIf { it.isNotEmpty() }?.let { locations ->
            section("<p><b>Related locations</b><br>${locations.joinToString("<br>") { location ->
                buildString {
                    location.message?.takeIf { it.isNotBlank() }?.let { append(renderText(it)).append(" — ") }
                    append(renderText(location.path))
                }
            }}</p>")
        }
        diagnostic.code?.takeIf { it.isNotBlank() }?.let {
            section("<p><a href=\"$DOCUMENTATION${encodeFragment(it)}\">Rule documentation</a></p>")
        }
        if (!hasContent) return null
    }.let { "<html><body>$it</body></html>" }

    fun decode(refSuffix: String): String? {
        return runCatching {
            String(Base64.getUrlDecoder().decode(refSuffix), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun detailsUrl(html: String): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(html.toByteArray(StandardCharsets.UTF_8))

    private fun detailsLink(html: String): String =
        "<a href=\"${detailsUrl(html)}\">Details</a>"

    private fun renderText(value: String): String {
        val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
        return Regex("`([^`]+)`").replace(escaped) { "<code>${it.groupValues[1]}</code>" }
            .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>")
    }

    private fun encodeFragment(value: String): String = value.toByteArray(StandardCharsets.UTF_8).joinToString("") {
        val byte = it.toInt() and 0xff
        if ((byte in 'a'.code..'z'.code) || (byte in 'A'.code..'Z'.code) || (byte in '0'.code..'9'.code) ||
            byte == '-'.code || byte == '_'.code || byte == '.'.code || byte == '~'.code
        ) byte.toChar().toString() else "%%%02X".format(byte)
    }
}
