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
                assertEquals(16, icon!!.iconWidth)
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
        val provider = MagoJsonSchemaProviderFactory().getProviders(project).single()
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
        server.createContext("/schema.json") { exchange ->
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
            val provider = MagoJsonSchemaProvider(project, "http://127.0.0.1:${server.address.port}/schema.json")
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
}
