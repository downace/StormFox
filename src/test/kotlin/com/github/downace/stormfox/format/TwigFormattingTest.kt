package com.github.downace.stormfox.format

import com.github.downace.stormfox.MagoVersion
import com.github.downace.stormfox.MagoVersionService
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class TwigFormattingTest {
    @Test fun runnerProbesAndGatesTwigAndMapsPaths() {
        object : Fixture() {}.apply { name = "testRunner" }.runBare()
    }

    private fun checkRunner() {
        val root = Files.createTempDirectory("twig-format-").toFile()
        try {
            val marker = File(root, "arguments")
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath.replace('\\', '/')
            val resource = TwigFormatterProbe::class.java.getResource("TwigFormatterProbe.class")!!
            val classpath = File(resource.toURI()).absolutePath.removeSuffix(
                TwigFormatterProbe::class.java.name.replace('.', File.separatorChar) + ".class",
            ).replace('\\', '/')
            fun command(version: String) =
                "\"$java\" -cp \"$classpath\" ${TwigFormatterProbe::class.java.name} $version \"${marker.path.replace('\\', '/')}\""
            val path = File(root, "src/view.TWIG").absolutePath
            assertTrue(MagoRunner.format(command("1.59.0"), root, "", path, "buffer") is MagoRunner.Result.Failure)
            assertFalse(marker.exists())
            assertEquals(MagoRunner.Result.Success("buffer"),
                MagoRunner.format(command("1.60.0"), root, "mago.toml", path, "buffer", executionRoot = "/app"))
            assertEquals(listOf("--config", "/app/mago.toml", "twig:fmt", "--stdin-input", "--stdin-filepath", "/app/src/view.TWIG"),
                marker.readLines())
            marker.delete()
            assertTrue(MagoRunner.format(command("1.60.0"), root, "", path, "buffer",
                cachedVersion = MagoVersion(1, 59, 0)) is MagoRunner.Result.Failure)
            assertFalse(marker.exists())
            assertEquals(MagoRunner.Result.Success("buffer"),
                MagoRunner.format(command("1.60.0"), root, "", File(root, "a.php").path, "buffer"))
            assertEquals("format", marker.readLines().first())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun realTwigFormatsBufferAndPreservesExcludedAndDisk() {
        object : Fixture() {}.apply { name = "testRealRunner" }.runBare()
    }

    private fun checkRealRunner() {
        val binary = System.getenv("STORMFOX_TWIG_TEST_BINARY")
        assumeTrue("STORMFOX_TWIG_TEST_BINARY is not set", !binary.isNullOrBlank())
        val root = Files.createTempDirectory("twig-real-").toFile()
        try {
            val source = File(root, "view.twig").apply { writeText("disk") }
            val excluded = File(root, "excluded.twig").apply { writeText("disk excluded") }
            File(root, "mago.toml").writeText("[source]\npaths = ['.']\nexcludes = ['excluded.twig']\n")
            val buffer = "{{  value  }}"
            val result = MagoRunner.format(binary!!, root, "", source.path, buffer)
            assertTrue(result.toString(), result is MagoRunner.Result.Success)
            assertNotEquals(buffer, (result as MagoRunner.Result.Success).text)
            assertEquals("disk", source.readText())
            assertEquals(MagoRunner.Result.Success(buffer),
                MagoRunner.format(binary, root, "", excluded.path, buffer))
            assertEquals("disk excluded", excluded.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun serviceSelectsOnlySupportedTwigAndPhp() {
        object : Fixture() {}.apply { name = "testSelection" }.runBare()
    }

    @Test fun taskReportsProbeFailureAndRecoversCache() {
        object : Fixture() {}.apply { name = "testTaskRecovery" }.runBare()
    }

    inner abstract class Fixture : BasePlatformTestCase() {
        fun testRunner() = checkRunner()
        fun testRealRunner() = checkRealRunner()
        fun testTaskRecovery() {
            val settings = MagoSettings.getInstance(project).state
            val previous = settings.magoPath
            val root = Files.createTempDirectory("twig-task-").toFile()
            val directory = root
            val versions = MagoVersionService.getInstance(project)
            var delivered = 0
            val request = Proxy.newProxyInstance(AsyncFormattingRequest::class.java.classLoader,
                arrayOf(AsyncFormattingRequest::class.java)) { _, method, _ ->
                if (method.name == "onTextReady") { delivered++; null }
                else throw AssertionError("Unexpected request access: ${method.name}")
            } as AsyncFormattingRequest
            try {
                val missing = File(root, "missing-mago").path
                settings.magoPath = missing
                val task = MagoFormattingService.MagoFormattingTask(request, missing, "", directory,
                    File(root, "view.twig").path, "{{ x }}", twig = true, project = { project })
                task.run()
                assertEquals(1, delivered)
                assertNotNull(versions.cachedFailure(missing, directory, settings.executionRoot))
                assertTrue(NotificationsManager.getNotificationsManager()
                    .getNotificationsOfType(Notification::class.java, project)
                    .any { it.groupId == MAGO_NOTIFICATION_GROUP && it.content.contains("Cannot start mago") })
                val java = File(System.getProperty("java.home"), "bin/java").absolutePath.replace('\\', '/')
                val resource = TwigFormatterProbe::class.java.getResource("TwigFormatterProbe.class")!!
                val classpath = File(resource.toURI()).absolutePath.removeSuffix(
                    TwigFormatterProbe::class.java.name.replace('.', File.separatorChar) + ".class").replace('\\', '/')
                val command = "\"$java\" -cp \"$classpath\" ${TwigFormatterProbe::class.java.name} 1.60.0 \"${File(root, "args").path.replace('\\', '/')}\""
                settings.magoPath = command
                val token = versions.beginValidation(command, directory)!!
                versions.finish(token, command, MagoValidation.Result.Failure("initial failure"), directory)
                MagoFormattingService.MagoFormattingTask(request, command, "", directory,
                    File(root, "view.twig").path, "{{ x }}", twig = true, project = { project }).run()
                assertEquals(versions.cachedFailure(command, directory, settings.executionRoot), MagoVersion(1, 60, 0), versions.cachedVersion(command, directory, settings.executionRoot))
                assertNull(versions.cachedFailure(command, directory, settings.executionRoot))
                val oldCommand = command.replace("1.60.0", "1.59.0")
                settings.magoPath = oldCommand
                val oldFailure = versions.beginValidation(oldCommand, directory)!!
                versions.finish(oldFailure, oldCommand, MagoValidation.Result.Failure("initial failure"), directory)
                MagoFormattingService.MagoFormattingTask(request, oldCommand, "", directory,
                    File(root, "view.twig").path, "{{ x }}", twig = true, project = { project }).run()
                assertEquals(MagoVersion(1, 59, 0), versions.cachedVersion(oldCommand, directory, settings.executionRoot))
                assertNull(versions.cachedFailure(oldCommand, directory, settings.executionRoot))
                // Selection has already happened; this request reports the unsupported version
                // rather than pretending that the IDE formatter handled it.
                assertTrue(NotificationsManager.getNotificationsManager()
                    .getNotificationsOfType(Notification::class.java, project)
                    .any { it.content.contains("Twig formatting requires Mago 1.60.0 or newer") })

                val invalid = "\"unterminated"
                settings.magoPath = invalid
                MagoFormattingService.MagoFormattingTask(request, invalid, "", directory,
                    File(root, "view.twig").path, "{{ x }}", twig = true, project = { project }).run()
                assertFalse(versions.cachedFailure(invalid, directory, settings.executionRoot).isNullOrBlank())

                val marker = File(root, "started")
                val waiting = "\"$java\" -cp \"$classpath\" ${WaitingTwigVersionProbe::class.java.name} \"${marker.path.replace('\\', '/')}\""
                settings.magoPath = waiting
                val backgroundStarted = CountDownLatch(1)
                val releaseBackground = CountDownLatch(1)
                val backgroundFinished = CountDownLatch(1)
                versions.probeForTest = { _, _, _ ->
                    backgroundStarted.countDown()
                    check(releaseBackground.await(10, TimeUnit.SECONDS))
                    MagoValidation.Result.Success("mago 1.60.0")
                }
                versions.probeFinishedForTest = { backgroundFinished.countDown() }
                versions.ensureInitialized(directory)
                assertTrue(backgroundStarted.await(10, TimeUnit.SECONDS))
                val waitingTask = MagoFormattingService.MagoFormattingTask(request, waiting, "", directory,
                    File(root, "view.twig").path, "{{ x }}", twig = true, project = { project })
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val running = executor.submit { waitingTask.run() }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(10)
                    assertTrue("formatter version probe did not start", marker.exists())
                    waitingTask.cancel()
                    try { running.get(10, TimeUnit.SECONDS) } catch (_: java.util.concurrent.ExecutionException) { }
                    releaseBackground.countDown()
                    assertTrue(backgroundFinished.await(10, TimeUnit.SECONDS))
                    assertEquals(MagoVersion(1, 60, 0), versions.cachedVersion(waiting, directory, settings.executionRoot))
                } finally {
                    releaseBackground.countDown()
                    versions.probeForTest = null
                    versions.probeFinishedForTest = null
                    waitingTask.cancel()
                    executor.shutdownNow()
                }
            } finally {
                settings.magoPath = previous
                root.deleteRecursively()
            }
        }
        fun testSelection() {
            val settings = MagoSettings.getInstance(project).state
            val previousEnabled = settings.enabled
            val previousTwigEnabled = settings.twigEnabled
            val previousPath = settings.magoPath
            try {
                settings.enabled = true
                settings.twigEnabled = null
                settings.magoPath = "twig-selection-test"
                val versions = MagoVersionService.getInstance(project)
                val directory = project.basePath?.let(::File)
                val service = MagoFormattingService()
                val twig = myFixture.configureByText("view.TWIG", "{{ value }}")
                val token = versions.beginValidation(settings.magoPath, directory)!!
                assertTrue(service.canFormat(twig))
                versions.finish(token, settings.magoPath, MagoValidation.Result.Failure("probe failed"), directory)
                assertTrue(service.canFormat(twig))
                assertTrue(MagoRunner.format(settings.magoPath, directory, "", "view.twig", "{{ value }}") is MagoRunner.Result.Failure)
                val oldToken = versions.beginValidation(settings.magoPath, directory)!!
                versions.finish(oldToken, settings.magoPath, MagoValidation.Result.Success("mago 1.59.0"), directory)
                assertFalse(service.canFormat(twig))
                val token2 = versions.beginValidation(settings.magoPath, directory)!!
                versions.finish(token2, settings.magoPath, MagoValidation.Result.Success("mago 1.60.0"), directory)
                assertTrue(service.canFormat(twig))
                assertFalse(service.canFormat(myFixture.configureByText("other.txt", "plain")))
                val php = myFixture.configureByText("other.php", "<?php echo 1;")
                for (phpEnabled in listOf(false, true)) {
                    for (twigEnabled in listOf(false, true)) {
                        settings.enabled = phpEnabled
                        settings.twigEnabled = twigEnabled
                        assertEquals(phpEnabled, service.canFormat(php))
                        assertEquals(twigEnabled, service.canFormat(twig))
                    }
                    settings.twigEnabled = null
                    assertEquals(phpEnabled, service.canFormat(php))
                    assertEquals(phpEnabled, service.canFormat(twig))
                }
            } finally {
                settings.enabled = previousEnabled
                settings.twigEnabled = previousTwigEnabled
                settings.magoPath = previousPath
            }
        }
    }
}
