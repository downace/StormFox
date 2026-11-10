package com.github.downace.stormfox.settings

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@Service(Service.Level.PROJECT)
internal class MagoSettingsValidationService(private val scope: CoroutineScope) {
    fun createSession(): Session = Session(scope)

    class Session(parent: CoroutineScope) {
        private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

        fun <T> validate(check: () -> T, update: (T) -> Unit): Job =
            scope.launch(Dispatchers.EDT + ModalityState.current().asContextElement()) {
                val result = coroutineScope {
                    suspendCancellableCoroutine<T> { continuation ->
                        val indicator = EmptyProgressIndicator()
                        continuation.invokeOnCancellation { indicator.cancel() }
                        launch(Dispatchers.IO) {
                            continuation.resumeWith(runCatching {
                                ProgressManager.getInstance().runProcess(Computable { check() }, indicator)
                            })
                        }
                    }
                }
                update(result)
            }

        fun dispose() {
            scope.cancel()
        }
    }
}
