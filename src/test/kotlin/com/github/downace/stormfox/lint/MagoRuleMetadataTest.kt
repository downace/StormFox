package com.github.downace.stormfox.lint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MagoRuleMetadataTest {
    @Test fun parsesRealThreeRuleFixture() {
        val json = requireNotNull(javaClass.getResource("/lint/rule-metadata.json")).readText()
        val rules = MagoRuleMetadataParser.parse(json)
        assertEquals(3, rules.size)
        assertEquals("Security", rules.getValue("sensitive-parameter").category)
        assertEquals("Warning", rules.getValue("literal-named-argument").level)
        assertTrue(rules.getValue("prefer-first-class-callable").goodExample.contains("formatter"))
    }

    @Test fun parsesCatalogueFieldsAndNulls() {
        val parsed = MagoRuleMetadataParser.parse("""
            [{"name":"Literal Named Argument","code":"literal-named-argument",
              "description":"Enforces named arguments.\nNext line.","good_example":"<?php\nok();",
              "bad_example":"<?php\nbad();","category":"Clarity","level":"Warning"},
             {"code":"other","name":null}]
        """.trimIndent())
        assertEquals("Literal Named Argument", parsed.getValue("literal-named-argument").name)
        assertEquals("<?php\nok();", parsed.getValue("literal-named-argument").goodExample)
        assertEquals("", parsed.getValue("other").name)
        assertEquals("", parsed.getValue("other").description)
    }

    @Test fun rejectsMalformedAndDuplicateRules() {
        listOf("{", "[]", """[{"code":"x"},{"code":"x"}]""", """[{"code":"x","name":2}]""")
            .forEach { json -> assertThrows(RuntimeException::class.java) { MagoRuleMetadataParser.parse(json) } }
    }

    @Test fun commandUsesPedanticAndPreservesDockerPrefix() {
        val key = MagoRuleMetadataKey("docker exec container mago", File(".").absoluteFile.normalize().path, "/app", null)
        val args = MagoRuleMetadataRunner.arguments(key)
        assertEquals(listOf("exec", "container", "mago", "lint", "--list-rules", "--json", "--pedantic"), args.drop(1))
        assertTrue("--config" !in args)
    }
}
