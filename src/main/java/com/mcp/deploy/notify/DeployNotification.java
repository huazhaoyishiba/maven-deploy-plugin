package com.mcp.deploy.notify;

import com.intellij.notification.*;
import com.intellij.openapi.project.Project;

/**
 * 通知工具类 — 使用 IntelliJ 内置的 Notification 系统弹通知。
 */
public final class DeployNotification {

    private static final NotificationGroup GROUP =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Maven Deploy");

    private DeployNotification() {}

    /**
     * 上传成功通知。
     */
    public static void showSuccess(Project project, String jarName, String remotePath, long fileSize) {
        String sizeStr = formatSize(fileSize);
        String content = String.format(
            "%s (%s)\n已上传至: %s",
            jarName, sizeStr, remotePath
        );

        Notification notification = GROUP.createNotification(
            "Maven 部署成功",
            content,
            NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }

    /**
     * 上传失败通知。
     */
    public static void showFailure(Project project, String errorMsg) {
        Notification notification = GROUP.createNotification(
            "Maven 部署失败",
            errorMsg,
            NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, project);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
