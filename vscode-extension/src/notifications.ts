import type { PR } from './github';

/** Provenance of a background PR notification, surfaced so the source is unambiguous. */
export type NotifySource = 'reviewRequested' | 'starredRepo';

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

