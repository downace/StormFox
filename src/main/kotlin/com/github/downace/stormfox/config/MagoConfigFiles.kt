package com.github.downace.stormfox.config

import com.intellij.openapi.vfs.VirtualFile

internal object MagoConfigFiles {
    // https://mago.carthage.software/latest/en/guide/configuration/#discovery
    private val names = setOf("toml", "yaml", "yml", "json").flatMap { extension ->
        listOf("mago.$extension", "mago.dist.$extension")
    }.toSet()

    fun matches(file: VirtualFile): Boolean = !file.isDirectory && file.name in names
}
