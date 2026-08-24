import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

import { existsOnPath } from '../src/claude';
import { classifyClaudeAuthProbe, probeClaudeAuthentication } from '../src/providerSetup';
import { providerNotInstalledMessage } from '../src/userFacingError';

test('providerNotInstalledMessage names the Copilot CLI and binary', () => {
  const msg = providerNotInstalledMessage('copilot');
  assert.match(msg, /copilot/i);
  assert.match(msg, /GitHub Copilot CLI/);
  assert.match(msg, /try again/i);
});

test('providerNotInstalledMessage names the Claude CLI and binary', () => {
  const msg = providerNotInstalledMessage('claude');
  assert.match(msg, /claude/i);
  assert.match(msg, /Claude Code CLI/);
  assert.match(msg, /try again/i);
});

test('existsOnPath finds a binary present in a PATH directory', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'prpilot-path-'));
  const original = process.env.PATH;
  try {
    fs.writeFileSync(path.join(dir, 'somebinary'), '');
    process.env.PATH = dir;
    assert.equal(existsOnPath('somebinary'), true);
    assert.equal(existsOnPath('not-there-xyz'), false);
  } finally {
    process.env.PATH = original;
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('existsOnPath returns false when PATH is empty', () => {
  const original = process.env.PATH;
  try {
    process.env.PATH = '';
    assert.equal(existsOnPath('anything'), false);
  } finally {
    process.env.PATH = original;
  }
});

test('Claude auth probe only marks a conclusive signed-out response unavailable', () => {
  const responseError = Object.assign(new Error('command failed'), { code: 1 });
  assert.equal(classifyClaudeAuthProbe(responseError, '', 'Not logged in. Run claude auth login.'), 'unavailable');
  assert.equal(classifyClaudeAuthProbe(responseError, '', "error: unknown command 'auth'"), 'unverified');
  assert.equal(classifyClaudeAuthProbe(null, '', "error: unknown command 'auth'"), 'unverified');
  assert.equal(classifyClaudeAuthProbe(responseError, '', ''), 'unverified');
  assert.equal(classifyClaudeAuthProbe(null, 'authenticated', ''), 'ready');
});

test('Claude auth probe execution and timeout failures remain unverified', async () => {
  assert.equal(await probeClaudeAuthentication(() => {
    throw new Error('spawn failed');
  }), 'unverified');
  assert.equal(await probeClaudeAuthentication((complete) => {
    const timeout = Object.assign(new Error('timed out'), { killed: true });
    complete(timeout, '', 'Not logged in.');
  }), 'unverified');
  assert.equal(await probeClaudeAuthentication((complete) => {
    const executionError = Object.assign(new Error('spawn failed'), { code: 'ENOENT' });
    complete(executionError, '', 'Not logged in.');
  }), 'unverified');
});
