package com.github.downace.stormfox

import com.github.downace.stormfox.config.MagoConfigNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MagoConfigNavigationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `discovery follows workspace precedence and explicit paths`() {
        val workspace = temporary.newFolder("workspace")
        val home = temporary.newFolder("home")
        val env = homeEnvironment(home)
        val dist = File(workspace, "mago.dist.toml").apply { writeText("") }
        val normal = File(workspace, "mago.json").apply { writeText("") }
        assertEquals(normal, MagoConfigNavigation.discover(workspace, "", env))
        assertEquals(dist, MagoConfigNavigation.discover(workspace, "mago.dist.toml", env))
        assertNull(MagoConfigNavigation.discover(workspace, "missing.toml", env))
        val toml = File(workspace, "mago.toml").apply { writeText("") }
        assertEquals(toml, MagoConfigNavigation.discover(workspace, "", env))
    }

    @Test fun `global directories ignore dist and workspace parents`() {
        val parent = temporary.newFolder("parent")
        val workspace = File(parent, "child").apply { mkdir() }
        val home = temporary.newFolder("home")
        val xdg = temporary.newFolder("xdg")
        val config = File(home, ".config").apply { mkdir() }
        File(parent, "mago.toml").writeText("")
        File(xdg, "mago.dist.toml").writeText("")
        File(config, "mago.dist.toml").writeText("")
        val env = homeEnvironment(home) + ("XDG_CONFIG_HOME" to xdg.path)
        assertNull(MagoConfigNavigation.discover(workspace, "", env))
        val homeConfig = File(config, "mago.yml").apply { writeText("") }
        assertEquals(homeConfig, MagoConfigNavigation.discover(workspace, "", env))
        val xdgConfig = File(xdg, "mago.json").apply { writeText("") }
        assertEquals(xdgConfig, MagoConfigNavigation.discover(workspace, "", env))
    }

    @Test fun `global discovery can be disabled without affecting workspace or explicit config`() {
        val workspace = temporary.newFolder("workspace")
        val home = temporary.newFolder("home")
        File(home, "mago.toml").writeText("")
        val env = homeEnvironment(home)
        assertNull(MagoConfigNavigation.discover(workspace, "", env, includeGlobal = false))
        val local = File(workspace, "mago.dist.toml").apply { writeText("") }
        assertEquals(local, MagoConfigNavigation.discover(workspace, "", env, includeGlobal = false))
        val explicit = File(workspace, "custom.toml").apply { writeText("") }
        assertEquals(explicit, MagoConfigNavigation.discover(workspace, "custom.toml", env, includeGlobal = false))
    }

    @Test fun `toml table dotted and inline keys`() {
        val samples = listOf(
            "[linter.rules]\n\"no-global\" = { enabled = true }" to "\"no-global\"",
            "[linter.rules.'no-global']\nenabled = true" to "'no-global'",
            "linter.rules.no-global.enabled = true" to "no-global",
            "linter = { rules = { 'no-global' = { enabled = true } } }" to "'no-global'",
            "[linter]\nrules.\"no-global\" = { enabled = false }" to "\"no-global\"",
            "[linter.rules]\n\"no-\\u0067lobal\" = { enabled = true }" to "\"no-\\u0067lobal\"",
        )
        for ((text, marker) in samples) {
            assertEquals(text.indexOf(marker), MagoConfigNavigation.ruleOffset(text, "mago.toml", "lint", "no-global"))
        }
    }

    @Test fun `array tables do not inherit previous toml table`() {
        val text = "[linter.rules]\nother = { enabled = true }\n[[guard.perimeter.rules]]\nno-global = { enabled = true }"
        assertNull(MagoConfigNavigation.ruleOffset(text, "mago.toml", "lint", "no-global"))
    }

    @Test fun `toml trailing comments do not consume following statements`() {
        val samples = listOf(
            "threads = 2 # comment\n[linter.rules]\nno-global = { enabled = true }",
            "[linter.rules]\nother = 2 # comment\nno-global = { enabled = true }",
            "items = [1, # comment\n2]\n[linter.rules]\nno-global = { enabled = true }",
        )
        for (text in samples) {
            assertEquals(text.indexOf("no-global"), MagoConfigNavigation.ruleOffset(text, "mago.toml", "lint", "no-global"))
        }
    }

    @Test fun `yaml block mappings support quoted keys and comments`() {
        val text = """
            other:
              rules:
                no-global: { enabled: false }
            "linter": # comment
              'rules':
                "no-\u0067lobal": { enabled: true }
        """.trimIndent()
        assertEquals(text.indexOf("\"no-"), MagoConfigNavigation.ruleOffset(text, "mago.yaml", "lint", "no-global"))
        assertEquals(text.indexOf("\"no-"), MagoConfigNavigation.ruleOffset(text, "mago.yml", "lint", "no-global"))
    }

    @Test fun `yaml ignores block scalar examples and unrelated mappings`() {
        val text = """
            example: |
              linter:
                rules:
                  no-global: { enabled: true }
            other:
              linter:
                rules:
                  no-global: { enabled: true }
            linter:
              rules:
                other-rule:
                  enabled: true
        """.trimIndent()
        assertNull(MagoConfigNavigation.ruleOffset(text, "mago.yaml", "lint", "no-global"))
        assertNull(MagoConfigNavigation.ruleOffset(text, "mago.yaml", "guard", "other-rule"))
        assertEquals(text.indexOf("other-rule"), MagoConfigNavigation.ruleOffset(text, "mago.yaml", "lint", "other-rule"))
    }

    @Test fun `ignore comments strings unrelated sections and absent rules`() {
        val text = """
            # [linter.rules.no-global]
            example = '''
            [linter.rules]
            no-global = true
            '''
            [other.rules]
            no-global = true
            [linter.rules]
            another-rule = { message = "no-global = true" }
        """.trimIndent()
        assertNull(MagoConfigNavigation.ruleOffset(text, "mago.toml", "lint", "no-global"))
        assertNull(MagoConfigNavigation.ruleOffset(text, "mago.toml", "analyze", "another-rule"))
        assertNull(MagoConfigNavigation.ruleOffset(text, "mago.toml", "guard", "another-rule"))
        assertNull(MagoConfigNavigation.ruleOffset(text, "mago.toml", "lint", null))
    }

    @Test fun `json finds only exact rule in object path`() {
        val text = """{"other":{"linter":{"rules":{"no-global":{"enabled":true}}}},"linter":{"rules":{"no-global":{"enabled":false}}}}"""
        assertEquals(text.lastIndexOf("\"no-global\""), MagoConfigNavigation.ruleOffset(text, "mago.json", "lint", "no-global"))
        assertNull(MagoConfigNavigation.ruleOffset("""{"linter":{"rules":["no-global"]}}""", "mago.json", "lint", "no-global"))
    }

    private fun homeEnvironment(home: File): Map<String, String> =
        mapOf((if (File.separatorChar == '\\') "USERPROFILE" else "HOME") to home.path)
}
