package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoLintReport
import org.junit.Assert.*
import org.junit.Test

class MagoLintFixTest {
    private fun edit(start: Int, end: Int, replacement: String, safety: String = "safe") =
        """{"range":{"start":$start,"end":$end},"new_text":[${replacement.toByteArray(Charsets.UTF_8).joinToString { (it.toInt() and 255).toString() }}],"safety":"$safety"}"""

    private fun parse(edits: String, text: String = "abc") = MagoLintReport.parse(
        """{"issues":[{"level":"Error","message":"Issue","edits":$edits,"annotations":[{"kind":"Primary","span":{"file_id":{"name":"source.php"},"start":{"offset":0},"end":{"offset":0}}}]}]}""",
        text, "source.php", null,
    ).single()

    private fun batch(vararg edits: String) = """[[{"name":"source.php"},[${edits.joinToString()}]]]"""

    @Test fun cumulativeEditsUseOriginalSnapshotAndWorstSafety() {
        val fix = parse(batch(edit(2, 3, "XYZ"), edit(0, 1, "!", "unsafe"))).fix!!
        assertEquals("!bXYZ", fix.text)
        assertEquals(MagoLintReport.Safety.UNSAFE, fix.safety)
        assertEquals(listOf(MagoLintReport.Edit(2, 3, "XYZ"), MagoLintReport.Edit(0, 1, "!", MagoLintReport.Safety.UNSAFE)), fix.edits)
        assertEquals(MagoLintReport.Safety.POTENTIALLY_UNSAFE,
            parse(batch(edit(0, 0, "", "potentiallyunsafe"))).fix!!.safety)
    }

    @Test fun supportsUnicodeAndEof() {
        assertEquals("Ж😁!", parse(batch(edit(0, 2, "Ж"), edit(2, 6, "😁"), edit(6, 7, ""), edit(7, 7, "!")), "Я😀x").fix!!.text)
        assertEquals("!", parse(batch(edit(0, 0, "!")), "").fix!!.text)
    }

    @Test fun coincidentAndBoundaryInsertionsPreserveOrder() {
        assertEquals("a12X34c", parse(batch(edit(1, 1, "1"), edit(1, 2, "X"), edit(1, 1, "2"), edit(2, 2, "3"), edit(2, 2, "4"))).fix!!.text)
    }

    @Test fun invalidBatchesPreserveDiagnosticButHaveNoFix() {
        val valid = edit(0, 0, "!")
        val invalid = listOf(
            batch(valid, edit(0, 2, ""), edit(1, 3, "")),
            batch(valid, edit(0, 2, ""), edit(1, 1, "")),
            batch(valid, edit(-1, 0, "")), batch(valid, edit(0, 99, "")),
            batch(valid, edit(2, 1, "")), batch(valid, edit(0, 1, "", "unknown")),
            batch(valid, edit(0, 0, "x").replace("[120]", "[255]")),
            batch(valid, edit(0, 0, "x").replace("[120]", "[256]")),
            batch(valid, edit(0, 0, "x").replace("[120]", "[1.5]")),
            batch(valid, "{}"), "{}", "null", "[]", batch(),
            """[[{"name":"source.php"},[$valid]],[{"path":"other.php"},[$valid]]]""",
        )
        for (value in invalid) {
            val diagnostic = parse(value)
            assertEquals("Issue", diagnostic.message)
            assertNull(value, diagnostic.fix)
        }
        assertNull(parse(batch(valid, edit(1, 2, "")), "Я😀").fix)
    }
}
