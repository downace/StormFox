package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Service(Service.Level.APP)
internal class MagoVersionService(private val scope: CoroutineScope) {
    private var generation = 0L
    private var path = ""
    private var version: MagoVersion? = null
    private var initialized = false

    @Synchronized
    fun cachedVersion(): MagoVersion? = version

    /** Called synchronously on a configured-path change; never runs a process on the caller. */
    fun refresh(binaryPath: String) {
        val (token, changed) = synchronized(this) { beginLocked(binaryPath) }
        if (changed) publishChange()
        launchRefresh(binaryPath, token)
    }

    private fun launchRefresh(binaryPath: String, token: Long) {
        if (binaryPath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val result = MagoValidation.checkBinaryUncached(binaryPath)
            finish(token, binaryPath, result)
        }
    }

    /** Also called for projects already open when the plugin is dynamically loaded. */
    fun ensureInitialized() {
        val configuredPath = MagoSettings.getInstance().state.magoPath
        val started = synchronized(this) {
            if (initialized) null else beginLocked(configuredPath)
        } ?: return
        if (started.second) publishChange()
        launchRefresh(configuredPath, started.first)
    }

    /** Explicit validation can reuse its own process result and supersede an older refresh. */
    fun beginValidation(binaryPath: String): Long? {
        val settings = MagoSettings.getInstance()
        val started = synchronized(this) {
            if (settings.state.magoPath.trim() != binaryPath.trim()) null else beginLocked(binaryPath)
        } ?: return null
        if (started.second) publishChange()
        return started.first
    }

    private fun beginLocked(binaryPath: String): Pair<Long, Boolean> {
        val nextPath = binaryPath.trim()
        val changed = path != nextPath && version != null
        initialized = true
        if (path != nextPath) version = null
        path = nextPath
        return ++generation to changed
    }

    fun finish(token: Long?, binaryPath: String, result: MagoValidation.Result) {
        val changed = synchronized(this) {
            if (token == null || generation != token || path != binaryPath.trim() ||
                MagoSettings.getInstance().state.magoPath.trim() != path) return
            val next = (result as? MagoValidation.Result.Success)?.let { MagoVersion.parse(it.message) }
            if (next == version) false else {
                version = next
                true
            }
        }
        if (changed) publishChange()
    }

    private fun publishChange() {
        ApplicationManager.getApplication().messageBus.syncPublisher(MagoVersionListener.TOPIC).versionChanged()
    }

    companion object {
        fun getInstance(): MagoVersionService =
            ApplicationManager.getApplication().getService(MagoVersionService::class.java)
    }
}

internal interface MagoVersionListener {
    fun versionChanged()

    companion object {
        @JvmField
        val TOPIC = Topic.create("Mago cached version changed", MagoVersionListener::class.java)
    }
}

internal class MagoVersionProjectStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        MagoVersionService.getInstance().ensureInitialized()
    }
}
