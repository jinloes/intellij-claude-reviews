import test from 'node:test';
import assert from 'node:assert/strict';

import { buildErrorHtml, buildLauncherHtml, buildMainWebviewHtml } from '../src/webviewHtml';

test('main webview HTML applies a restrictive CSP and nonces scripts', () => {
  const html = buildMainWebviewHtml(
    '<!doctype html><html lang="en"><head></head><body><script type="module" src="./assets/app.js"></script><link href="/assets/app.css"></body></html>',
    'vscode-webview://origin',
    (assetPath) => `vscode-resource://${assetPath}`,
    'fixed-nonce',
  );

  assert.match(html, /default-src 'none'/);
  assert.match(html, /script-src 'nonce-fixed-nonce'/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /<script nonce="fixed-nonce" type="module"/);
  assert.match(html, /src="vscode-resource:\/\/assets\/app\.js"/);
  assert.match(html, /href="vscode-resource:\/\/assets\/app\.css"/);
});

test('launcher uses a nonce and a message instead of a command URI', () => {
  const html = buildLauncherHtml('vscode-webview://origin', 'fixed-nonce');

  assert.match(html, /default-src 'none'/);
  assert.match(html, /<style nonce="fixed-nonce">/);
  assert.match(html, /<script nonce="fixed-nonce">/);
  assert.match(html, /postMessage\(\{ type: 'open' }\)/);
  assert.doesNotMatch(html, /command:/);
});

test('main webview HTML rejects documents without a head', () => {
  assert.throws(
    () => buildMainWebviewHtml('<html lang="en"><body></body></html>', 'source', (assetPath) => assetPath),
    /missing a <head>/,
  );
});

test('error HTML applies CSP and escapes message content', () => {
  const html = buildErrorHtml('<script>alert("unsafe")</script>', 'fixed-nonce');

  assert.match(html, /default-src 'none'/);
  assert.match(html, /style-src 'nonce-fixed-nonce'/);
  assert.match(html, /&lt;script&gt;alert\(&quot;unsafe&quot;\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});
