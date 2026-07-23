import test from 'node:test';
import assert from 'node:assert/strict';

import { parseReview } from '../src/review';

const valid = JSON.stringify({
  summary: '## Overview\nDoes a thing.',
  verdict: 'COMMENT',
  lineComments: [{
    file: 'src/a.ts', line: 5, type: 'note', severity: 'minor', category: 'maintainability',
    confidence: 'low', body: 'Is this intentional?',
  }],
});

test('parseReview parses a well-formed object', () => {
  const r = parseReview(valid);
  assert.equal(r.verdict, 'COMMENT');
  assert.equal(r.lineComments.length, 1);
  assert.equal(r.lineComments[0].file, 'src/a.ts');
});

test('parseReview strips markdown fences', () => {
  const r = parseReview('```json\n' + valid + '\n```');
  assert.equal(r.verdict, 'COMMENT');
});

test('parseReview strips surrounding prose', () => {
  const r = parseReview('Here is the review:\n' + valid + '\nThanks!');
  assert.equal(r.lineComments.length, 1);
});

test('parseReview accepts empty lineComments', () => {
  const r = parseReview(JSON.stringify({ summary: 's', verdict: 'APPROVE', lineComments: [] }));
  assert.equal(r.lineComments.length, 0);
});

test('parseReview rejects non-object JSON', () => {
  assert.throws(() => parseReview('[1,2,3]'), /not an object/);
});

test('parseReview rejects missing summary', () => {
  assert.throws(
    () => parseReview(JSON.stringify({ verdict: 'APPROVE', lineComments: [] })),
    /summary/,
  );
});

test('parseReview normalizes an invalid verdict instead of rejecting', () => {
  const r = parseReview(JSON.stringify({ summary: 's', verdict: 'LGTM', lineComments: [] }));
  assert.equal(r.verdict, 'COMMENT');
});

test('parseReview treats non-array lineComments as empty instead of rejecting', () => {
  const r = parseReview(JSON.stringify({ summary: 's', verdict: 'APPROVE', lineComments: {} }));
  assert.equal(r.lineComments.length, 0);
});

test('parseReview rejects lineComment with wrong field types', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'APPROVE',
    lineComments: [{ file: 'a', line: '5', type: 'issue', body: 'b' }],
  }));
  assert.equal(r.lineComments.length, 0);
});

test('parseReview drops lineComment with invalid type', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'APPROVE',
    lineComments: [{ file: 'a', line: 5, type: 'nit', body: 'b' }],
  }));
  assert.equal(r.lineComments.length, 0);
});

test('parseReview throws on non-JSON input', () => {
  assert.throws(() => parseReview('not json at all'));
});

test('parseReview keeps valid severity, category, confidence, and rationale', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'REQUEST_CHANGES',
    lineComments: [{
      file: 'a.ts', line: 5, type: 'issue', body: 'b',
      severity: 'major', category: 'security', confidence: 'high', rationale: 'read the schema',
    }],
  }));
  const c = r.lineComments[0];
  assert.equal(c.severity, 'major');
  assert.equal(c.category, 'security');
  assert.equal(c.confidence, 'high');
  assert.equal(c.rationale, 'read the schema');
});

test('parseReview drops comments with invalid enum values for required fields', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'COMMENT',
    lineComments: [{
      file: 'a.ts', line: 5, type: 'note', body: 'b',
      severity: 'catastrophic', category: 'vibes', confidence: 'certain',
    }],
  }));
  assert.equal(r.lineComments.length, 0);
});

test('parseReview drops comments missing required fields', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'COMMENT',
    lineComments: [{ file: 'a.ts', line: 5, type: 'note', body: 'b', severity: 'NIT' }],
  }));
  assert.equal(r.lineComments.length, 0);
});

test('parseReview downgrades low-confidence issues to suggestions instead of rejecting', () => {
  const issue = {
    file: 'a.ts', line: 5, type: 'issue', body: 'b', severity: 'major',
    category: 'correctness', confidence: 'low', rationale: 'The added branch returns null.',
  };
  const r = parseReview(JSON.stringify({ summary: 's', verdict: 'REQUEST_CHANGES', lineComments: [issue] }));
  assert.equal(r.lineComments.length, 1);
  assert.equal(r.lineComments[0].type, 'suggestion');
  assert.equal(r.verdict, 'COMMENT');
});

test('parseReview self-heals a verdict/issue mismatch instead of rejecting', () => {
  const highConfidenceIssue = {
    file: 'a.ts', line: 5, type: 'issue', body: 'b', severity: 'major',
    category: 'correctness', confidence: 'high', rationale: 'The added branch returns null.',
  };
  const r = parseReview(JSON.stringify({ summary: 's', verdict: 'APPROVE', lineComments: [highConfidenceIssue] }));
  assert.equal(r.verdict, 'REQUEST_CHANGES');
  assert.equal(r.lineComments[0].type, 'issue');
});

test('parseReview ignores unexpected top-level and comment fields instead of rejecting', () => {
  const r1 = parseReview(JSON.stringify({ summary: 's', verdict: 'APPROVE', lineComments: [], extra: true }));
  assert.equal(r1.verdict, 'APPROVE');

  const r2 = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'COMMENT',
    lineComments: [{
      file: 'a.ts', line: 5, type: 'note', body: 'b', severity: 'minor',
      category: 'tests', confidence: 'low', extra: true,
    }],
  }));
  assert.equal(r2.lineComments.length, 1);
});

test('parseReview truncates an over-long summary instead of rejecting', () => {
  const longSummary = 's'.repeat(900);
  const r = parseReview(JSON.stringify({ summary: longSummary, verdict: 'APPROVE', lineComments: [] }));
  assert.equal(r.summary.length, 800);
});

test('parseReview collapses embedded newlines in a comment body instead of rejecting', () => {
  const r = parseReview(JSON.stringify({
    summary: 's',
    verdict: 'COMMENT',
    lineComments: [{
      file: 'a.ts', line: 5, type: 'note', body: 'line one\nline two', severity: 'minor',
      category: 'tests', confidence: 'medium',
    }],
  }));
  assert.equal(r.lineComments.length, 1);
  assert.equal(r.lineComments[0].body, 'line one line two');
});
