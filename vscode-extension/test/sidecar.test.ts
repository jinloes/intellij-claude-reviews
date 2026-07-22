import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';

import { encodeFrame, extractFrames, resolveSidecarJarPath } from '../src/sidecar';

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

