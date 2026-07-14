import crypto from 'node:crypto';

export function createNonce(): string {
  return crypto.randomBytes(18).toString('base64url');
}

export function buildMainWebviewHtml(
  sourceHtml: string,
  cspSource: string,
  toResourceUri: (assetPath: string) => string,
  nonce = createNonce(),
): string {
  let html = sourceHtml.replace(/(src|href)="\.\/?([^"/][^"]*)"/g, (_match, attribute, assetPath) =>
    `${attribute}="${toResourceUri(assetPath)}"`);
  html = html.replace(/(src|href)="\/([^"]+)"/g, (_match, attribute, assetPath) =>
    `${attribute}="${toResourceUri(assetPath)}"`);
  html = html.replace(/<script\b(?![^>]*\bnonce=)/g, `<script nonce="${nonce}"`);

  const policy = [
    "default-src 'none'",
    `script-src 'nonce-${nonce}'`,
    `style-src ${cspSource} 'unsafe-inline'`,
    `font-src ${cspSource}`,
    `img-src ${cspSource} data:`,
    "connect-src 'none'",
    "object-src 'none'",
    "frame-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
  ].join('; ');
  const csp = `<meta http-equiv="Content-Security-Policy" content="${policy}">`;
  if (!html.includes('<head>')) {
    throw new Error('Webview HTML is missing a <head> element');
  }
  return html.replace('<head>', `<head>\n  ${csp}`);
}

export function buildLauncherHtml(cspSource: string, nonce = createNonce()): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; img-src ${cspSource}; base-uri 'none'; form-action 'none'">
  <title>PR Pilot</title>
  <style nonce="${nonce}">
    body {
      color: var(--vscode-foreground);
      background: var(--vscode-sideBar-background);
      font-family: var(--vscode-font-family);
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 16px;
      text-align: center;
    }
    button {
      color: var(--vscode-button-foreground);
      background: var(--vscode-button-background);
      border: 0;
      border-radius: 2px;
      cursor: pointer;
      padding: 6px 12px;
    }
    button:hover { background: var(--vscode-button-hoverBackground); }
    p {
      color: var(--vscode-descriptionForeground);
      line-height: 1.4;
      margin: 0;
      font-size: 0.9em;
    }
  </style>
</head>
<body>
  <button id="open-pr-pilot" type="button">Open PR Pilot</button>
  <p>The PR Pilot workspace opens in an editor tab.</p>
  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    document.getElementById('open-pr-pilot').addEventListener('click', () => {
      vscode.postMessage({ type: 'open' });
    });
  </script>
</body>
</html>`;
}

export function buildErrorHtml(message: string, nonce = createNonce()): string {
  const escapedMessage = message
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${nonce}'; base-uri 'none'; form-action 'none'">
  <title>PR Pilot</title>
  <style nonce="${nonce}">body { color: var(--vscode-errorForeground); background: var(--vscode-editor-background); font-family: var(--vscode-editor-font-family); padding: 16px; }</style>
</head>
<body><p>${escapedMessage}</p></body>
</html>`;
}
