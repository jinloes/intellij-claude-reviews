import { spawn, type ChildProcessWithoutNullStreams } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

const REQUEST_TIMEOUT_MS = 5_000;
const HEADER_TERMINATOR = '\r\n\r\n';

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (err: Error) => void;
}

interface SidecarRpcResponse {
    id?: number;
    result?: unknown;
    error?: { message?: string };
}

export interface SidecarGitHubAuthResult {
    status: 'authenticated' | 'not_installed' | 'not_authenticated' | 'api_failed' | 'invalid_base_url';
    username: string | null;
    message: string;
}

export interface SidecarPrListResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    query: string | null;
    resultLimit: number;
    limited: boolean;
    prs: Array<{
        number: number;
        title: string;
        owner: string;
        repo: string;
        author: string;
        createdAt: string;
        htmlUrl: string;
        isDraft: boolean;
    }>;
}

export interface SidecarPrDetailHead {
    sha: string;
    ref: string;
    repoFullName: string | null;
    cloneUrl: string | null;
}

export interface SidecarPrDiffResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    diff: string | null;
    truncated: boolean;
    limitBytes: number;
}

export interface SidecarPrDetailResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    detail: {
        merged: boolean;
        title: string;
        body: string;
        head: SidecarPrDetailHead | null;
        baseRepoFullName: string | null;
    } | null;
}

const AUTH_STATUSES = new Set<SidecarGitHubAuthResult['status']>([
    'authenticated',
    'not_installed',
    'not_authenticated',
    'api_failed',
    'invalid_base_url',
]);

const PR_LIST_STATUSES = new Set<SidecarPrListResult['status']>([
    'ok',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'rate_limited',
    'network_error',
    'api_failed',
]);

const PR_DETAIL_STATUSES = new Set<SidecarPrDetailResult['status']>([
    'ok',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'invalid_request',
    'rate_limited',
    'network_error',
    'api_failed',
]);
const PR_DIFF_STATUSES = new Set<SidecarPrDiffResult['status']>(PR_DETAIL_STATUSES);

/** Validates the token-free result shape returned by `github/checkAuth`. */
export function parseGitHubAuthResult(value: unknown): SidecarGitHubAuthResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string' || !AUTH_STATUSES.has(result.status as SidecarGitHubAuthResult['status'])) {
        return null;
    }
    if (result.username !== null && typeof result.username !== 'string') return null;
    if (typeof result.message !== 'string') return null;
    return {
        status: result.status as SidecarGitHubAuthResult['status'],
        username: typeof result.username === 'string' ? result.username : null,
        message: result.message,
    };
}

/** Validates the token-free result shape returned by `prs/list`. */
export function parsePrListResult(value: unknown): SidecarPrListResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string' || !PR_LIST_STATUSES.has(result.status as SidecarPrListResult['status'])) {
        return null;
    }
    if (typeof result.message !== 'string'
        || (result.query !== null && typeof result.query !== 'string')
        || typeof result.resultLimit !== 'number'
        || typeof result.limited !== 'boolean'
        || !Array.isArray(result.prs)) {
        return null;
    }
    const prs = result.prs.map((value) => {
        if (!value || typeof value !== 'object') return null;
        const pr = value as Record<string, unknown>;
        if (!Number.isInteger(pr.number)
            || typeof pr.title !== 'string'
            || typeof pr.owner !== 'string'
            || typeof pr.repo !== 'string'
            || typeof pr.author !== 'string'
            || typeof pr.createdAt !== 'string'
            || typeof pr.htmlUrl !== 'string'
            || typeof pr.isDraft !== 'boolean') {
            return null;
        }
        return {
            number: pr.number,
            title: pr.title,
            owner: pr.owner,
            repo: pr.repo,
            author: pr.author,
            createdAt: pr.createdAt,
            htmlUrl: pr.htmlUrl,
            isDraft: pr.isDraft,
        };
    });
    if (prs.some((pr) => pr === null)) return null;
    return {
        status: result.status as SidecarPrListResult['status'],
        message: result.message,
        query: typeof result.query === 'string' ? result.query : null,
        resultLimit: result.resultLimit,
        limited: result.limited,
        prs: prs as SidecarPrListResult['prs'],
    };
}

/** Validates the token-free result shape returned by `prs/getDetail`. */
export function parsePrDetailResult(value: unknown): SidecarPrDetailResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_DETAIL_STATUSES.has(result.status as SidecarPrDetailResult['status'])
        || typeof result.message !== 'string'
        || (result.detail !== null && typeof result.detail !== 'object')) {
        return null;
    }
    if (result.detail === null) {
        return result.status === 'ok' ? null : {
            status: result.status as SidecarPrDetailResult['status'],
            message: result.message,
            detail: null,
        };
    }
    const detail = result.detail as Record<string, unknown>;
    if (typeof detail.merged !== 'boolean'
        || typeof detail.title !== 'string'
        || typeof detail.body !== 'string'
        || (detail.baseRepoFullName !== null && typeof detail.baseRepoFullName !== 'string')) {
        return null;
    }
    let head: SidecarPrDetailHead | null = null;
    if (detail.head !== null) {
        if (!detail.head || typeof detail.head !== 'object') return null;
        const rawHead = detail.head as Record<string, unknown>;
        if (typeof rawHead.sha !== 'string'
            || typeof rawHead.ref !== 'string'
            || (rawHead.repoFullName !== null && typeof rawHead.repoFullName !== 'string')
            || (rawHead.cloneUrl !== null && typeof rawHead.cloneUrl !== 'string')) {
            return null;
        }
        head = {
            sha: rawHead.sha,
            ref: rawHead.ref,
            repoFullName: typeof rawHead.repoFullName === 'string' ? rawHead.repoFullName : null,
            cloneUrl: typeof rawHead.cloneUrl === 'string' ? rawHead.cloneUrl : null,
        };
    }
    return {
        status: result.status as SidecarPrDetailResult['status'],
        message: result.message,
        detail: {
            merged: detail.merged,
            title: detail.title,
            body: detail.body,
            head,
            baseRepoFullName: typeof detail.baseRepoFullName === 'string' ? detail.baseRepoFullName : null,
        },
    };
}

export function parsePrDiffResult(value: unknown): SidecarPrDiffResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string' || !PR_DIFF_STATUSES.has(result.status as SidecarPrDiffResult['status'])
        || typeof result.message !== 'string' || (result.diff !== null && typeof result.diff !== 'string')
        || typeof result.truncated !== 'boolean' || typeof result.limitBytes !== 'number') return null;
    if ((result.status === 'ok') !== (typeof result.diff === 'string')) return null;
    return { status: result.status as SidecarPrDiffResult['status'], message: result.message,
        diff: typeof result.diff === 'string' ? result.diff : null, truncated: result.truncated, limitBytes: result.limitBytes };
}

/** Encodes a JSON-RPC payload with the same bounded Content-Length framing the sidecar's
 * StdioFrameCodec (Java) reads/writes. Kept as a pure function so framing can be unit tested
 * without spawning a real process. */
export function encodeFrame(payload: string): Buffer {
    const body = Buffer.from(payload, 'utf8');
    return Buffer.concat([Buffer.from(`Content-Length: ${body.length}${HEADER_TERMINATOR}`, 'ascii'), body]);
}

/** Extracts every complete Content-Length-framed message currently available in `buffer`,
 * invoking `onFrame` with each decoded UTF-8 body in order. Returns the remaining unconsumed
 * bytes (a partial frame, if any) so callers can keep appending as more data arrives. */
export function extractFrames(buffer: Uint8Array, onFrame: (body: string) => void): Buffer {
    let remaining: Buffer = Buffer.from(buffer);
    for (;;) {
        const headerEnd = remaining.indexOf(HEADER_TERMINATOR);
        if (headerEnd < 0) return remaining;
        const header = remaining.subarray(0, headerEnd).toString('ascii');
        const match = /Content-Length:\s*(\d+)/i.exec(header);
        if (!match) {
            // Unparseable header — drop it rather than spin forever on garbage input.
            return Buffer.alloc(0);
        }
        const length = parseInt(match[1], 10);
        const bodyStart = headerEnd + HEADER_TERMINATOR.length;
        if (remaining.length < bodyStart + length) return remaining;
        onFrame(remaining.subarray(bodyStart, bodyStart + length).toString('utf8'));
        remaining = remaining.subarray(bodyStart + length);
    }
}

/**
 * Best-effort client for the optional pr-pilot Java sidecar process. Speaks the same
 * Content-Length-framed JSON-RPC protocol as StdioFrameCodec/StdioJsonRpcServer (sidecar
 * module). The sidecar reduces logic duplication between hosts (e.g. PR search query
 * construction) but is never a hard dependency: every public method resolves to `null`
 * instead of throwing when the process can't be spawned, times out, or errors, so callers
 * always have a local TypeScript fallback path.
 */
export class SidecarClient {
    private child: ChildProcessWithoutNullStreams | null = null;
    private startFailed = false;
    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private buffer: Buffer = Buffer.alloc(0);

    constructor(
        private readonly jarPath: string | null,
        private readonly javaBinary = 'java',
    ) {}

    private ensureStarted(): boolean {
        if (this.startFailed) return false;
        if (this.child) return true;
        if (!this.jarPath) {
            this.startFailed = true;
            return false;
        }
        try {
            const child = spawn(this.javaBinary, ['-jar', this.jarPath], { stdio: ['pipe', 'pipe', 'pipe'] });
            child.on('error', () => this.failAllPending(new Error('Sidecar process failed to start')));
            child.on('exit', () => {
                this.child = null;
                this.failAllPending(new Error('Sidecar process exited'));
            });
            child.stdout.on('data', (chunk: Buffer) => this.onData(chunk));
            // Diagnostics only; the sidecar never writes protocol frames to stderr.
            child.stderr.on('data', () => { /* intentionally ignored */ });
            this.child = child;
            return true;
        } catch {
            this.startFailed = true;
            return false;
        }
    }

    private onData(chunk: Buffer): void {
        this.buffer = extractFrames(Buffer.concat([this.buffer, chunk]), (body) => this.handleMessage(body));
    }

    private handleMessage(body: string): void {
        let message: SidecarRpcResponse;
        try {
            message = JSON.parse(body) as SidecarRpcResponse;
        } catch {
            return;
        }
        if (typeof message.id !== 'number') return;
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        if (message.error) {
            pending.reject(new Error(message.error.message ?? 'Sidecar error'));
        } else {
            pending.resolve(message.result);
        }
    }

    private failAllPending(err: Error): void {
        for (const pending of this.pending.values()) pending.reject(err);
        this.pending.clear();
    }

    private request(method: string, params: unknown): Promise<unknown> {
        if (!this.ensureStarted() || !this.child) return Promise.reject(new Error('Sidecar unavailable'));
        const id = this.nextId++;
        const child = this.child;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`Sidecar request "${method}" timed out`));
            }, REQUEST_TIMEOUT_MS);
            this.pending.set(id, {
                resolve: (value) => { clearTimeout(timer); resolve(value); },
                reject: (err) => { clearTimeout(timer); reject(err); },
            });
            child.stdin.write(encodeFrame(JSON.stringify({ jsonrpc: '2.0', id, method, params })), (err) => {
                if (err) {
                    clearTimeout(timer);
                    this.pending.delete(id);
                    reject(err);
                }
            });
        });
    }

    /**
     * Builds a GitHub PR search query via the sidecar's pure `pr/buildSearchQuery` capability
     * (mirrors PrSearchQueryService). Resolves to `null` on any failure — spawn failure, missing
     * `java`, timeout, or a malformed response — so the caller falls back to the local
     * `buildPRSearchQuery` implementation in github.ts.
     */
    async buildSearchQuery(state: string, searchScope: string, currentRepo?: string): Promise<string | null> {
        try {
            const result = (await this.request('pr/buildSearchQuery', {
                state,
                searchScope,
                ...(currentRepo ? { currentRepo } : {}),
            })) as { query?: string } | undefined;
            return typeof result?.query === 'string' ? result.query : null;
        } catch {
            return null;
        }
    }

    /**
     * Detects the owner/repo for `path` via the sidecar's `repo/detect` capability (mirrors
     * RepoDetector, which additionally understands linked-worktree `.git` files that the local
     * TypeScript `detectCurrentRepo` does not). Resolves to `null` on any failure — spawn
     * failure, missing `java`, timeout, malformed response, or any non-`found` detection status
     * — so the caller falls back to the local `detectCurrentRepo` implementation in github.ts.
     */
    async detectRepo(path: string): Promise<string | null> {
        try {
            const result = (await this.request('repo/detect', { path })) as
                | { status?: string; repository?: { owner?: string; repo?: string } }
                | undefined;
            if (result?.status !== 'found') return null;
            const owner = result.repository?.owner;
            const repo = result.repository?.repo;
            return owner && repo ? `${owner}/${repo}` : null;
        } catch {
            return null;
        }
    }

    /**
     * Verifies GitHub CLI credentials and the GitHub API via `github/checkAuth`. The sidecar
     * never returns the token. Resolves to `null` on any transport or response-shape failure so
     * settings can retain their local `gh auth token` fallback.
     */
    async checkGitHubAuth(githubBaseUrl: string): Promise<SidecarGitHubAuthResult | null> {
        try {
            return parseGitHubAuthResult(await this.request('github/checkAuth', { githubBaseUrl }));
        } catch {
            return null;
        }
    }

    /**
     * Lists pull requests through the sidecar without returning a GitHub token to the extension.
     * Resolves to null only on transport or response-shape failures, leaving callers free to use
     * the local TypeScript implementation; valid domain failures are returned as-is.
     */
    async listPullRequests(
        githubBaseUrl: string,
        state: string,
        searchScope: string,
        currentRepo?: string,
    ): Promise<SidecarPrListResult | null> {
        try {
            return parsePrListResult(await this.request('prs/list', {
                githubBaseUrl,
                state,
                searchScope,
                ...(currentRepo ? { currentRepo } : {}),
            }));
        } catch {
            return null;
        }
    }

    /**
     * Retrieves PR metadata through the sidecar without returning a GitHub token to the extension.
     * Resolves to null only for transport or response-shape failures so callers retain local
     * fallback behavior; valid domain failures are returned as-is.
     */
    async getPullRequestDetail(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarPrDetailResult | null> {
        try {
            return parsePrDetailResult(await this.request('prs/getDetail', {
                githubBaseUrl,
                owner,
                repo,
                number,
            }));
        } catch {
            return null;
        }
    }

    async getPullRequestDiff(githubBaseUrl: string, owner: string, repo: string, number: number): Promise<SidecarPrDiffResult | null> {
        try { return parsePrDiffResult(await this.request('prs/getDiff', { githubBaseUrl, owner, repo, number, mode: 'review' })); }
        catch { return null; }
    }

    dispose(): void {
        const child = this.child;
        this.child = null;
        if (child) {
            child.stdin.end();
            child.kill();
        }
        this.failAllPending(new Error('Sidecar disposed'));
    }
}

/**
 * Resolves the sidecar jar to spawn: the packaged copy staged alongside the extension by
 * `scripts/stage-sidecar.mjs` (release/`.vsix` builds), falling back to the sibling Gradle
 * module's `build/libs` bootJar output for local development. Returns `null` (never throws)
 * when no jar can be found, matching resolveWebviewDistPath's dev/packaged fallback shape.
 */
export function resolveSidecarJarPath(
    extensionRoot: string,
    existsSync: (candidate: string) => boolean = fs.existsSync,
    readdirSync: (dir: string) => string[] = (dir) => fs.readdirSync(dir),
): string | null {
    const packagedJar = path.join(extensionRoot, 'sidecar', 'pr-pilot-sidecar.jar');
    if (existsSync(packagedJar)) return packagedJar;

    const devLibsDir = path.resolve(extensionRoot, '..', 'sidecar', 'build', 'libs');
    if (!existsSync(devLibsDir)) return null;
    try {
        const candidate = readdirSync(devLibsDir).find((name) => name === 'pr-pilot-sidecar.jar');
        return candidate ? path.join(devLibsDir, candidate) : null;
    } catch {
        return null;
    }
}
