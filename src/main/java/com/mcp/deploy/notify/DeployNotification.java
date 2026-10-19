package com.mcp.deploy.notify;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * 通知工具类 — 使用 IntelliJ 内置的 Notification 系统弹通知。
 *
 * <p>注意：IDEA 通知正文按 HTML 渲染，{@code \n} 不会换行，必须转成 {@code <br/>}。</p>
 */
public final class DeployNotification {

    private static final String GROUP_ID = "Maven Deploy";

    private DeployNotification() {
    }

    /**
     * 开始上传通知（上传前提示，避免用户不知道有没有在传）。
     *
     * @return 通知对象，上传结束后可调用 {@link Notification#expire()} 收起
     */
    public static Notification showUploadStarted(Project project, String jarName, long fileSize,
                                                 List<String> serverLabels) {
        String content = String.format(
            "开始上传 %s (%s)\n目标服务器: %s\n上传中，完成后会再通知…",
            jarName, formatSize(fileSize), String.join("、", serverLabels)
        );
        return notify(project, "Maven 部署开始", content, NotificationType.INFORMATION);
    }

    /**
     * 批量上传结果汇总（多台服务器时使用）。
     */
    public static void showBatchResult(Project project, int success, int failed, int total) {
        NotificationType type = failed == 0 ? NotificationType.INFORMATION : NotificationType.WARNING;
        String content = "共 " + total + " 台服务器，成功 " + success + " 台，失败 " + failed + " 台。"
            + (failed == 0 ? "" : "\n失败原因见上方错误通知，或 idea.log 里的 [SshUploader] 日志。");
        notify(project, failed == 0 ? "Maven 部署完成" : "Maven 部署部分失败", content, type);
    }

    /**
     * 上传成功通知。
     */
    public static void showSuccess(Project project, String serverLabel, String jarName,
                                   String remotePath, long fileSize) {
        String content = String.format(
            "%s (%s)\n服务器: %s\n已上传至: %s",
            jarName, formatSize(fileSize), serverLabel, remotePath
        );
        notify(project, "Maven 部署成功", content, NotificationType.INFORMATION);
    }

    /**
     * 上传失败通知。
     */
    public static void showFailure(Project project, String serverLabel, String errorMsg) {
        String content = "服务器: " + serverLabel + "\n" + errorMsg;
        notify(project, "Maven 部署失败", content, NotificationType.ERROR);
    }

    /**
     * 警告通知：例如打包成功但没找到 jar，让用户知道「为什么没上传」。
     */
    public static void showWarning(Project project, String title, String content) {
        notify(project, title, content, NotificationType.WARNING);
    }

    private static Notification notify(Project project, String title, String content, NotificationType type) {
        try {
            Notification notification = group().createNotification(title, toHtml(content), type);
            Notifications.Bus.notify(notification, project);
            return notification;
        } catch (Exception ignored) {
            // 通知失败不能影响上传流程本身（例如插件在沙盒里未注册通知组）
            return null;
        }
    }

    private static NotificationGroup group() {
        return NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID);
    }

    /** 纯文本 → 通知可识别的 HTML（转义 + 换行），外面显式包一层 &lt;html&gt; 保证按 HTML 渲染。 */
    private static String toHtml(String text) {
        if (text == null) return "";
        String escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
        return "<html>" + escaped.replace("\r\n", "\n").replace("\n", "<br/>") + "</html>";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
