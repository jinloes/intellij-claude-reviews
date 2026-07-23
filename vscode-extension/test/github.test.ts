import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

import {
  apiBase,
  buildPRSearchQuery,
  detectCurrentRepo,
  detectCurrentRepoAsync,
  getPRDetailWithSidecar,
  isRetriableNetworkError,
  isRetriableStatus,
  loadDraftReviewWithSidecar,
  MAX_VALIDATION_DIFF_BYTES,
  normalizeGithubBaseUrl,
  searchPRs,
} from '../src/github';

test('buildPRSearchQuery uses current repo when scope is currentRepo', () => {
  assert.equal(
    buildPRSearchQuery('open', 'currentRepo', 'acme/platform'),
    'is:pr is:open repo:acme/platform',
  );
});

test('buildPRSearchQuery falls back to authored PRs when current repo is missing', () => {
  assert.equal(
    buildPRSearchQuery('open', 'currentRepo'),
    'is:pr is:open author:@me',
  );
});

test('buildPRSearchQuery supports review-requested scope', () => {
  assert.equal(
    buildPRSearchQuery('open', 'reviewRequested', 'acme/platform'),
    'is:pr is:open review-requested:@me',
  );
});

test('buildPRSearchQuery supports assigned scope', () => {
  assert.equal(
    buildPRSearchQuery('closed', 'assigned'),
    'is:pr is:closed assignee:@me',
  );
});

test('buildPRSearchQuery supports authored scope', () => {
  assert.equal(
    buildPRSearchQuery('all', 'authored'),
    'is:pr author:@me',
  );
});

test('searchPRs uses the sidecar result without resolving a local token', async () => {
  const result = await searchPRs(
    'https://github.com',
    'open',
    'currentRepo',
    'acme/widgets',
    {
      buildSearchQuery: async () => null,
      listPullRequests: async () => ({
        status: 'ok',
        message: 'Pull requests loaded.',
        query: 'is:pr is:open repo:acme/widgets',
        resultLimit: 50,
        limited: true,
        prs: [{
          number: 42,
          title: 'Example',
          owner: 'acme',
          repo: 'widgets',
          author: 'octocat',
          createdAt: '2026-01-01T00:00:00Z',
          htmlUrl: 'https://github.com/acme/widgets/pull/42',
          isDraft: false,
        }],
      }),
    },
    async () => {
      throw new Error('local token must not be resolved');
    },
  );

  assert.equal(result.limited, true);
  assert.deepEqual(result.prs, [{
    number: 42,
    title: 'Example',
    owner: 'acme',
    repo: 'widgets',
    author: 'octocat',
    createdAt: '2026-01-01T00:00:00Z',
    htmlUrl: 'https://github.com/acme/widgets/pull/42',
    isDraft: false,
    hasReviewDraft: false,
  }]);
});

test('searchPRs surfaces valid sidecar domain failures without a local fallback', async () => {
  await assert.rejects(
    searchPRs(
      'https://github.com',
      'open',
      'authored',
      undefined,
      {
        buildSearchQuery: async () => null,
        listPullRequests: async () => ({
          status: 'not_authenticated',
          message: "Run 'gh auth login' in a terminal for this GitHub host.",
          query: null,
          resultLimit: 50,
          limited: false,
          prs: [],
        }),
      },
      async () => {
        throw new Error('local fallback must not run');
      },
    ),
    /gh auth login/,
  );
});

test('getPRDetailWithSidecar uses a valid sidecar detail without direct GitHub fallback', async () => {
  const detail = await getPRDetailWithSidecar(
    'unused-token',
    'https://github.com',
    'acme',
    'widgets',
    42,
    {
      getPullRequestDetail: async () => ({
        status: 'ok',
        message: 'Pull request details loaded.',
        detail: {
          merged: false,
          title: 'Example',
          body: 'Description',
          head: { sha: 'abc', ref: 'feature', repoFullName: 'acme/widgets', cloneUrl: 'https://github.com/acme/widgets.git' },
          baseRepoFullName: 'acme/widgets',
        },
      }),
    },
  );

  assert.deepEqual(detail, {
    merged: false,
    title: 'Example',
    body: 'Description',
    head: { sha: 'abc', ref: 'feature', repo: { full_name: 'acme/widgets', clone_url: 'https://github.com/acme/widgets.git' } },
    base: { repo: { full_name: 'acme/widgets' } },
  });
});

test('getPRDetailWithSidecar surfaces valid domain failures', async () => {
  await assert.rejects(
    getPRDetailWithSidecar(
      'unused-token',
      'https://github.com',
      'acme',
      'widgets',
      42,
      {
        getPullRequestDetail: async () => ({
          status: 'not_authenticated',
          message: "Run 'gh auth login' in a terminal for this GitHub host.",
          detail: null,
        }),
      },
    ),
    /gh auth login/,
  );
});

test('loadDraftReviewWithSidecar decodes a valid sidecar draft without direct GitHub fallback', async () => {
  const draft = await loadDraftReviewWithSidecar(
    'unused-token',
    'https://github.com',
    'acme',
    'widgets',
    42,
    {
      getDraftReview: async () => ({
        status: 'ok',
        message: 'Pending review draft loaded.',
        id: '7',
        commitId: 'sha',
        review: {
          summary: 'Looks good',
          verdict: 'APPROVE',
          lineComments: [{
            file: 'a.ts',
            line: 10,
            type: 'note',
            body: 'nit',
            severity: null,
            category: null,
            confidence: null,
            rationale: null,
          }],
          importedFromGitHub: false,
        },
      }),
    },
  );

  assert.deepEqual(draft, {
    id: '7',
    commitId: 'sha',
    result: {
      summary: 'Looks good',
      verdict: 'APPROVE',
      lineComments: [{
        file: 'a.ts',
        line: 10,
        type: 'note',
        body: 'nit',
        severity: undefined,
        category: undefined,
        confidence: undefined,
        rationale: undefined,
      }],
    },
    importedFromGitHub: false,
  });
});

test('loadDraftReviewWithSidecar returns null when the sidecar reports no pending review', async () => {
  const draft = await loadDraftReviewWithSidecar(
    'unused-token',
    'https://github.com',
    'acme',
    'widgets',
    42,
    {
      getDraftReview: async () => ({
        status: 'none',
        message: 'No pending review draft.',
        id: null,
        commitId: null,
        review: null,
      }),
    },
  );

  assert.equal(draft, null);
});

test('loadDraftReviewWithSidecar surfaces valid domain failures', async () => {
  await assert.rejects(
    loadDraftReviewWithSidecar(
      'unused-token',
      'https://github.com',
      'acme',
      'widgets',
      42,
      {
        getDraftReview: async () => ({
          status: 'not_authenticated',
          message: "Run 'gh auth login' in a terminal for this GitHub host.",
          id: null,
          commitId: null,
          review: null,
        }),
      },
    ),
    /gh auth login/,
  );
});

test('apiBase rejects values that are not HTTPS origins', () => {
  for (const value of [
    'http://github.example.com',
    'https://user:password@github.example.com',
    'https://github.example.com/api/v3',
    'https://github.example.com?tenant=acme',
    'https://github.example.com#fragment',
    'https://github.example.com:',
  ]) {
    assert.throws(
      () => apiBase(value),
      /must be an HTTPS origin without credentials, a path, query, or fragment/,
      value,
    );
  }
});

test('isRetriableStatus retries on 429 and 5xx only', () => {
  assert.equal(isRetriableStatus(429), true);
  assert.equal(isRetriableStatus(500), true);
  assert.equal(isRetriableStatus(503), true);
  assert.equal(isRetriableStatus(404), false);
  assert.equal(isRetriableStatus(422), false);
});

test('isRetriableNetworkError recognizes transient network failures', () => {
  assert.equal(isRetriableNetworkError(new Error('ETIMEDOUT while connecting')), true);
  assert.equal(isRetriableNetworkError(new Error('socket hang up')), true);
  assert.equal(isRetriableNetworkError(new Error('request timeout')), true);
  assert.equal(isRetriableNetworkError(new Error('bad credentials')), false);
});

test('validation diff stays within the webview bridge message limit', () => {
  assert.equal(MAX_VALIDATION_DIFF_BYTES, 1_000_000);
});

test('normalizeGithubBaseUrl defaults to github.com and trims trailing slash', () => {
  assert.equal(normalizeGithubBaseUrl(''), 'https://github.com');
  assert.equal(normalizeGithubBaseUrl('https://github.example.com/'), 'https://github.example.com');
  assert.equal(normalizeGithubBaseUrl('https://GITHUB.EXAMPLE.COM/'), 'https://github.example.com');
});

// ── detectCurrentRepo ───────────────────────────────────────────────────────

function withGitConfig(config: string, fn: (dir: string) => void): void {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-repo-'));
  try {
    fs.mkdirSync(path.join(dir, '.git'));
    fs.writeFileSync(path.join(dir, '.git', 'config'), config);
    fn(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

async function withGitConfigAsync(config: string, fn: (dir: string) => Promise<void>): Promise<void> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-repo-'));
  try {
    fs.mkdirSync(path.join(dir, '.git'));
    fs.writeFileSync(path.join(dir, '.git', 'config'), config);
    await fn(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('detectCurrentRepo reads the origin remote (https)', () => {
  withGitConfig(
    '[remote "origin"]\n\turl = https://github.com/acme/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), 'acme/platform'),
  );
});

test('detectCurrentRepo reads the origin remote (scp-style ssh)', () => {
  withGitConfig(
    '[remote "origin"]\n\turl = git@github.com:acme/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), 'acme/platform'),
  );
});

test('detectCurrentRepo picks origin, not the first remote in the file', () => {
  // Regression: a non-origin remote listed first must not win, or the list would
  // search the wrong repo and (for a personal fork) show only the user's PRs.
  withGitConfig(
    '[remote "upstream"]\n\turl = https://github.com/upstream-org/platform.git\n' +
      '[remote "origin"]\n\turl = https://github.com/acme/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), 'acme/platform'),
  );
});

test('detectCurrentRepoAsync prefers the sidecar result when it finds a repo', async () => {
  await withGitConfigAsync(
    '[remote "origin"]\n\turl = https://github.com/acme/platform.git\n',
    async (dir) => {
      const result = await detectCurrentRepoAsync(dir, {
        detectRepo: async () => 'from-sidecar/repo',
      });
      assert.equal(result, 'from-sidecar/repo');
    },
  );
});

test('detectCurrentRepoAsync falls back to the local implementation when the sidecar finds nothing', async () => {
  await withGitConfigAsync(
    '[remote "origin"]\n\turl = https://github.com/acme/platform.git\n',
    async (dir) => {
      const result = await detectCurrentRepoAsync(dir, { detectRepo: async () => null });
      assert.equal(result, 'acme/platform');
    },
  );
});

test('detectCurrentRepoAsync works with no sidecar supplied', async () => {
  await withGitConfigAsync(
    '[remote "origin"]\n\turl = https://github.com/acme/platform.git\n',
    async (dir) => {
      const result = await detectCurrentRepoAsync(dir);
      assert.equal(result, 'acme/platform');
    },
  );
});

test('detectCurrentRepo returns null when there is no origin remote', () => {
  withGitConfig(
    '[remote "upstream"]\n\turl = https://github.com/upstream-org/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), null),
  );
});
