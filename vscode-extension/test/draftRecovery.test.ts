import * as assert from 'assert';
import test from 'node:test';
import { DraftRecoveryStore, type RecoveryMemento } from '../src/draftRecovery';
import type { ReviewResult } from '../src/models';

class MemoryMemento implements RecoveryMemento {
    readonly values = new Map<string, unknown>();

    get<T>(key: string): T | undefined {
        return this.values.get(key) as T | undefined;
    }

    async update(key: string, value: unknown): Promise<void> {
        this.values.set(key, value);
    }
}

const result: ReviewResult = {
    summary: 'summary',
    verdict: 'COMMENT',
    lineComments: [{ file: 'a.ts', line: 1, type: 'note', body: 'note' }],
};

test('persists and clears a token-free recovery snapshot', async () => {
    const store = new DraftRecoveryStore(new MemoryMemento());
    await store.save('acme/repo#1', result, []);

    assert.deepEqual(store.get('acme/repo#1')?.result, result);
    await store.clear('acme/repo#1');
    assert.equal(store.get('acme/repo#1'), null);
});

test('returns copies so callers cannot mutate persisted recovery data', async () => {
    const store = new DraftRecoveryStore(new MemoryMemento());
    await store.save('acme/repo#1', result, []);

    const restored = store.get('acme/repo#1');
    restored?.result.lineComments.push({ file: 'b.ts', line: 2, type: 'note', body: 'other' });

    assert.equal(store.get('acme/repo#1')?.result.lineComments.length, 1);
});
