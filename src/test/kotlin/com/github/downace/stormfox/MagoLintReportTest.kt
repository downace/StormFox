package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoLintReport
import com.intellij.openapi.util.TextRange
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MagoLintReportTest {
    private fun issue(start: Int = 0, end: Int = 1, path: String = "C:/project/source.php", kind: String = "Primary", detail: String = "Detail") =
        """{"level":"Error","code":"example","message":"Issue","annotations":[{"kind":"$kind","message":"$detail","span":{"file_id":{"name":"$path"},"start":{"offset":$start},"end":{"offset":$end}}}]}"""

    private fun parse(issues: String, text: String = "abc") =
        MagoLintReport.parse("""{"issues":[$issues]}""", text, "C:/project/source.php", File("C:/project"))

    @Test fun convertsUtf8ToUtf16() {
        val result = parse(issue(2, 6), "Я😀x").single()
        assertEquals(TextRange(1, 3), result.range)
        assertEquals("Issue", result.message)
        assertEquals("Detail", result.primaryMessage)
        assertEquals("example", result.code)
        assertEquals("Error", result.level)
    }

    @Test fun skipsInvalidByteRanges() {
        for ((start, end) in listOf(1 to 2, 2 to 3, -1 to 2, 0 to 99, 6 to 2)) {
            assertTrue(parse(issue(start, end), "Я😀x").isEmpty())
        }
    }

    @Test fun preservesZeroLengthRangesIncludingEof() {
        assertEquals(TextRange(0, 0), parse(issue(0, 0), "").single().range)
        assertEquals(TextRange(3, 3), parse(issue(3, 3)).single().range)
    }

    @Test fun filtersForeignAndSecondaryAnnotations() {
        assertTrue(parse(issue(path = "C:/other/source.php")).isEmpty())
        assertTrue(parse(issue(kind = "Secondary")).isEmpty())
        assertTrue(parse(issue(path = "other/source.php")).isEmpty())
    }

    @Test fun resolvesRelativePathsAndNormalizesWindowsPaths() {
        assertEquals(1, parse(issue(path = "./source.php")).size)
        assertEquals(1, parse(issue(path = "C:\\\\PROJECT\\\\source.php")).size)
    }

    @Test fun deduplicatesIssuesAndMessages() {
        assertEquals(1, parse("${issue()},${issue()}").size)
        assertEquals("Issue", parse(issue(detail = "Issue")).single().message)
        assertEquals("Issue", parse(issue(detail = "Issue")).single().primaryMessage)
    }

    @Test fun acceptsRelativeNameWithDiskPathPresent() {
        val issue = com.google.gson.JsonParser.parseString(issue(path = "source.php")).asJsonObject
        issue.getAsJsonArray("annotations")[0].asJsonObject.getAsJsonObject("span")
            .getAsJsonObject("file_id").addProperty("path", "\\\\?\\C:\\project\\source.php")
        assertEquals(1, parse(issue.toString()).size)
    }

    @Test fun skipsAbsentPrimarySpans() {
        assertTrue(parse("""{"level":"Error","message":"Issue","annotations":[{"kind":"Primary"}]}""").isEmpty())
    }

    @Test fun acceptsCleanReport() {
        assertTrue(parse("").isEmpty())
    }

    @Test fun preservesOptionalIssueContextAndPlainHtml() {
        val value = com.google.gson.JsonParser.parseString(issue(detail = "<b>detail</b>")).asJsonObject
        value.addProperty("message", "<script>alert(1)</script>")
        value.add("notes", com.google.gson.JsonParser.parseString("""["", "  ", "<img src=x>", "Another note"]"""))
        value.addProperty("help", "<a href='bad'>help</a>")
        val result = parse(value.toString()).single()
        assertEquals("<script>alert(1)</script>", result.message)
        assertEquals("<b>detail</b>", result.primaryMessage)
        assertEquals(listOf("<img src=x>", "Another note"), result.notes)
        assertEquals("<a href='bad'>help</a>", result.help)
    }

    @Test fun acceptsMissingNullAndBlankOptionalContext() {
        for (fields in listOf("", ", \"notes\":null, \"help\":null", ", \"notes\":[\"\", \"  \"], \"help\":\"  \"")) {
            val result = parse("""{"level":"Error","message":"Issue"$fields,"annotations":[{"kind":"Primary","message":null,"span":{"file_id":{"name":"source.php"},"start":{"offset":0},"end":{"offset":1}}}]}""").single()
            assertNull(result.primaryMessage)
            assertTrue(result.notes.isEmpty())
            assertNull(result.help)
            assertTrue(result.relatedLocations.isEmpty())
        }
        assertNull(parse(issue(detail = "  ")).single().primaryMessage)
    }

    @Test fun attachesSecondaryLocationsToEveryPrimaryWithoutHighlights() {
        val value = com.google.gson.JsonParser.parseString(issue()).asJsonObject
        val annotations = value.getAsJsonArray("annotations")
        annotations.add(com.google.gson.JsonParser.parseString(issue(1, 2)).asJsonObject.getAsJsonArray("annotations")[0])
        for (secondary in listOf(
            """{"kind":"Secondary","message":"Elsewhere","span":{"file_id":{"name":"other.php","path":"ignored.php"},"start":{"line":7}}}""",
            """{"kind":"Secondary","message":" ","span":{"file_id":{"path":"fallback.php"}}}""",
            """{"kind":"Secondary","message":"No span"}""",
            """{"kind":"Secondary","span":null}""",
            """{"kind":"Secondary","span":{}}""",
        )) annotations.add(com.google.gson.JsonParser.parseString(secondary))
        val results = parse(value.toString())
        assertEquals(listOf(TextRange(0, 1), TextRange(1, 2)), results.map { it.range })
        val expected = listOf(
            MagoLintReport.RelatedLocation("Elsewhere", "other.php", 7),
            MagoLintReport.RelatedLocation(null, "fallback.php"),
        )
        results.forEach { assertEquals(expected, it.relatedLocations) }
    }

    @Test fun rejectsMalformedOptionalContext() {
        for (fields in listOf("\"notes\":\"note\"", "\"notes\":[null]", "\"notes\":[42]", "\"help\":[]")) {
            assertThrows(Exception::class.java) {
                parse("""{"level":"Error","message":"Issue",$fields,"annotations":[]}""")
            }
        }
    }

    @Test fun rejectsMalformedReports() {
        for (json in listOf("", "{", "{}", "[]", "{\"issues\":null}", "{\"issues\":{}}", "{issues:[]}", "{\"issues\":[]} trailing")) {
            assertThrows(Exception::class.java) { MagoLintReport.parse(json, "", "source.php", null) }
        }
    }
}
