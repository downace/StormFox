package com.github.downace.stormfox.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.http.HttpVirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import java.util.concurrent.atomic.AtomicBoolean

class MagoJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(MagoJsonSchemaProvider(project))
}

class MagoJsonSchemaProvider internal constructor(
    private val project: Project,
    private val schemaUrl: String = SCHEMA_URL,
) : JsonSchemaFileProvider {
    private val downloadRequested = AtomicBoolean()
    override fun isAvailable(file: VirtualFile): Boolean = MagoConfigFiles.matches(file)

    override fun getName(): String = "Mago"

    override fun getSchemaType(): SchemaType = SchemaType.remoteSchema

    override fun getRemoteSource(): String = schemaUrl

    override fun getSchemaFile(): VirtualFile? {
        val remote = VirtualFileManager.getInstance().findFileByUrl(schemaUrl) as? HttpVirtualFile ?: return null
        remote.fileInfo?.localFile?.takeIf { it.isValid && it.length > 0 }?.let { return it }
        if (downloadRequested.compareAndSet(false, true)) {
            remote.refresh(true, false) {
                if (remote.fileInfo?.localFile?.length?.let { it > 0 } != true) {
                    downloadRequested.set(false)
                } else if (!project.isDisposed) {
                    JsonSchemaService.Impl.get(project).reset()
                }
            }
        }
        return null
    }

    private companion object {
        const val SCHEMA_URL = "https://mago.carthage.software/latest/schema.json"
    }
}
