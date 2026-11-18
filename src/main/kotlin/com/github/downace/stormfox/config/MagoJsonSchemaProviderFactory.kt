package com.github.downace.stormfox.config

import com.github.downace.stormfox.MagoVersionListener
import com.github.downace.stormfox.MagoVersionService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.http.HttpVirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

internal enum class SchemaDownloadEvent { STALE, SUCCESS, FAILED_LATEST, FAILED_VERSION }

internal fun schemaDownloadEvent(currentUrl: String, requestUrl: String, latestUrl: String, succeeded: Boolean): SchemaDownloadEvent =
    when {
        currentUrl != requestUrl -> SchemaDownloadEvent.STALE
        succeeded -> SchemaDownloadEvent.SUCCESS
        requestUrl == latestUrl -> SchemaDownloadEvent.FAILED_LATEST
        else -> SchemaDownloadEvent.FAILED_VERSION
    }

class MagoJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf(project.getService(MagoSchemaProjectService::class.java).provider())
}

@Service(Service.Level.PROJECT)
internal class MagoSchemaProjectService(private val project: Project, private val scope: CoroutineScope) {
    private var instance: MagoJsonSchemaProvider? = null

    @Synchronized
    fun provider(): MagoJsonSchemaProvider {
        instance?.let { return it }
        project.messageBus.connect(scope).subscribe(
            MagoVersionListener.TOPIC,
            object : MagoVersionListener {
                override fun versionChanged() = resetSchema()
            },
        )
        return MagoJsonSchemaProvider(project, onChange = ::resetSchema).also { instance = it }
    }

    private fun resetSchema() {
        scope.launch(Dispatchers.EDT) {
            if (!project.isDisposed) JsonSchemaService.Impl.get(project).reset()
        }
    }
}

class MagoJsonSchemaProvider internal constructor(
    private val project: Project,
    private val baseUrl: String = "https://mago.carthage.software",
    private val version: () -> String? = { MagoVersionService.getInstance(project).cachedVersion(project.basePath?.let(::File))?.toString() },
    private val onChange: () -> Unit = {
        if (!project.isDisposed) JsonSchemaService.Impl.get(project).reset()
    },
) : JsonSchemaFileProvider {
    private enum class Download { REQUESTED, SUCCEEDED, FAILED }
    private val downloads = mutableMapOf<String, Download>()

    override fun isAvailable(file: VirtualFile): Boolean = MagoConfigFiles.matches(file)
    override fun getName(): String = "Mago"
    override fun getSchemaType(): SchemaType = SchemaType.remoteSchema

    private fun versionUrl(): String = "$baseUrl/${version() ?: "latest"}/schema.json"
    private val latestUrl: String get() = "$baseUrl/latest/schema.json"

    @Synchronized
    private fun selectedUrl(): String {
        val preferred = versionUrl()
        return if (downloads[preferred] == Download.FAILED) latestUrl else preferred
    }

    override fun getRemoteSource(): String = selectedUrl()

    override fun getSchemaFile(): VirtualFile? {
        val url = selectedUrl()
        val remote = VirtualFileManager.getInstance().findFileByUrl(url) as? HttpVirtualFile ?: return null
        synchronized(this) {
            if (downloads[url] == Download.FAILED) return null
            remote.fileInfo?.localFile?.takeIf { it.isValid && it.length > 0 }?.let {
                downloads[url] = Download.SUCCEEDED
                return it
            }
            if (downloads.containsKey(url)) return null
            downloads[url] = Download.REQUESTED
            LOG.debug("Requesting schema download: $url")
        }
        remote.refresh(true, false) {
            val changed = synchronized(this) {
                // Do not let an obsolete request select a fallback for the current version.
                val local = remote.fileInfo?.localFile
                val event = schemaDownloadEvent(selectedUrl(), url, latestUrl, local != null && local.isValid && local.length > 0)
                LOG.debug("Schema download result for $url: $event")
                if (event == SchemaDownloadEvent.STALE) {
                    downloads.remove(url)
                    false
                } else {
                    downloads[url] = if (event == SchemaDownloadEvent.SUCCESS) Download.SUCCEEDED else Download.FAILED
                    when (event) {
                        SchemaDownloadEvent.FAILED_VERSION -> {
                            val versionLabel = url.removePrefix("$baseUrl/").removeSuffix("/schema.json")
                                .takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) } ?: "unknown"
                            LOG.warn("Schema download failed for version $versionLabel")
                            LOG.info("Falling back to latest schema")
                        }
                        SchemaDownloadEvent.FAILED_LATEST -> LOG.warn("Latest schema download failed")
                        else -> Unit
                    }
                    true
                }
            }
            if (changed && !project.isDisposed) onChange()
        }
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(MagoJsonSchemaProvider::class.java)
    }
}
