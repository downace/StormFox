package com.github.downace.stormfox

import com.github.downace.stormfox.config.MagoConfigIconProvider
import com.github.downace.stormfox.config.MagoJsonSchemaProviderFactory
import com.github.downace.stormfox.config.MagoJsonSchemaProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.PlatformTestUtil
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

class MagoConfigProvidersTest : BasePlatformTestCase() {
    fun testProvidersAreRegistered() {
        assertTrue(ExtensionPointName<FileIconProvider>("com.intellij.fileIconProvider").extensionList
            .any { it is MagoConfigIconProvider })
        assertTrue(ExtensionPointName<JsonSchemaProviderFactory>("JavaScript.JsonSchema.ProviderFactory").extensionList
            .any { it is MagoJsonSchemaProviderFactory })
    }

    fun testAllConfigNamesHaveIconAndSchema() {
        IconLoader.activate()
        val iconProvider = MagoConfigIconProvider()
        val schemaProvider = MagoJsonSchemaProviderFactory().getProviders(project).single()
        for (extension in listOf("toml", "yaml", "yml", "json")) {
            for (name in listOf("mago.$extension", "mago.dist.$extension")) {
                val file = LightVirtualFile(name)
                val icon = iconProvider.getIcon(file, 0, project)
                assertNotNull(name, icon)
                assertEquals(15, icon!!.iconWidth)
                assertEquals(16, icon.iconHeight)
                assertTrue(name, schemaProvider.isAvailable(file))
            }
        }
    }

    fun testNearMissesAndDirectoriesAreNotClaimed() {
        val iconProvider = MagoConfigIconProvider()
        val schemaProvider = MagoJsonSchemaProviderFactory().getProviders(project).single()
        val files = listOf(
            "Mago.toml", "mago.TOML", "mago.Dist.json", "mago.dist.JSON", "mago.json.bak",
            "other.toml", "mago.dist.dist.yaml", ".mago.toml", "mago.xml", "mago", "mago.toml.extra",
        ).map { LightVirtualFile(it) } + myFixture.tempDirFixture.findOrCreateDir("mago.toml")
        for (file in files) {
            assertNull(file.name, iconProvider.getIcon(file, 0, project))
            assertFalse(file.name, schemaProvider.isAvailable(file))
        }
    }

    fun testSchemaUsesOfficialRemoteSource() {
        val provider = MagoJsonSchemaProvider(project, version = { null })
        assertEquals("Mago", provider.name)
        assertEquals("https://mago.carthage.software/latest/schema.json", provider.remoteSource)
        assertEquals(SchemaType.remoteSchema, provider.schemaType)
    }

    fun testRemoteSchemaIsDownloadedBeforeItIsExposed() {
        val bytes = """{"type":"object","properties":{"formatter":{"type":"object"}}}""".toByteArray()
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/latest/schema.json") { exchange ->
            requests.incrementAndGet()
            requested.countDown()
            if (release.await(20, TimeUnit.SECONDS)) {
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        try {
            val provider = MagoJsonSchemaProvider(project, "http://127.0.0.1:${server.address.port}", { null })
            assertNull(provider.schemaFile)
            assertTrue(requested.await(10, TimeUnit.SECONDS))
            repeat(5) { assertNull(provider.schemaFile) }
            release.countDown()
            var downloaded: VirtualFile? = null
            PlatformTestUtil.waitWithEventsDispatching("Schema download", {
                downloaded = provider.schemaFile
                downloaded != null
            }, 20)
            assertTrue(bytes.contentEquals(downloaded!!.contentsToByteArray()))
            val schema = JsonSchemaService.Impl.get(project).getSchemaObjectForSchemaFile(downloaded!!)
            assertNotNull(schema)
            assertNotNull(schema!!.getPropertyByName("formatter"))
            repeat(5) { assertEquals(downloaded, provider.schemaFile) }
            assertEquals(1, requests.get())
        }
        finally {
            release.countDown()
            server.stop(0)
        }
    }

    fun testVersionedSchemasFallbackAndBoundedFailures() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val rule = when (path) {
                "/1.59.0/schema.json" -> "old-rule"
                "/1.60.0/schema.json" -> "no-unused-return-value"
                "/latest/schema.json" -> "latest-rule"
                else -> null
            }
            if (rule == null) exchange.sendResponseHeaders(404, -1)
            else {
                val bytes = """{"type":"object","properties":{"$rule":{"type":"boolean"}}}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            var version: String? = "1.59.0"
            val provider = MagoJsonSchemaProvider(project, base, { version }, {})
            fun waitFile(): VirtualFile {
                var file: VirtualFile? = null
                PlatformTestUtil.waitWithEventsDispatching("Schema download", {
                    file = provider.schemaFile
                    file != null
                }, 20)
                return file!!
            }
            assertEquals("$base/1.59.0/schema.json", provider.remoteSource)
            assertTrue(String(waitFile().contentsToByteArray()).contains("old-rule"))
            version = "1.60.0"
            assertEquals("$base/1.60.0/schema.json", provider.remoteSource)
            assertTrue(String(waitFile().contentsToByteArray()).contains("no-unused-return-value"))
            version = "1.61.0"
            assertEquals("$base/1.61.0/schema.json", provider.remoteSource)
            assertTrue(String(waitFile().contentsToByteArray()).contains("latest-rule"))
            assertEquals("$base/latest/schema.json", provider.remoteSource)
            version = null
            assertEquals("$base/latest/schema.json", provider.remoteSource)
            assertTrue(String(waitFile().contentsToByteArray()).contains("latest-rule"))
            repeat(5) { assertNotNull(provider.schemaFile) }
            for (path in listOf("/1.59.0/schema.json", "/1.60.0/schema.json", "/1.61.0/schema.json", "/latest/schema.json"))
                assertEquals(path, 1, requests[path]?.get())
        } finally {
            server.stop(0)
        }
    }

    fun testPendingOldVersionCannotSelectFallbackAndLatestFailureDoesNotSpin() {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            if (exchange.requestURI.path == "/1.59.0/schema.json") {
                requested.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            var version: String? = "1.59.0"
            val changes = AtomicInteger()
            val provider = MagoJsonSchemaProvider(project, base, { version }, { changes.incrementAndGet() })
            assertNull(provider.schemaFile)
            assertTrue(requested.await(10, TimeUnit.SECONDS))
            version = "1.60.1"
            release.countDown()
            assertEquals("$base/1.60.1/schema.json", provider.remoteSource)
            PlatformTestUtil.waitWithEventsDispatching("Version fallback", {
                provider.schemaFile
                provider.remoteSource == "$base/latest/schema.json"
            }, 20)
            PlatformTestUtil.waitWithEventsDispatching("Latest failure", {
                provider.schemaFile
                changes.get() >= 2
            }, 20)
            repeat(10) { assertNull(provider.schemaFile) }
            assertEquals(3, requests.get())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
