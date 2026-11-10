package com.github.downace.stormfox.lint

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.util.TextRange
import java.io.File
import java.io.StringReader
import java.util.Locale

internal object MagoLintReport {
    data class Diagnostic(
        val range: TextRange,
        val message: String,
        val code: String?,
        val level: String,
        val primaryMessage: String? = null,
        val notes: List<String> = emptyList(),
        val help: String? = null,
        val relatedLocations: List<RelatedLocation> = emptyList(),
        val snapshotHash: String = "",
        val fix: Fix? = null,
    )

    enum class Safety { SAFE, POTENTIALLY_UNSAFE, UNSAFE }
    data class Edit(val start: Int, val end: Int, val text: String, val safety: Safety = Safety.SAFE)
    data class Fix(val text: String, val safety: Safety, val edits: List<Edit> = emptyList())

    data class RelatedLocation(
        val message: String?,
        val path: String,
        val line: Int? = null,
        val range: TextRange? = null,
        val filePath: String? = null,
    )

    fun parse(json: String, text: String, logicalPath: String, workingDirectory: File?): List<Diagnostic> {
        JsonReader(StringReader(json)).use {
            require(it.peek() == JsonToken.BEGIN_OBJECT) { "Expected a lint report object" }
            it.skipValue()
            require(it.peek() == JsonToken.END_DOCUMENT) { "Trailing content in lint report" }
        }
        val root = JsonParser.parseString(json).asJsonObject
        val issues = requireNotNull(root.get("issues")) { "Missing lint issues" }
        require(issues.isJsonArray) { "Expected lint issues array" }
        val offsets = byteOffsets(text)
        val expectedPath = normalizePath(logicalPath, workingDirectory)
        val snapshotHash = MagoRelatedNavigation.snapshotHash(text)
        val diagnostics = linkedSetOf<Diagnostic>()
        for (element in issues.asJsonArray) {
            require(element.isJsonObject) { "Expected lint issue object" }
            val issue = element.asJsonObject
            val message = issue.requiredString("message")
            val level = issue.requiredString("level")
            val code = issue.optionalString("code")
            val fix = parseFix(issue.get("edits"), text, offsets, expectedPath, workingDirectory)
            val notes = issue.get("notes")?.takeUnless { it.isJsonNull }?.let { values ->
                require(values.isJsonArray) { "Expected notes array" }
                values.asJsonArray.map { value ->
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected string note" }
                    value.asString
                }.filter { it.isNotBlank() }
            } ?: emptyList()
            val help = issue.optionalString("help")?.takeIf { it.isNotBlank() }
            val annotations = issue.get("annotations") ?: continue
            require(annotations.isJsonArray) { "Expected annotations array" }
            val relatedLocations = annotations.asJsonArray.mapNotNull { element ->
                require(element.isJsonObject) { "Expected annotation object" }
                val annotation = element.asJsonObject
                if (annotation.requiredString("kind") != "Secondary") return@mapNotNull null
                val span = annotation.get("span")?.takeUnless { it.isJsonNull } ?: return@mapNotNull null
                require(span.isJsonObject) { "Expected span object" }
                val path = span.asJsonObject.filePath() ?: return@mapNotNull null
                val start = span.asJsonObject.get("start")?.takeUnless { it.isJsonNull }
                if (start != null) require(start.isJsonObject) { "Expected span position" }
                val line = start?.asJsonObject?.get("line")?.takeUnless { it.isJsonNull }?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Expected numeric line" }
                    it.asString.toIntOrNull()
                }
                val sameSnapshot = normalizePath(path, workingDirectory) == expectedPath
                val range = if (sameSnapshot) {
                    val secondaryStart = offset(span.asJsonObject.get("start"))
                    val secondaryEnd = offset(span.asJsonObject.get("end"))
                    if (secondaryStart != null && secondaryEnd != null && secondaryStart in offsets.indices &&
                        secondaryEnd in offsets.indices && secondaryEnd >= secondaryStart &&
                        offsets[secondaryStart] >= 0 && offsets[secondaryEnd] >= 0
                    ) TextRange(offsets[secondaryStart], offsets[secondaryEnd]) else null
                } else null
                RelatedLocation(annotation.optionalString("message")?.takeIf { it.isNotBlank() }, path, line, range,
                    logicalPath.takeIf { sameSnapshot && range != null })
            }
            for (annotationElement in annotations.asJsonArray) {
                require(annotationElement.isJsonObject) { "Expected annotation object" }
                val annotation = annotationElement.asJsonObject
                if (annotation.requiredString("kind") != "Primary") continue
                val span = annotation.get("span")?.takeUnless { it.isJsonNull } ?: continue
                require(span.isJsonObject) { "Expected span object" }
                val path = span.asJsonObject.filePath() ?: continue
                if (normalizePath(path, workingDirectory) != expectedPath) continue
                val start = offset(span.asJsonObject.get("start")) ?: continue
                val end = offset(span.asJsonObject.get("end")) ?: continue
                if (start !in offsets.indices || end !in offsets.indices || end < start) continue
                if (offsets[start] < 0 || offsets[end] < 0) continue
                val detail = annotation.optionalString("message")?.takeIf { it.isNotBlank() }
                diagnostics.add(Diagnostic(TextRange(offsets[start], offsets[end]), message, code, level,
                    detail, notes, help, relatedLocations, snapshotHash, fix))
            }
        }
        return diagnostics.toList()
    }

    private fun JsonObject.requiredString(name: String): String =
        requireNotNull(optionalString(name)) { "Missing $name" }

    private fun parseFix(value: JsonElement?, text: String, offsets: IntArray, expectedPath: String, workingDirectory: File?): Fix? {
        if (value == null || value.isJsonNull) return null
        return try {
            require(value.isJsonArray)
            val edits = mutableListOf<Edit>()
            var safety = Safety.SAFE
            fun integer(value: JsonElement): Int {
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                return requireNotNull(value.asString.toIntOrNull())
            }
            for (entry in value.asJsonArray) {
                require(entry.isJsonArray && entry.asJsonArray.size() == 2)
                val pair = entry.asJsonArray
                val descriptor = pair[0].asJsonObject
                val path = descriptor.optionalString("name")?.takeIf { it.isNotBlank() }
                    ?: descriptor.optionalString("path")?.takeIf { it.isNotBlank() }
                require(path != null && normalizePath(path, workingDirectory) == expectedPath)
                require(pair[1].isJsonArray)
                for (element in pair[1].asJsonArray) {
                    val edit = element.asJsonObject
                    val range = edit.getAsJsonObject("range")
                    val start = integer(range.get("start"))
                    val end = integer(range.get("end"))
                    require(start in offsets.indices && end in offsets.indices && start <= end)
                    require(offsets[start] >= 0 && offsets[end] >= 0)
                    val level = when (edit.requiredString("safety")) {
                        "safe" -> Safety.SAFE
                        "potentiallyunsafe" -> Safety.POTENTIALLY_UNSAFE
                        "unsafe" -> Safety.UNSAFE
                        else -> error("Unknown edit safety")
                    }
                    if (level.ordinal > safety.ordinal) safety = level
                    val bytes = edit.get("new_text")
                    require(bytes.isJsonArray)
                    val replacement = bytes.asJsonArray.map { byte ->
                        val number = integer(byte)
                        require(number in 0..255)
                        number.toByte()
                    }.toByteArray()
                    val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(replacement)).toString()
                    edits.add(Edit(offsets[start], offsets[end], decoded, level))
                }
            }
            if (edits.isEmpty()) return null
            Fix(MagoLintBatchFixes.stitch(text, edits), safety, edits.toList())
        } catch (_: RuntimeException) {
            null
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun JsonObject.filePath(): String? {
        val fileId = get("file_id")?.takeUnless { it.isJsonNull } ?: return null
        require(fileId.isJsonObject) { "Expected file identifier object" }
        return fileId.asJsonObject.optionalString("name")?.takeIf { it.isNotBlank() }
            ?: fileId.asJsonObject.optionalString("path")?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected string $name" }
        return value.asString
    }

    private fun offset(value: JsonElement?): Int? {
        if (value == null || value.isJsonNull) return null
        require(value.isJsonObject) { "Expected span position" }
        val number = value.asJsonObject.get("offset") ?: return null
        require(number.isJsonPrimitive && number.asJsonPrimitive.isNumber) { "Expected numeric offset" }
        return number.asString.toIntOrNull()
    }

    private fun byteOffsets(text: String): IntArray {
        val result = IntArray(text.toByteArray(Charsets.UTF_8).size + 1) { -1 }
        var chars = 0
        var bytes = 0
        result[0] = 0
        while (chars < text.length) {
            val count = Character.charCount(text.codePointAt(chars))
            bytes += text.substring(chars, chars + count).toByteArray(Charsets.UTF_8).size
            chars += count
            result[bytes] = chars
        }
        return result
    }

    private fun normalizePath(path: String, workingDirectory: File?): String {
        val slashed = path.replace('\\', '/')
        val absolute = slashed.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(slashed)
        val full = if (absolute || workingDirectory == null) slashed
            else workingDirectory.absolutePath.replace('\\', '/') + "/" + slashed
        val parts = mutableListOf<String>()
        for (part in full.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else parts.add(part)
                else -> parts.add(part)
            }
        }
        val normalized = (if (full.startsWith('/')) "/" else "") + parts.joinToString("/")
        return if (File.separatorChar == '\\' || Regex("^[A-Za-z]:/").containsMatchIn(full))
            normalized.lowercase(Locale.ROOT) else normalized
    }
}
