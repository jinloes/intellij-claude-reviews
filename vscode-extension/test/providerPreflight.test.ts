import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

import { existsOnPath } from '../src/claude';
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



