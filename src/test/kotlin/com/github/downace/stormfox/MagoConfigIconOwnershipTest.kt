package com.github.downace.stormfox

import com.github.downace.stormfox.config.MagoConfigIconProvider
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.lang.reflect.Modifier
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

class MagoConfigIconOwnershipTest : BasePlatformTestCase() {
    fun testCachedIconLoadersHaveNoPluginOwner() {
        IconLoader.activate()
        val icon = requireNotNull(MagoConfigIconProvider().getIcon(LightVirtualFile("mago.toml"), 0, project))
        assertOwnerlessIcon(icon, "/icons/magoConfig.svg")
        assertSame(
            icon,
            MagoConfigIconProvider().getIcon(LightVirtualFile("mago.toml"), 0, project)
        )
    }

    private fun assertOwnerlessIcon(icon: javax.swing.Icon, resourcePath: String) {
        assertEquals(15, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
        val loaders = fields(icon).filter { it.name == "loader" || it.name == "originalLoader" }
        assertEquals(setOf("loader", "originalLoader"), loaders.map { it.name }.toSet())
        for (field in loaders) {
            field.isAccessible = true
            val loader = requireNotNull(field.get(icon))
            assertEquals("com.intellij.ui.icons.ImageDataByUrlLoader", loader.javaClass.name)
            val ownerFields = fields(loader).filter { it.name == "ownerClass" || it.name == "classLoader" }
            assertEquals(setOf("ownerClass", "classLoader"), ownerFields.map { it.name }.toSet())
            for (owner in ownerFields) {
                owner.isAccessible = true
                assertNull(owner.name, owner.get(loader))
            }
            val urlField = fields(loader).single { it.type == URL::class.java }
            urlField.isAccessible = true
            val url = urlField.get(loader) as URL
            assertTrue(url.toExternalForm().endsWith(resourcePath))
            assertEquals("sun.net.www.protocol.${url.protocol}.Handler", handler(url).javaClass.name)
            assertNotSame(MagoConfigIconProvider::class.java.classLoader, handler(url).javaClass.classLoader)
        }
    }

    fun testIconRendersIdenticallyInLightAndDark() {
        IconLoader.activate()
        val icon = requireNotNull(MagoConfigIconProvider().getIcon(LightVirtualFile("mago.toml"), 0, project))
        val images = listOf(false, true).map { dark ->
            val variant = IconLoader.getDarkIcon(icon, dark)
            val image = BufferedImage(variant.iconWidth, variant.iconHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                variant.paintIcon(null, graphics, 0, 0)
            } finally {
                graphics.dispose()
            }
            image
        }
        assertEquals(images[0].width, images[1].width)
        assertEquals(images[0].height, images[1].height)
        for (x in 0 until images[0].width) {
            for (y in 0 until images[0].height) {
                assertEquals("Pixel at ($x, $y)", images[0].getRGB(x, y), images[1].getRGB(x, y))
            }
        }
    }

    fun testUrlNormalizationDropsResourceHandler() {
        val resource = requireNotNull(MagoConfigIconProvider::class.java.getResource("/icons/magoConfig.svg"))
        val owner = object : ClassLoader(null) {}
        val customHandler = object : URLStreamHandler() {
            val resourceOwner = owner
            override fun openConnection(url: URL): URLConnection = error("Resource handler must not be retained")
        }
        val customResource = URL(null, resource.toExternalForm(), customHandler)
        assertSame(customHandler, handler(customResource))
        val normalized = URI(customResource.toExternalForm()).toURL()
        assertEquals(resource.toExternalForm(), normalized.toExternalForm())
        assertNotSame(customHandler, handler(normalized))
        assertTrue(normalized.openStream().use { it.readBytes() }.contentEquals(resource.openStream().use { it.readBytes() }))
    }

    private fun fields(value: Any) = generateSequence(value.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .filterNot { Modifier.isStatic(it.modifiers) }
        .toList()

    private fun handler(url: URL): Any = URL::class.java.getDeclaredField("handler").let {
        it.isAccessible = true
        requireNotNull(it.get(url))
    }
}
