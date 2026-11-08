package com.github.downace.stormfox.config

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI
import javax.swing.Icon

class MagoConfigIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (MagoConfigFiles.matches(file)) ICON else null

    private companion object {
        val resource = requireNotNull(MagoConfigIconProvider::class.java.getResource("/icons/magoConfig.svg"))
        val url = URI(resource.toExternalForm()).toURL()
        val ICON = requireNotNull(IconLoader.findIcon(url))
    }
}
