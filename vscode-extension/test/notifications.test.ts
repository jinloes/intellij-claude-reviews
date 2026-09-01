import test from 'node:test';
import assert from 'node:assert/strict';

import {
  EMPTY_NOTIFICATION_HEALTH,
  markNotificationWarningShown,
  mergeBySource,
  normalizeNotificationSeedSources,
  notificationMessage,
  notificationWarningMessage,
  notifySourceLabel,
  planNotificationPoll,
  prNotificationKey,
  recordNotificationDegraded,
  recordNotificationFailure,
  recordNotificationSuccess,
  settleNotificationSources,
  shouldWarnAboutNotificationFailure,
} from '../src/notifications';
import type { PR } from '../src/models';

function pr(owner: string, repo: string, number: number): PR {
  return {
    owner,
    repo,
    number,
    title: `Title #${number}`,
    author: 'octocat',
    createdAt: '',
    htmlUrl: 'https://github.test',
    isDraft: false,
    hasReviewDraft: false,
    reviewStatus: 'UNAVAILABLE',
  };
}

test('notifySourceLabel distinguishes the two sources', () => {
  assert.equal(notifySourceLabel('reviewRequested'), 'Review requested');
  assert.match(notifySourceLabel('starredRepo'), /Starred repo/);
});

test('notificationMessage names the source and the PR', () => {
  const msg = notificationMessage(pr('acme', 'foo', 7), 'reviewRequested');
  assert.match(msg, /Review requested/);
  assert.match(msg, /acme\/foo #7/);
  assert.match(msg, /Title #7/);
});

test('starred-repo message is labeled distinctly', () => {
  const msg = notificationMessage(pr('acme', 'bar', 9), 'starredRepo');
  assert.match(msg, /Starred repo/);
  assert.doesNotMatch(msg, /Review requested/);
});

test('mergeBySource gives review-requested precedence and preserves order', () => {
  const shared = pr('acme', 'foo', 1);
  const starredOnly = pr('acme', 'bar', 2);
  const merged = mergeBySource([shared], [shared, starredOnly]);

  assert.equal(merged.length, 2);
  assert.equal(merged[0].source, 'reviewRequested');
  assert.equal(merged[0].pr.number, 1);
  assert.equal(merged[1].source, 'starredRepo');
  assert.equal(merged[1].pr.number, 2);
});

test('notification health records failures and recovers on success', () => {
  const firstFailure = recordNotificationFailure(EMPTY_NOTIFICATION_HEALTH, 'network down', 1_000);
  const secondFailure = recordNotificationFailure(firstFailure, 'still down', 2_000);

  assert.equal(firstFailure.consecutiveFailures, 1);
  assert.equal(secondFailure.consecutiveFailures, 2);
  assert.equal(secondFailure.message, 'still down');
  assert.equal(shouldWarnAboutNotificationFailure(secondFailure, 2_000), true);

  const recovered = recordNotificationSuccess(secondFailure, 3_000);
  assert.equal(recovered.status, 'healthy');
  assert.equal(recovered.consecutiveFailures, 0);
  assert.equal(recovered.message, undefined);
  assert.equal(recovered.lastSuccessAt, 3_000);
});

test('notification health records partial success as degraded', () => {
  const first = recordNotificationDegraded(EMPTY_NOTIFICATION_HEALTH, 'starredRepo: unavailable', 1_000);
  const second = recordNotificationDegraded(first, 'starredRepo: still unavailable', 2_000);

  assert.equal(first.status, 'degraded');
  assert.equal(first.lastSuccessAt, 1_000);
  assert.equal(first.message, 'starredRepo: unavailable');
  assert.equal(shouldWarnAboutNotificationFailure(first, 1_000), false);
  assert.equal(shouldWarnAboutNotificationFailure(second, 2_000), true);
  assert.match(notificationWarningMessage(second), /partially working/);
  assert.doesNotMatch(notificationWarningMessage(second), /not working/);
});

test('settles review-requested success when starred repositories fail', async () => {
  const review = pr('acme', 'review', 1);
  const result = await settleNotificationSources([
    { source: 'reviewRequested', load: async () => [review] },
    { source: 'starredRepo', load: async () => { throw new Error('starred unavailable'); } },
  ]);

  assert.deepEqual(result.reviewRequested, [review]);
  assert.deepEqual(result.starred, []);
  assert.deepEqual(result.successfulSources, ['reviewRequested']);
  assert.equal(result.failures[0].source, 'starredRepo');
});

test('settles starred success when review-requested fails', async () => {
  const starred = pr('acme', 'starred', 2);
  const result = await settleNotificationSources([
    { source: 'reviewRequested', load: async () => { throw new Error('review unavailable'); } },
    { source: 'starredRepo', load: async () => [starred] },
  ]);

  assert.deepEqual(result.reviewRequested, []);
  assert.deepEqual(result.starred, [starred]);
  assert.deepEqual(result.successfulSources, ['starredRepo']);
  assert.equal(result.failures[0].source, 'reviewRequested');
});

test('persistent partial failure seeds the healthy source and keeps delivering its new PRs', () => {
  const existing = pr('acme', 'existing-review', 1);
  const next = pr('acme', 'new-review', 2);
  const failedSourceUnknown = pr('acme', 'unknown-starred', 3);
  const first = planNotificationPoll(new Set(), new Set(), {
    reviewRequested: [existing],
    starred: [],
    successfulSources: ['reviewRequested'],
    failures: [{ source: 'starredRepo', message: 'unavailable' }],
  });

  assert.equal(first.status, 'degraded');
  assert.deepEqual(first.seededSources, ['reviewRequested']);
  assert.deepEqual(first.notifications, []);
  assert.deepEqual(first.seen, [prNotificationKey(existing)]);

  const second = planNotificationPoll(
    new Set(first.seededSources),
    new Set(first.seen),
    {
      reviewRequested: [existing, next],
      starred: [],
      successfulSources: ['reviewRequested'],
      failures: [{ source: 'starredRepo', message: 'still unavailable' }],
    },
  );

  assert.equal(second.status, 'degraded');
  assert.deepEqual(second.notifications.map(({ pr: candidate }) => prNotificationKey(candidate)), [
    prNotificationKey(next),
  ]);
  assert.deepEqual(second.seen, [prNotificationKey(existing), prNotificationKey(next)]);
  assert.equal(second.seen.includes(prNotificationKey(failedSourceUnknown)), false);

  const repeated = planNotificationPoll(
    new Set(second.seededSources),
    new Set(second.seen),
    {
      reviewRequested: [existing, next],
      starred: [],
      successfulSources: ['reviewRequested'],
      failures: [{ source: 'starredRepo', message: 'still unavailable' }],
    },
  );
  assert.deepEqual(repeated.notifications, []);

  const recovered = planNotificationPoll(
    new Set(repeated.seededSources),
    new Set(repeated.seen),
    {
      reviewRequested: [existing, next],
      starred: [failedSourceUnknown],
      successfulSources: ['reviewRequested', 'starredRepo'],
      failures: [],
    },
  );
  assert.equal(recovered.status, 'healthy');
  assert.deepEqual(recovered.notifications, []);
  assert.deepEqual(recovered.seededSources, ['reviewRequested', 'starredRepo']);
  assert.equal(recovered.seen.includes(prNotificationKey(failedSourceUnknown)), true);
});

test('total source failure leaves an unseeded poller state unchanged', () => {
  const plan = planNotificationPoll(new Set(), new Set(), {
    reviewRequested: [],
    starred: [],
    successfulSources: [],
    failures: [
      { source: 'reviewRequested', message: 'review unavailable' },
      { source: 'starredRepo', message: 'starred unavailable' },
    ],
  });

  assert.equal(plan.status, 'failed');
  assert.deepEqual(plan.seededSources, []);
  assert.deepEqual(plan.seen, []);
  assert.deepEqual(plan.notifications, []);
});

test('per-source seed restoration validates values and supports the legacy global seed', () => {
  assert.deepEqual(normalizeNotificationSeedSources(undefined, true), [
    'reviewRequested',
    'starredRepo',
  ]);
  assert.deepEqual(
    normalizeNotificationSeedSources(['starredRepo', 'invalid', 'starredRepo'], true),
    ['starredRepo'],
  );
  assert.deepEqual(normalizeNotificationSeedSources([], true), []);
});

test('notification warning is suppressed until its cooldown expires', () => {
  const failedTwice = recordNotificationFailure(
    recordNotificationFailure(EMPTY_NOTIFICATION_HEALTH, 'down', 1_000),
    'down',
    2_000,
  );
  const warned = markNotificationWarningShown(failedTwice, 2_000);

  assert.match(notificationWarningMessage(failedTwice), /not working/);
  assert.equal(shouldWarnAboutNotificationFailure(warned, 2_000 + 29 * 60_000), false);
  assert.equal(shouldWarnAboutNotificationFailure(warned, 2_000 + 30 * 60_000), true);
});
