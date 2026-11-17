package com.github.downace.stormfox

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.security.MessageDigest

/** Bounded, synchronized failure chains. Keys and messages must not contain process output. */
internal class MagoFailureLog(
    private val emit: (Level, String) -> Unit,
    private val capacity: Int = 128,
    private val debugEnabled: () -> Boolean = { true },
) {
    internal enum class Level { WARN, DEBUG, INFO }
    private data class Key(val context: String, val operation: String, val stage: String)
    private val failures = LinkedHashMap<Key, String>(16, 0.75f, true)

    init { require(capacity > 0) }

    @Synchronized
    fun failure(context: String, operation: String, stage: String, category: String, directory: String? = null) {
        val key = Key(context, operation, stage)
        val repeated = failures.put(key, category) == category
        while (failures.size > capacity) failures.remove(failures.keys.first())
        val location = directory?.replace('\n', ' ')?.replace('\r', ' ')?.let { ", directory=$it" }.orEmpty()
        emit(if (repeated) Level.DEBUG else Level.WARN, "$operation $stage failed ($category, context=${context.take(12)}$location)")
    }

    fun debug(message: () -> String) {
        if (debugEnabled()) emit(Level.DEBUG, message())
    }

    @Synchronized
    fun success(context: String, operation: String, vararg stages: String) {
        val removed = failures.keys.removeIf { it.context == context && it.operation == operation && it.stage in stages }
        if (removed) emit(Level.INFO, "$operation ${stages.joinToString("/")} recovered (context=${context.take(12)})")
    }

    @Synchronized
    internal fun size(): Int = failures.size

    companion object {
        /** Avoid retaining command lines, which can contain credentials. */
        fun context(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach {
                val bytes = it.toByteArray(Charsets.UTF_8)
                digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(), bytes.size.toByte()))
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

@Service(Service.Level.APP)
internal class MagoFailureLogService {
    private val logger = Logger.getInstance(MagoFailureLogService::class.java)
    val log = MagoFailureLog(emit = { level, message ->
        when (level) {
            MagoFailureLog.Level.WARN -> logger.warn(message)
            MagoFailureLog.Level.DEBUG -> logger.debug(message)
            MagoFailureLog.Level.INFO -> logger.info(message)
        }
    }, debugEnabled = { logger.isDebugEnabled })

    companion object {
        fun get(): MagoFailureLog = ApplicationManager.getApplication().getService(MagoFailureLogService::class.java).log
    }
}
