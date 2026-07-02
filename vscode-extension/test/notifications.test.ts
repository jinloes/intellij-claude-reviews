import test from 'node:test';
import assert from 'node:assert/strict';

import { mergeBySource, notificationMessage, notifySourceLabel } from '../src/notifications';
import type { PR } from '../src/github';

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


