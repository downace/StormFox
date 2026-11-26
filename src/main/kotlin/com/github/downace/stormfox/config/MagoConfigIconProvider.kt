package com.github.downace.stormfox.config

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI
import javax.swing.Icon

class MagoConfigIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (MagoConfigFiles.matches(file)) icon else null

    private companion object {
        private val icon: Icon by lazy {
            val resource = requireNotNull(MagoConfigIconProvider::class.java.getResource("/icons/magoConfig.svg"))
            // The resource URL's handler can retain the plugin classloader; rebuild it with a JDK handler.
            val url = URI(resource.toExternalForm()).toURL()
            requireNotNull(IconLoader.findIcon(url))
        }
    }
}
