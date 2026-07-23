import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';

import {
  encodeFrame,
  extractFrames,
  parseGitHubAuthResult,
  parsePrDetailResult,
  parsePrDiffResult,
  parsePrListResult,
  resolveSidecarJarPath,
} from '../src/sidecar';

const extensionRoot = path.resolve('/workspace/pr-pilot/vscode-extension');

test('encodeFrame writes a bounded Content-Length header followed by the UTF-8 body', () => {
  const frame = encodeFrame('{"message":"héllo"}');
  const text = frame.toString('utf8');
  const body = Buffer.from('{"message":"héllo"}', 'utf8');
  assert.equal(text, `Content-Length: ${body.length}\r\n\r\n{"message":"héllo"}`);
});

test('extractFrames decodes a single complete frame and leaves no remainder', () => {
  const frame = encodeFrame('{"a":1}');
  const seen: string[] = [];
  const remaining = extractFrames(frame, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}']);
  assert.equal(remaining.length, 0);
});

test('extractFrames decodes multiple concatenated frames in order', () => {
  const combined = Buffer.concat([encodeFrame('{"a":1}'), encodeFrame('{"b":2}')]);
  const seen: string[] = [];
  const remaining = extractFrames(combined, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}', '{"b":2}']);
  assert.equal(remaining.length, 0);
});

test('extractFrames holds back a partial trailing frame for the next chunk', () => {
  const complete = encodeFrame('{"a":1}');
  const partialNext = encodeFrame('{"b":2}');
  const combined = Buffer.concat([complete, partialNext.subarray(0, partialNext.length - 3)]);
  const seen: string[] = [];
  const remaining = extractFrames(combined, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}']);
  assert.equal(remaining.length, partialNext.length - 3);
});

test('extractFrames discards an unparseable header instead of looping forever', () => {
  const garbage = Buffer.from('Content-Type: application/json\r\n\r\n{}', 'ascii');
  const seen: string[] = [];
  const remaining = extractFrames(garbage, (body) => seen.push(body));
  assert.deepEqual(seen, []);
  assert.equal(remaining.length, 0);
});

test('parseGitHubAuthResult accepts the token-free authenticated result shape', () => {
  assert.deepEqual(
    parseGitHubAuthResult({
      status: 'authenticated',
      username: 'octocat',
      message: 'GitHub authentication is available.',
    }),
    {
      status: 'authenticated',
      username: 'octocat',
      message: 'GitHub authentication is available.',
    },
  );
});

test('parseGitHubAuthResult rejects malformed or unknown authentication results', () => {
  assert.equal(parseGitHubAuthResult({ status: 'unknown', username: null, message: 'x' }), null);
  assert.equal(parseGitHubAuthResult({ status: 'authenticated', username: 1, message: 'x' }), null);
  assert.equal(parseGitHubAuthResult({ status: 'authenticated', username: null }), null);
});

test('parsePrListResult accepts token-free pull request list results', () => {
  assert.deepEqual(
    parsePrListResult({
      status: 'ok',
      message: 'Pull requests loaded.',
      query: 'is:pr is:open author:@me',
      resultLimit: 50,
      limited: false,
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
    {
      status: 'ok',
      message: 'Pull requests loaded.',
      query: 'is:pr is:open author:@me',
      resultLimit: 50,
      limited: false,
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
    },
  );
});

test('parsePrListResult rejects malformed fields and unknown statuses', () => {
  assert.equal(parsePrListResult({ status: 'unknown', message: 'x', query: null, resultLimit: 50, limited: false, prs: [] }), null);
  assert.equal(parsePrListResult({ status: 'ok', message: 'x', query: null, resultLimit: 50, limited: false, prs: [{ number: '42' }] }), null);
  assert.equal(parsePrListResult({ status: 'ok', message: 'x', query: null, resultLimit: 50, limited: false }), null);
});

test('parsePrDetailResult accepts nullable repository metadata', () => {
  assert.deepEqual(
    parsePrDetailResult({
      status: 'ok',
      message: 'Pull request details loaded.',
      detail: {
        merged: false,
        title: 'Example',
        body: '',
        head: { sha: 'abc', ref: 'feature', repoFullName: null, cloneUrl: null },
        baseRepoFullName: null,
      },
    }),
    {
      status: 'ok',
      message: 'Pull request details loaded.',
      detail: {
        merged: false,
        title: 'Example',
        body: '',
        head: { sha: 'abc', ref: 'feature', repoFullName: null, cloneUrl: null },
        baseRepoFullName: null,
      },
    },
  );
});

test('parsePrDetailResult rejects malformed successful and unknown results', () => {
  assert.equal(parsePrDetailResult({ status: 'unknown', message: 'x', detail: null }), null);
  assert.equal(parsePrDetailResult({ status: 'ok', message: 'x', detail: null }), null);
  assert.equal(parsePrDetailResult({ status: 'ok', message: 'x', detail: { merged: false } }), null);
});

test('parsePrDiffResult accepts only complete successful review diffs', () => {
  assert.deepEqual(parsePrDiffResult({ status: 'ok', message: 'Pull request diff loaded.', diff: 'diff', truncated: false, limitBytes: 250000 }),
    { status: 'ok', message: 'Pull request diff loaded.', diff: 'diff', truncated: false, limitBytes: 250000 });
  assert.equal(parsePrDiffResult({ status: 'ok', message: 'x', diff: null, truncated: false, limitBytes: 250000 }), null);
});

test('resolveSidecarJarPath prefers the packaged jar staged alongside the extension', () => {
  const resolved = resolveSidecarJarPath(
    extensionRoot,
    (candidate) => candidate === path.join(extensionRoot, 'sidecar', 'pr-pilot-sidecar.jar'),
    () => [],
  );
  assert.equal(resolved, path.join(extensionRoot, 'sidecar', 'pr-pilot-sidecar.jar'));
});

test('resolveSidecarJarPath falls back to the sibling Gradle module build output during development', () => {
  const devLibsDir = path.resolve(extensionRoot, '..', 'sidecar', 'build', 'libs');
  const resolved = resolveSidecarJarPath(
    extensionRoot,
    (candidate) => candidate === devLibsDir,
    (dir) => (dir === devLibsDir ? ['pr-pilot-sidecar.jar'] : []),
  );
  assert.equal(resolved, path.join(devLibsDir, 'pr-pilot-sidecar.jar'));
});

test('resolveSidecarJarPath returns null when neither packaged nor dev jar exists', () => {
  const resolved = resolveSidecarJarPath(extensionRoot, () => false, () => []);
  assert.equal(resolved, null);
});
