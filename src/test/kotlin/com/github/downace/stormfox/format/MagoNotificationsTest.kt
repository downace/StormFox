package com.github.downace.stormfox.format

import com.github.downace.stormfox.settings.MagoConfigurable
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService

class MagoNotificationsTest : BasePlatformTestCase() {
    fun testOpenSettingsActionSelectsMagoConfigurableClass() {
        var selectedProject: Project? = null
        var selectedClass: Class<*>? = null
        val settings = object : ShowSettingsUtilImpl() {
            override fun <T : Configurable?> showSettingsDialog(project: Project?, configurableClass: Class<T>) {
                selectedProject = project
                selectedClass = configurableClass
            }
        }
        ApplicationManager.getApplication().replaceService(ShowSettingsUtil::class.java, settings, testRootDisposable)

        val notification = createMagoErrorNotification(project, "Formatting failed")
        val action = notification.actions.single() as NotificationAction
        val event = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT)
        action.actionPerformed(event, notification)

        assertSame(project, selectedProject)
        assertEquals(MagoConfigurable::class.java, selectedClass)
    }
}
