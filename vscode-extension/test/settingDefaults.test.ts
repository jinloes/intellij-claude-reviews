import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

// Every VS Code setting is declared twice: once as a `contributes.configuration` default in
// package.json, and once as the fallback argument to `config().get(...)` in extension.ts. VS Code
// reads the package.json default, so a mismatch is silent — the reader fallback only shows up when
// the contribution is missing. These tests pin the pairs that matter.

/**
 * Walks up from the compiled test location to the extension root. Tests execute from `dist-test/`,
 * so the root is not a fixed number of levels above `__dirname`.
 */
function findExtensionRoot(): string {
    let dir = __dirname;
    for (let i = 0; i < 6; i++) {
        const candidate = path.join(dir, 'package.json');
        if (fs.existsSync(candidate)) {
            const parsed = JSON.parse(fs.readFileSync(candidate, 'utf8'));
            if (parsed?.name === 'pr-pilot-vscode') return dir;
        }
        dir = path.dirname(dir);
    }
    throw new Error('could not locate the vscode-extension root from ' + __dirname);
}

const extensionRoot = findExtensionRoot();

function packageJsonDefaults(): Record<string, unknown> {
    const raw = fs.readFileSync(path.join(extensionRoot, 'package.json'), 'utf8');
    const properties = JSON.parse(raw)?.contributes?.configuration?.properties;
    assert.ok(properties, 'package.json is missing contributes.configuration.properties');
    return properties;
}

function readerFallback(setting: string): string {
    const source = fs.readFileSync(path.join(extensionRoot, 'src', 'extension.ts'), 'utf8');
    const match = new RegExp(
        `config\\(\\)\\.get<boolean>\\('${setting}',\\s*(true|false)\\s*\\)`,
    ).exec(source);
    assert.ok(match, `extension.ts has no boolean reader for '${setting}'`);
    return match[1];
}

test('reviewSelfCritique defaults to on in the contribution', () => {
    const property = packageJsonDefaults()['pr-pilot.reviewSelfCritique'] as { default: boolean };
    assert.equal(property.default, true);
});

test('reviewSelfCritique reader fallback matches the contribution default', () => {
    const property = packageJsonDefaults()['pr-pilot.reviewSelfCritique'] as { default: boolean };
    assert.equal(readerFallback('reviewSelfCritique'), String(property.default));
});

test('every boolean setting reader fallback matches its contribution default', () => {
    const properties = packageJsonDefaults();
    const source = fs.readFileSync(path.join(extensionRoot, 'src', 'extension.ts'), 'utf8');

    for (const [key, value] of Object.entries(properties)) {
        const property = value as { type?: string; default?: unknown };
        if (property.type !== 'boolean') continue;

        const setting = key.replace(/^pr-pilot\./, '');
        const match = new RegExp(
            `config\\(\\)\\.get<boolean>\\('${setting}',\\s*(true|false)\\s*\\)`,
        ).exec(source);
        // Not every contributed setting is read through this helper shape; only check the ones
        // that are, so adding a differently-read setting does not fail this guard spuriously.
        if (!match) continue;

        assert.equal(
            match[1],
            String(property.default),
            `'${key}' defaults to ${String(property.default)} in package.json but `
            + `extension.ts falls back to ${match[1]}`,
        );
    }
});


