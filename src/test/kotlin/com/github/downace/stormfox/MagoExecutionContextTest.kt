package com.github.downace.stormfox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.github.downace.stormfox.lint.MagoDiagnosticReport
import java.nio.file.Files

class MagoExecutionContextTest {
    @Test fun parsesArgumentsWithoutShell() {
        val directory = Files.createTempDirectory("mago project").toFile()
        val context = MagoExecutionContext("docker compose exec -T app mago \"two words\" 'literal value' \$PROJECT_DIR\$", directory)
        assertEquals(listOf("docker", "compose", "exec", "-T", "app", "mago", "two words", "literal value", "\$PROJECT_DIR\$"), context.command())
        assertEquals(listOf("C:\\tools\\mago.exe", "--flag"), MagoExecutionContext("C:\\tools\\mago.exe --flag", directory).command())
        assertEquals(listOf("C:\\Program Files\\mago.exe", "--flag"), MagoExecutionContext("\"C:\\Program Files\\mago.exe\" --flag", directory).command())
        assertEquals(listOf("docker", "-v", "\$PROJECT_DIR\$:/app", "\$HOME"), MagoExecutionContext("docker -v \"\$PROJECT_DIR\$:/app\" \$HOME", null).command())
        assertThrows(IllegalArgumentException::class.java) { MagoExecutionContext("mago \"broken", directory).command() }
        assertThrows(IllegalArgumentException::class.java) { MagoExecutionContext("", directory).command() }
    }

    @Test fun preservesLegacyPathAndMapsNestedPaths() {
        val root = Files.createTempDirectory("mago project").toFile()
        val executable = root.resolve("mago binary").apply { writeText("") }
        assertEquals(listOf(executable.absolutePath), MagoExecutionContext(executable.absolutePath, root).command())
        val context = MagoExecutionContext("mago", root, "/workspace/project")
        assertEquals("/workspace/project", context.toExecution(root.absolutePath))
        assertEquals("relative/file.php", MagoExecutionContext("mago", root).toHost("relative/file.php"))
        assertEquals("/workspace/project/config/mago.toml", context.toExecution("config/mago.toml"))
        assertEquals(root.resolve("src/file.php").absolutePath, context.toHost("../src/file.php", "/workspace/project/config"))
        assertThrows(IllegalArgumentException::class.java) { context.toHost("/workspace/other/file.php") }
        assertThrows(IllegalArgumentException::class.java) { context.toHost("/workspace/project/C:/outside.php") }
        assertThrows(IllegalArgumentException::class.java) { context.toHost("/workspace/project/..\\outside.php") }
        assertThrows(IllegalArgumentException::class.java) { context.toExecution("../outside.php") }
    }

    @Test fun mapsReportPathsAgainstNestedWorkspace() {
        val root = Files.createTempDirectory("mago project").toFile()
        val context = MagoExecutionContext("mago", root, "/app")
        val workspace = root.resolve("nested")
        val file = workspace.resolve("source.php")
        val json = """{"issues":[{"level":"Error","message":"Issue","annotations":[{"kind":"Primary","span":{"file_id":{"name":"/app/nested/source.php"},"start":{"offset":0},"end":{"offset":1}}},{"kind":"Secondary","span":{"file_id":{"name":"source.php"},"start":{"offset":0},"end":{"offset":1}}}]}]}"""
        val result = MagoDiagnosticReport.parse(json, "abc", file.path, workspace) { context.toHost(it, "/app/nested") }
        assertEquals(1, result.size)
        assertEquals(file.absolutePath, result.single().relatedLocations.single().path)
    }
}
