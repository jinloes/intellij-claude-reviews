import test from 'node:test';
import assert from 'node:assert/strict';

import { BRIDGE_PROTOCOL_VERSION, isValidBridgeRequest } from '../src/bridgeValidation';

const version = { protocolVersion: BRIDGE_PROTOCOL_VERSION };

test('accepts valid PR-scoped request', () => {
  assert.equal(
    isValidBridgeRequest({ ...version, type: 'generateReview', number: 42, owner: 'acme', repo: 'platform' }),
    true,
  );
});

test('rejects unknown type', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'surprise' }), false);
});

test('rejects PR-scoped request without valid identity', () => {
  assert.equal(
    isValidBridgeRequest({ ...version, type: 'selectPR', number: 0, owner: '', repo: '' }),
    false,
  );
});

test('accepts openUrl only when url is a string', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'openUrl', url: 'https://example.com' }), true);
  assert.equal(isValidBridgeRequest({ ...version, type: 'openUrl', url: 42 }), false);
});

test('accepts setup runAuthLogin action', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'runAuthLogin' }), true);
});

test('validates webview layout change reasons', () => {
  assert.equal(isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged', reason: 'chat-panel' }), true);
  assert.equal(isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged' }), false);
  assert.equal(isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged', reason: 42 }), false);
  assert.equal(
    isValidBridgeRequest({ ...version, type: 'webviewLayoutChanged', reason: 'x'.repeat(4_097) }),
    false,
  );
});

test('rejects unversioned messages', () => {
  assert.equal(isValidBridgeRequest({ type: 'runAuthLogin' }), false);
});

test('validates nested review fields', () => {
  const base = { ...version, type: 'saveDraft', number: 42, owner: 'acme', repo: 'platform' };
  assert.equal(isValidBridgeRequest({
    ...base,
    result: {
      summary: 'Summary',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/a.ts', line: 1, type: 'note', body: 'Body', confidence: 'high' }],
    },
  }), true);
  assert.equal(isValidBridgeRequest({
    ...base,
    result: { summary: 'Summary', verdict: 'INVALID', lineComments: [] },
  }), false);
});

test('validates refresh compatibility booleans', () => {
  assert.equal(isValidBridgeRequest({
    ...version, type: 'refreshPRs', assignedToMe: true, reviewRequested: false,
  }), true);
  assert.equal(isValidBridgeRequest({
    ...version, type: 'refreshPRs', assignedToMe: 'yes',
  }), false);
});

test('rejects oversized identities and payloads', () => {
  assert.equal(isValidBridgeRequest({
    ...version, type: 'selectPR', number: 42, owner: 'x'.repeat(257), repo: 'platform',
  }), false);
  assert.equal(isValidBridgeRequest({
    ...version, type: 'askClaude', question: 'x'.repeat(100_001), context: '',
  }), false);
});

test('rejects invalid rich comment metadata', () => {
  const base = { ...version, type: 'saveDraft', number: 42, owner: 'acme', repo: 'platform' };
  assert.equal(isValidBridgeRequest({
    ...base,
    result: {
      summary: 'Summary',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/a.ts', line: 1, type: 'note', body: 'Body', severity: 'urgent' }],
    },
  }), false);
});
