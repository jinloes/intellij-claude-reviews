package com.jinloes.prpilot.services;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.text.StringEscapeUtils;

/** Deduplicates the actionable IntelliJ warning for an unreadable local draft index. */
public final class PendingReviewIndexNotifications {
    private static final String NOTIFICATION_GROUP = "PR Pilot";
    private static final String USER_MESSAGE =
            "PR Pilot could not read ~/.pr-pilot/pending-prs.json. The file was preserved; "
                    + "repair it or use the notification action to quarantine it, then refresh PR Pilot.";
    private static final Map<Path, Notification> ACTIVE_NOTIFICATIONS = new ConcurrentHashMap<>();
    private static final Map<Path, Set<Runnable>> RECOVERY_ACTIONS = new ConcurrentHashMap<>();

    private PendingReviewIndexNotifications() {}

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public static String userMessage() {
        return USER_MESSAGE;
    }

    public static Registration observe(
            PendingReviewIndex index, PendingReviewIndex.LoadResult result) {
        return observe(index, result, null);
    }

    public static Registration observe(
            PendingReviewIndex index,
            PendingReviewIndex.LoadResult result,
            Runnable recoveryAction) {
        Path indexPath = index.indexPath();
        if (result.healthy()) {
            clear(indexPath);
            return () -> {};
        }
        Registration registration = () -> {};
        if (recoveryAction != null) {
            RECOVERY_ACTIONS
                    .computeIfAbsent(indexPath, ignored -> ConcurrentHashMap.newKeySet())
                    .add(recoveryAction);
            registration = () -> removeRecoveryAction(indexPath, recoveryAction);
        }
        Application application = ApplicationManager.getApplication();
        if (application == null || ACTIVE_NOTIFICATIONS.containsKey(indexPath)) {
            return registration;
        }
        application.invokeLater(() -> showWarning(index, indexPath));
        return registration;
    }

    private static void showWarning(PendingReviewIndex index, Path indexPath) {
        if (ACTIVE_NOTIFICATIONS.containsKey(indexPath)) {
            return;
        }
        Notification notification =
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP)
                        .createNotification(
                                "Local draft index needs repair",
                                USER_MESSAGE,
                                NotificationType.ERROR);
        notification.addAction(
                NotificationAction.createSimple(
                        "Quarantine corrupt file",
                        () -> quarantine(index, indexPath, notification)));
        Notification existing = ACTIVE_NOTIFICATIONS.putIfAbsent(indexPath, notification);
        if (existing == null) {
            notification.notify(null);
        }
    }

    private static void quarantine(
            PendingReviewIndex index, Path indexPath, Notification notification) {
        PendingReviewIndex.QuarantineResult result = index.quarantineCorruptFile();
        if (result.status() == PendingReviewIndex.QuarantineStatus.FAILED) {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                            "Could not quarantine local draft index",
                            result.error(),
                            NotificationType.ERROR)
                    .notify(null);
            return;
        }

        ACTIVE_NOTIFICATIONS.remove(indexPath, notification);
        notification.expire();
        Set<Runnable> recoveryActions = RECOVERY_ACTIONS.remove(indexPath);
        if (recoveryActions != null) {
            recoveryActions.forEach(Runnable::run);
        }
        if (result.status() == PendingReviewIndex.QuarantineStatus.QUARANTINED) {
            String quarantinedPath =
                    StringEscapeUtils.escapeHtml4(result.quarantinedPath().toString());
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                            "Local draft index quarantined",
                            "The corrupt file was preserved at "
                                    + quarantinedPath
                                    + ". GitHub drafts were not changed.",
                            NotificationType.INFORMATION)
                    .notify(null);
        }
    }

    private static void clear(Path indexPath) {
        RECOVERY_ACTIONS.remove(indexPath);
        Notification notification = ACTIVE_NOTIFICATIONS.remove(indexPath);
        if (notification == null) {
            return;
        }
        Application application = ApplicationManager.getApplication();
        if (application != null) {
            application.invokeLater(notification::expire);
        }
    }

    private static void removeRecoveryAction(Path indexPath, Runnable recoveryAction) {
        RECOVERY_ACTIONS.computeIfPresent(
                indexPath,
                (ignored, recoveryActions) -> {
                    recoveryActions.remove(recoveryAction);
                    return recoveryActions.isEmpty() ? null : recoveryActions;
                });
    }

    static int recoveryActionCount(Path indexPath) {
        Set<Runnable> recoveryActions =
                RECOVERY_ACTIONS.get(indexPath.toAbsolutePath().normalize());
        return recoveryActions == null ? 0 : recoveryActions.size();
    }
}
