package com.github.downace.stormfox

import com.github.downace.stormfox.format.MagoValidation
import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.settings.MagoSettingsSnapshot
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File

@Service(Service.Level.APP)
internal class MagoVersionService(private val scope: CoroutineScope) {
    private data class Key(val command: String, val directory: String?, val root: String)
    private data class Entry(val token: Long, var version: MagoVersion? = null)
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var generation = 0L
    private var pendingRefresh: Job? = null

    @org.jetbrains.annotations.TestOnly
    internal var projectDirectoriesForTest: (() -> List<File>)? = null
    @org.jetbrains.annotations.TestOnly
    internal var probeForTest: ((String, File?, String) -> MagoValidation.Result)? = null

    private fun key(command: String, directory: File?, root: String) =
        Key(command.trim(), directory?.toPath()?.toAbsolutePath()?.normalize()?.toString(), root.trim())

    private fun current(directory: File?): Key = MagoSettings.getInstance().state.snapshot().let {
        key(it.command, directory, it.executionRoot)
    }

    fun cachedVersion(directory: File? = null): MagoVersion? {
        val context = current(directory)
        return synchronized(this) { entries[context]?.version }
    }

    /** Invalidate immediately, then coalesce settings changes before probing open projects. */
    fun refresh(snapshot: MagoSettingsSnapshot) {
        val binaryPath = snapshot.command
        val root = snapshot.executionRoot
        val changed = synchronized(this) {
            val hadVersions = entries.values.any { it.version != null }
            entries.clear()
            generation++
            pendingRefresh?.cancel(null)
            pendingRefresh = scope.launch {
                delay(300)
                if (current(null) != key(binaryPath, null, root)) return@launch
                val directories = projectDirectoriesForTest?.invoke() ?: ProjectManager.getInstance().openProjects
                    .filterNot { it.isDisposed }.mapNotNull { it.basePath?.let(::File) }
                directories.filter { it.isDirectory }.distinctBy { it.toPath().toAbsolutePath().normalize() }
                    .forEach { ensureInitialized(it) }
                if (safeWithoutProject(binaryPath, root)) ensureInitialized()
            }
            hadVersions
        }
        if (changed) publishChange()
    }

    private fun launchRefresh(command: String, directory: File?, root: String, token: Long) {
        if (command.isBlank() || directory == null && !safeWithoutProject(command, root)) return
        scope.launch(Dispatchers.IO) {
            if (directory != null && !directory.isDirectory) return@launch
            val result = probeForTest?.invoke(command, directory, root)
                ?: MagoValidation.checkBinaryUncached(command, directory, root)
            finish(token, command, result, directory, root)
        }
    }

    fun ensureInitialized(directory: File? = null) {
        val context = current(directory)
        val token = synchronized(this) {
            if (entries.containsKey(context)) return
            beginLocked(context)
        }
        launchRefresh(context.command, directory, context.root, token)
    }

    fun beginValidation(binaryPath: String, directory: File? = null, executionRoot: String = MagoSettings.getInstance().state.executionRoot): Long? {
        val context = key(binaryPath, directory, executionRoot)
        val settingsContext = current(directory)
        return synchronized(this) {
            if (context != settingsContext) null else beginLocked(context)
        }
    }

    private fun beginLocked(context: Key): Long {
        val previous = entries[context]?.version
        val token = ++generation
        entries[context] = Entry(token, previous)
        while (entries.size > 64) entries.remove(entries.keys.first())
        return token
    }

    fun finish(token: Long?, binaryPath: String, result: MagoValidation.Result, directory: File? = null,
               executionRoot: String = MagoSettings.getInstance().state.executionRoot) {
        val context = key(binaryPath, directory, executionRoot)
        val settingsContext = current(directory)
        val changed = synchronized(this) {
            val entry = entries[context]
            if (token == null || entry?.token != token || context != settingsContext) return
            val next = (result as? MagoValidation.Result.Success)?.let { MagoVersion.parse(it.message) }
            if (next == entry.version) false else { entry.version = next; true }
        }
        if (changed) publishChange()
    }

    private fun publishChange() {
        ApplicationManager.getApplication().messageBus.syncPublisher(MagoVersionListener.TOPIC).versionChanged()
    }

    companion object {
        private fun safeWithoutProject(command: String, root: String): Boolean {
            if (root.isNotBlank()) return false
            val value = command.trim()
            if (value.isEmpty()) return false
            if (File(value).isAbsolute && File(value).isFile) return true
            return value.none { it.isWhitespace() || it == '"' || it == '\'' || it == '/' || it == '\\' }
        }

        fun getInstance(): MagoVersionService =
            ApplicationManager.getApplication().getService(MagoVersionService::class.java)
    }
}

internal interface MagoVersionListener {
    fun versionChanged()
    companion object {
        @JvmField val TOPIC = Topic.create("Mago cached version changed", MagoVersionListener::class.java)
    }
}

internal class MagoVersionProjectStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        MagoVersionService.getInstance().ensureInitialized(project.basePath?.let(::File))
    }
}
