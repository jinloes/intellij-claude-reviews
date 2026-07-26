import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

import {
  DEFAULT_GUIDANCE_GLOBS,
  globToRegex,
  isGlob,
  readRepoGuidelines,
  resolvePaths,
  MAX_GUIDELINES_BYTES,
} from '../src/guidelines';

function mkTempDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'repo-guidelines-test-'));
}

function write(dir: string, rel: string, content: string): void {
  const full = path.join(dir, rel);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, content);
}

test('globToRegex: single star stays within a path segment', () => {
  assert.match('a.md', globToRegex('*.md'));
  assert.doesNotMatch('dir/a.md', globToRegex('*.md'));
});

test('globToRegex: **/ matches zero or more leading segments', () => {
  const re = globToRegex('**/style.md');
  assert.match('style.md', re);
  assert.match('a/style.md', re);
  assert.match('a/b/style.md', re);
  assert.doesNotMatch('a/style.md.bak', re);
});

test('globToRegex: ? matches a single non-slash char and dots are literal', () => {
  assert.match('v1.md', globToRegex('v?.md'));
  assert.doesNotMatch('v12.md', globToRegex('v?.md'));
  assert.doesNotMatch('axmd', globToRegex('a.md'));
});

test('isGlob detects glob metacharacters', () => {
  assert.equal(isGlob('AGENTS.md'), false);
  assert.equal(isGlob('**/style.md'), true);
  assert.equal(isGlob('.linkedin/ai-agent/*.md'), true);
});

test('resolvePaths: literal paths resolve in priority order, missing skipped', () => {
  const dir = mkTempDir();
  try {
    write(dir, 'AGENTS.md', 'a');
    write(dir, '.linkedin/ai-agent/coding-pattern.md', 'b');
    const resolved = resolvePaths(dir, ['.linkedin/ai-agent/coding-pattern.md', 'AGENTS.md', 'MISSING.md']);
    assert.deepEqual(resolved, ['.linkedin/ai-agent/coding-pattern.md', 'AGENTS.md']);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('resolvePaths: globs match nested files, dedupe, and skip heavy dirs', () => {
  const dir = mkTempDir();
  try {
    write(dir, 'docs/style.md', 's1');
    write(dir, 'src/style.md', 's2');
    write(dir, 'style.md', 's0');
    write(dir, 'node_modules/pkg/style.md', 'ignored');
    const resolved = resolvePaths(dir, ['**/style.md', 'style.md']);
    assert.deepEqual(resolved, ['docs/style.md', 'src/style.md', 'style.md']);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('readRepoGuidelines: concatenates matched files with headers', () => {
  const dir = mkTempDir();
  try {
    write(dir, 'AGENTS.md', 'agent rules');
    write(dir, '.linkedin/ai-agent/coding-pattern.md', 'pattern rules');
    const result = readRepoGuidelines(dir, ['AGENTS.md', '.linkedin/ai-agent/*.md']);
    assert.match(result, /## AGENTS\.md\nagent rules/);
    assert.match(result, /## \.linkedin\/ai-agent\/coding-pattern\.md\npattern rules/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('readRepoGuidelines: falls back to defaults and caps bytes', () => {
  const dir = mkTempDir();
  try {
    write(dir, 'AGENTS.md', 'x'.repeat(MAX_GUIDELINES_BYTES + 500));
    const result = readRepoGuidelines(dir, []);
    assert.match(result, /## AGENTS\.md/);
    assert.match(result, /…\(truncated\)/);
    assert.ok(DEFAULT_GUIDANCE_GLOBS.includes('AGENTS.md'));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('readRepoGuidelines: returns empty for blank dir', () => {
  assert.equal(readRepoGuidelines('', ['AGENTS.md']), '');
});

