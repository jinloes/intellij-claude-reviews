import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

import {
    findGitRoot,
    createWorktree,
    pinnedCommitish,
    removeWorktree,
    runGit,
    worktreePath,
} from '../src/worktree';

function mkTmp(prefix: string): string {
    return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function rm(dir: string): void {
    fs.rmSync(dir, { recursive: true, force: true });
}

/** Returns the resolved HEAD commit of a repository or worktree. */
function headSha(dir: string): string {
    return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: dir, encoding: 'utf8' }).trim();
}

/**
 * Initialises a minimal git repository with one commit on `main` and an `origin` remote pointing
 * at itself, so tests can call `createWorktree(..., 'main', ...)` without a real remote.
 */
function initRepo(dir: string): void {
    const git = (...args: string[]) => execFileSync('git', args, { cwd: dir, stdio: 'pipe' });
    git('init', '-b', 'main');
    git('config', 'user.email', 'test@test.com');
    git('config', 'user.name', 'Test');
    fs.writeFileSync(path.join(dir, 'hello.txt'), 'hello');
    git('add', '.');
    git('commit', '-m', 'initial');
    git('remote', 'add', 'origin', dir);
}

// ── findGitRoot ──────────────────────────────────────────────────────────────

test('findGitRoot returns null for a directory with no .git ancestor', () => {
    const dir = mkTmp('gw-root-none-');
    try {
        assert.equal(findGitRoot(dir), null);
    } finally {
        rm(dir);
    }
});

test('findGitRoot returns the directory itself when .git is a child', () => {
    const dir = mkTmp('gw-root-self-');
    try {
        fs.mkdirSync(path.join(dir, '.git'));
        assert.equal(findGitRoot(dir), fs.realpathSync(dir));
    } finally {
        rm(dir);
    }
});

test('findGitRoot finds .git in a parent directory', () => {
    const dir = mkTmp('gw-root-parent-');
    try {
        fs.mkdirSync(path.join(dir, '.git'));
        const nested = path.join(dir, 'a', 'b', 'c');
        fs.mkdirSync(nested, { recursive: true });
        assert.equal(findGitRoot(nested), fs.realpathSync(dir));
    } finally {
        rm(dir);
    }
});

test('findGitRoot accepts .git as a file (git worktree metadata)', () => {
    const dir = mkTmp('gw-root-file-');
    try {
        fs.writeFileSync(path.join(dir, '.git'), 'gitdir: ../../.git/worktrees/foo');
        assert.equal(findGitRoot(dir), fs.realpathSync(dir));
    } finally {
        rm(dir);
    }
});

// ── createWorktree / removeWorktree (real git) ────────────────────────────────

test('createWorktree creates a worktree on an existing branch', async () => {
    const repo = mkTmp('gw-repo-');
    const wt = path.join(os.tmpdir(), `gw-wt-${Date.now()}`);
    try {
        initRepo(repo);
        await createWorktree(repo, 'main', '', wt);
        assert.equal(fs.existsSync(wt), true);
        assert.equal(fs.existsSync(path.join(wt, 'hello.txt')), true);
    } finally {
        rm(wt);
        rm(repo);
    }
});

test('createWorktree throws for a non-existent branch', async () => {
    const repo = mkTmp('gw-repo-bad-');
    const wt = path.join(os.tmpdir(), `gw-wt-bad-${Date.now()}`);
    try {
        initRepo(repo);
        await assert.rejects(
            () => createWorktree(repo, 'does-not-exist', '', wt),
            /git fetch/,
        );
    } finally {
        rm(wt);
        rm(repo);
    }
});

// The point of pinning: the reviewed commit is checked out even after the branch moves on, so the
// agent greps the code the diff was rendered from.
test('createWorktree checks out the reviewed commit even after the branch tip moves', async () => {
    const repo = mkTmp('gw-repo-pin-');
    const wt = path.join(os.tmpdir(), `gw-wt-pin-${Date.now()}`);
    try {
        initRepo(repo);
        const reviewed = headSha(repo);
        fs.writeFileSync(path.join(repo, 'pushed-later.txt'), 'later');
        execFileSync('git', ['add', '.'], { cwd: repo, stdio: 'pipe' });
        execFileSync('git', ['commit', '-m', 'pushed after review started'], { cwd: repo, stdio: 'pipe' });

        await createWorktree(repo, 'main', reviewed, wt);

        assert.equal(fs.existsSync(path.join(wt, 'hello.txt')), true);
        assert.equal(fs.existsSync(path.join(wt, 'pushed-later.txt')), false);
        assert.equal(headSha(wt), reviewed);
    } finally {
        rm(wt);
        rm(repo);
    }
});

test('createWorktree falls back to the branch tip when the reviewed commit is unavailable', async () => {
    const repo = mkTmp('gw-repo-orphan-');
    const wt = path.join(os.tmpdir(), `gw-wt-orphan-${Date.now()}`);
    try {
        initRepo(repo);
        execFileSync('git', ['commit', '--allow-empty', '-m', 'tip'], { cwd: repo, stdio: 'pipe' });
        const tip = headSha(repo);

        await createWorktree(repo, 'main', 'a'.repeat(40), wt);

        assert.equal(headSha(wt), tip);
    } finally {
        rm(wt);
        rm(repo);
    }
});

// ── pinnedCommitish ───────────────────────────────────────────────────────────

test('pinnedCommitish returns the sha when the commit is present, including abbreviated', async () => {
    const repo = mkTmp('gw-pin-present-');
    try {
        initRepo(repo);
        const sha = headSha(repo);
        assert.equal(await pinnedCommitish(repo, sha, 'origin/main'), sha);
        assert.equal(await pinnedCommitish(repo, sha.slice(0, 8), 'origin/main'), sha.slice(0, 8));
    } finally {
        rm(repo);
    }
});

test('pinnedCommitish falls back when the sha is blank or unknown locally', async () => {
    const repo = mkTmp('gw-pin-blank-');
    try {
        initRepo(repo);
        assert.equal(await pinnedCommitish(repo, '', 'origin/main'), 'origin/main');
        assert.equal(await pinnedCommitish(repo, '   ', 'FETCH_HEAD'), 'FETCH_HEAD');
        assert.equal(await pinnedCommitish(repo, 'a'.repeat(40), 'origin/main'), 'origin/main');
    } finally {
        rm(repo);
    }
});

// headSha arrives from the GitHub API, so a value git could read as an option or a different
// revision must never reach the command line.
test('pinnedCommitish refuses revisions that are not hex object names', async () => {
    const repo = mkTmp('gw-pin-evil-');
    try {
        initRepo(repo);
        // A real ref that resolves, but is not an object name — must still be rejected.
        assert.equal(await pinnedCommitish(repo, 'main', 'origin/main'), 'origin/main');
        assert.equal(await pinnedCommitish(repo, 'HEAD', 'origin/main'), 'origin/main');
        assert.equal(await pinnedCommitish(repo, '--upload-pack=touch /tmp/x', 'origin/main'), 'origin/main');
        assert.equal(await pinnedCommitish(repo, 'abc', 'origin/main'), 'origin/main');
    } finally {
        rm(repo);
    }
});

test('removeWorktree removes a worktree that was created', async () => {
    const repo = mkTmp('gw-repo-rm-');
    const wt = path.join(os.tmpdir(), `gw-wt-rm-${Date.now()}`);
    try {
        initRepo(repo);
        await createWorktree(repo, 'main', '', wt);
        assert.equal(fs.existsSync(wt), true);
        await removeWorktree(repo, wt);
        assert.equal(fs.existsSync(wt), false);
    } finally {
        rm(wt);
        rm(repo);
    }
});

test('removeWorktree does not throw when the worktree directory is missing', async () => {
    const repo = mkTmp('gw-repo-rm-missing-');
    try {
        initRepo(repo);
        // --force tolerates a missing dir; should resolve without throwing.
        await removeWorktree(repo, path.join(repo, 'no-such-wt'));
    } finally {
        rm(repo);
    }
});

// ── runGit (error handling) ───────────────────────────────────────────────────

test('runGit throws with the subcommand name on a non-zero git command', async () => {
    const dir = mkTmp('gw-run-');
    try {
        initRepo(dir);
        await assert.rejects(
            () => runGit(dir, 10, ['rev-parse', '--verify', 'nonexistent-ref-xyz']),
            /git rev-parse/,
        );
    } finally {
        rm(dir);
    }
});

// ── worktreePath ──────────────────────────────────────────────────────────────

test('worktreePath returns a unique temp path scoped to the PR number', () => {
    const a = worktreePath(42);
    const b = worktreePath(42);
    assert.ok(a.startsWith(os.tmpdir()));
    assert.ok(path.basename(a).startsWith('pr-pilot-wt-42-'));
    assert.notEqual(a, b);
});
