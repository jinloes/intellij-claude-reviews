import * as fs from 'fs';
import * as path from 'path';

/**
 * Resolves and reads a repository's review-guidance docs from a working directory (the PR-branch
 * worktree or the open workspace) so the model can weight findings against the project's own
 * conventions. Mirrors the JVM `RepoGuidelinesReader` (review-engine) used by the IntelliJ host.
 *
 * The set of files is configurable rather than hardcoded: each entry is either a literal relative
 * path (e.g. `AGENTS.md`, `.linkedin/ai-agent/coding-pattern.md`) or a glob (e.g. `**​/style.md`,
 * `.linkedin/**​/*.md`), so teams surface repo-specific guidance without a code change.
 */

/** Default guidance files scanned when the user has not configured their own list. */
export const DEFAULT_GUIDANCE_GLOBS = [
    'AGENTS.md',
    'CONTRIBUTING.md',
    '.github/CONTRIBUTING.md',
    'docs/CONTRIBUTING.md',
    '.github/pull_request_template.md',
];

/** Cap on total guidance bytes fed to the prompt so a large doc can't blow up the context. */
export const MAX_GUIDELINES_BYTES = 6_000;

const MAX_FILES_SCANNED = 5_000;
const MAX_DEPTH = 8;
const SKIP_DIRS = new Set([
    '.git', 'node_modules', 'build', 'dist', 'target', 'out', '.gradle', '.idea', '.venv', 'venv',
]);

export function isGlob(pattern: string): boolean {
    return /[*?[{]/.test(pattern);
}

/**
 * Translates a minimal glob (`**`, `*`, `?`) into a regex matched against a '/'-joined relative
 * path. `**​/` matches zero or more leading segments so `**​/style.md` also matches `style.md` at
 * the root. Mirrors RepoGuidelinesReader.globToRegex.
 */
export function globToRegex(glob: string): RegExp {
    let re = '^';
    let i = 0;
    const n = glob.length;
    while (i < n) {
        const c = glob[i];
        if (c === '*') {
            const doubleStar = i + 1 < n && glob[i + 1] === '*';
            if (doubleStar) {
                i += 2;
                if (i < n && glob[i] === '/') {
                    i++;
                    re += '(?:.*/)?';
                } else {
                    re += '.*';
                }
                continue;
            }
            re += '[^/]*';
        } else if (c === '?') {
            re += '[^/]';
        } else if ('\\.[]{}()+-^$|'.includes(c)) {
            re += `\\${c}`;
        } else {
            re += c;
        }
        i++;
    }
    return new RegExp(`${re}$`);
}

/** Bounded breadth-first walk returning '/'-joined relative paths, skipping heavy directories. */
function collectRelativeFiles(root: string): string[] {
    const files: string[] = [];
    const queue: Array<{ dir: string; prefix: string; depth: number }> = [{ dir: root, prefix: '', depth: 0 }];
    while (queue.length > 0 && files.length < MAX_FILES_SCANNED) {
        const entry = queue.shift()!;
        let children: fs.Dirent[];
        try {
            children = fs.readdirSync(entry.dir, { withFileTypes: true });
        } catch {
            continue;
        }
        for (const child of children) {
            const rel = entry.prefix ? `${entry.prefix}/${child.name}` : child.name;
            if (child.isDirectory()) {
                if (entry.depth < MAX_DEPTH && !SKIP_DIRS.has(child.name)) {
                    queue.push({ dir: path.join(entry.dir, child.name), prefix: rel, depth: entry.depth + 1 });
                }
            } else if (child.isFile()) {
                files.push(rel);
                if (files.length >= MAX_FILES_SCANNED) break;
            }
        }
    }
    return files;
}

/**
 * Resolves `patterns` to a de-duplicated, priority-ordered list of relative paths under `dir`.
 * Literal paths are checked directly; globs are matched against a bounded walk (matches sorted for
 * determinism). Mirrors RepoGuidelinesReader.resolvePaths.
 */
export function resolvePaths(dir: string, patterns: string[]): string[] {
    const ordered: string[] = [];
    const seen = new Set<string>();
    let allFiles: string[] | null = null;
    const add = (rel: string): void => {
        if (!seen.has(rel)) {
            seen.add(rel);
            ordered.push(rel);
        }
    };
    for (const raw of patterns) {
        const pattern = raw.trim().replace(/\\/g, '/');
        if (!pattern) continue;
        if (isGlob(pattern)) {
            if (allFiles === null) allFiles = collectRelativeFiles(dir);
            const re = globToRegex(pattern);
            allFiles.filter((f) => re.test(f)).sort().forEach(add);
        } else {
            try {
                if (fs.statSync(path.join(dir, pattern)).isFile()) add(pattern);
            } catch { /* missing — skip */ }
        }
    }
    return ordered;
}

/**
 * Reads the guidance files matching `globs` under `dir`, concatenated and capped at
 * {@link MAX_GUIDELINES_BYTES}. Returns '' when `dir` is empty or nothing matches. A blank/empty
 * `globs` falls back to {@link DEFAULT_GUIDANCE_GLOBS}.
 */
export function readRepoGuidelines(dir: string, globs?: string[]): string {
    if (!dir) return '';
    const patterns = globs && globs.length > 0 ? globs : DEFAULT_GUIDANCE_GLOBS;
    const parts: string[] = [];
    let total = 0;
    for (const rel of resolvePaths(dir, patterns)) {
        if (total >= MAX_GUIDELINES_BYTES) break;
        try {
            let content = fs.readFileSync(path.join(dir, rel), 'utf8').trim();
            if (!content) continue;
            const remaining = MAX_GUIDELINES_BYTES - total;
            if (content.length > remaining) content = `${content.substring(0, remaining)}\n…(truncated)`;
            parts.push(`## ${rel}\n${content}`);
            total += content.length;
        } catch { /* unreadable — skip */ }
    }
    return parts.join('\n\n');
}

