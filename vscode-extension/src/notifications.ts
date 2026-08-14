import type { PR } from './models';

/** Provenance of a background PR notification, surfaced so the source is unambiguous. */
export type NotifySource = 'reviewRequested' | 'starredRepo';

export interface NotificationHealth {
    status: 'never' | 'healthy' | 'error';
    lastAttemptAt?: number;
    lastSuccessAt?: number;
    message?: string;
    consecutiveFailures: number;
    lastWarningAt?: number;
}

export const EMPTY_NOTIFICATION_HEALTH: NotificationHealth = {
    status: 'never',
    consecutiveFailures: 0,
};

export function recordNotificationSuccess(
    previous: NotificationHealth,
    now = Date.now(),
): NotificationHealth {
    return {
        ...previous,
        status: 'healthy',
        lastAttemptAt: now,
        lastSuccessAt: now,
        message: undefined,
        consecutiveFailures: 0,
    };
}

export function recordNotificationFailure(
    previous: NotificationHealth,
    message: string,
    now = Date.now(),
): NotificationHealth {
    return {
        ...previous,
        status: 'error',
        lastAttemptAt: now,
        message,
        consecutiveFailures: previous.consecutiveFailures + 1,
    };
}

export function shouldWarnAboutNotificationFailure(
    health: NotificationHealth,
    now = Date.now(),
    cooldownMs = 30 * 60_000,
): boolean {
    return health.status === 'error'
        && health.consecutiveFailures >= 2
        && (health.lastWarningAt === undefined || now - health.lastWarningAt >= cooldownMs);
}

export function markNotificationWarningShown(
    health: NotificationHealth,
    now = Date.now(),
): NotificationHealth {
    return { ...health, lastWarningAt: now };
}

export function notificationHealthLabel(health: NotificationHealth): string {
    if (health.status === 'never') return 'No notification poll has run yet.';
    const when = health.lastAttemptAt === undefined ? '' : ` Last checked ${new Date(health.lastAttemptAt).toLocaleString()}.`;
    if (health.status === 'healthy') return `Notifications are working.${when}`;
    return `Notification polling failed: ${health.message || 'Unknown error.'}${when}`;
}

export function notifySourceLabel(source: NotifySource): string {
    return source === 'starredRepo' ? '★ Starred repo' : 'Review requested';
}

export function prNotificationKey(pr: PR): string {
    return `${pr.owner}/${pr.repo}#${pr.number}`;
}

export function notificationMessage(pr: PR, source: NotifySource): string {
    return `${notifySourceLabel(source)} · ${pr.owner}/${pr.repo} #${pr.number}: ${pr.title}`;
}

/**
 * Merges review-requested and starred-repo results, deduped by PR. Review-requested wins when a PR
 * appears in both sources so its more actionable label is shown. Order is preserved
 * (review-requested first, then starred-only PRs).
 */
export function mergeBySource(
    reviewRequested: PR[],
    starred: PR[],
): Array<{ pr: PR; source: NotifySource }> {
    const merged = new Map<string, { pr: PR; source: NotifySource }>();
    for (const pr of reviewRequested) {
        const key = prNotificationKey(pr);
        if (!merged.has(key)) merged.set(key, { pr, source: 'reviewRequested' });
    }
    for (const pr of starred) {
        const key = prNotificationKey(pr);
        if (!merged.has(key)) merged.set(key, { pr, source: 'starredRepo' });
    }
    return [...merged.values()];
}
