package com.github.downace.stormfox.format

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

internal const val MAGO_NOTIFICATION_GROUP = "Mago"

/**
 * The name of the plugin itself. The tool it drives is called [TOOL_NAME], and that is what the
 * user sees wherever the tool is involved.
 */
internal const val PLUGIN_NAME = "StormFox"

internal const val TOOL_NAME = "Mago"

internal const val SETTINGS_CONFIGURABLE_ID = "settings.php.quality.tools.php.mago.formatter"

internal const val SETTINGS_LOCATION = "Settings | PHP | Quality Tools | Mago"

internal const val PHP_LANGUAGE_ID = "PHP"

private const val MAX_NOTIFICATION_LENGTH = 500

internal fun notifyMagoError(project: Project?, message: String) {
    val notification = NotificationGroupManager.getInstance()
        .getNotificationGroup(MAGO_NOTIFICATION_GROUP)
        .createNotification(TOOL_NAME, truncateForNotification(message), NotificationType.ERROR)

    if (project != null && !project.isDisposed) {
        notification.addAction(
            NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_CONFIGURABLE_ID)
            }
        )
    }

    notification.notify(project)
}

/**
 * The full mago output goes to the IDE log, a notification only gets the beginning of it.
 */
internal fun truncateForNotification(message: String): String {
    if (message.length <= MAX_NOTIFICATION_LENGTH) {
        return message
    }
    return message.take(MAX_NOTIFICATION_LENGTH).trimEnd() + "… (see the IDE log for the full output)"
}
