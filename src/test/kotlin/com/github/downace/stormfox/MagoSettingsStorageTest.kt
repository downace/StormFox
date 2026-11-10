package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import org.junit.Assert.assertEquals
import org.junit.Test

class MagoSettingsStorageTest {
    @Test
    fun localExecutableAndConfigPathsDoNotRoam() {
        val storage = MagoSettings::class.java.getAnnotation(State::class.java).storages.single()
        assertEquals("mago.xml", storage.value)
        assertEquals(RoamingType.DISABLED, storage.roamingType)
    }
}
