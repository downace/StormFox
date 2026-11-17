package com.github.downace.stormfox

import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.SortingException
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MagoStatusWidgetOrderTest {
    private data class Widget(
        override val orderId: String,
        override val order: LoadingOrder,
    ) : LoadingOrder.Orderable

    private fun descriptorWidget(): Widget {
        val document = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val elements = document.getElementsByTagName("statusBarWidgetFactory")
        val element = (0 until elements.length)
            .map { elements.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("implementation") == "com.github.downace.stormfox.status.MagoStatusBarWidgetFactory" }
        return Widget(element.getAttribute("id"), LoadingOrder.readOrder(element.getAttribute("order")))
    }

    @Test
    fun `descriptor sorts before first PHP widget and ordinary widget`() {
        val mago = descriptorWidget()
        val php = Widget("php.language.level.widget", LoadingOrder.FIRST)
        val ordinary = Widget("ordinary", LoadingOrder.ANY)
        val widgets = mutableListOf(ordinary, php, mago)
        LoadingOrder.sortByLoadingOrder(widgets)
        assertEquals(listOf(mago, php, ordinary), widgets)
    }

    @Test
    fun `old before constraint conflicts with first PHP widget`() {
        val mago = Widget("mago", LoadingOrder.readOrder("before php.language.level.widget"))
        val php = Widget("php.language.level.widget", LoadingOrder.FIRST)
        assertThrows(SortingException::class.java) {
            LoadingOrder.sortByLoadingOrder(mutableListOf(mago, php))
        }
    }

    @Test
    fun `missing PHP anchor does not prevent sorting`() {
        val mago = descriptorWidget()
        val ordinary = Widget("ordinary", LoadingOrder.ANY)
        val widgets = mutableListOf(ordinary, mago)
        LoadingOrder.sortByLoadingOrder(widgets)
        assertEquals(listOf(mago, ordinary), widgets)
    }
}
