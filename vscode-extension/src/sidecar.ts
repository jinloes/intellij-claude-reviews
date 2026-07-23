import { spawn, type ChildProcessWithoutNullStreams } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

const REQUEST_TIMEOUT_MS = 60_000;
const HEADER_TERMINATOR = '\r\n\r\n';
const SIDECAR_PROTOCOL_VERSION = 1;
const REQUIRED_CAPABILITIES = [
    'githubAuth',
    'prDetail',
    'prDiff',
    'prList',
    'repoDetect',
    'draftReview',
    'draftReviewMutations',
    'prSearch',
    'starredRepos',
    'existingReviews',
] as const;

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (err: Error) => void;
}

interface SidecarRpcResponse {
    jsonrpc?: string;
    id?: number;
    result?: unknown;
    error?: { code?: number; message?: string };
}

export type SidecarSpawn = (
    command: string,
    args: string[],
    options: { stdio: ['pipe', 'pipe', 'pipe'] },
) => ChildProcessWithoutNullStreams;

export interface SidecarInitializeResult {
    serviceName: string;
    serviceVersion: string;
    protocolVersion: number;
    capabilities: Record<string, boolean>;
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

export interface SidecarPrSearchResult {
    status: SidecarPrListResult['status'] | 'invalid_request';
    message: string;
    resultLimit: number;
    limited: boolean;
    prs: SidecarPrListResult['prs'];
}

export interface SidecarStarredReposResult {
    status: SidecarPrListResult['status'];
    message: string;
    resultLimit: number;
    limited: boolean;
    repositories: string[];
}

export interface SidecarExistingReviewsResult {
    status: SidecarPrDetailResult['status'];
    message: string;
    summary: string;
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

export interface SidecarLineComment {
    file: string;
    line: number;
    type: string;
    body: string;
    severity: string | null;
    category: string | null;
    confidence: string | null;
    rationale: string | null;
}

/** Request-shaped comment sent to `prs/saveDraftReview` — optional fields may be omitted. */
export interface SidecarCommentInput {
    file: string;
    line: number;
    type: string;
    body: string;
    severity?: string;
    category?: string;
    confidence?: string;
    rationale?: string;
}

export interface SidecarDraftReviewResult {
    status: 'ok' | 'none' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    id: string | null;
    commitId: string | null;
    review: {
        summary: string;
        verdict: string;
        lineComments: SidecarLineComment[];
        importedFromGitHub: boolean;
    } | null;
}

export interface SidecarDraftReviewMutationResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    reviewId: string | null;
    commentsDropped: boolean;
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
const PR_SEARCH_STATUSES = new Set<SidecarPrSearchResult['status']>([
    ...PR_LIST_STATUSES,
    'invalid_request',
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

const DRAFT_REVIEW_STATUSES = new Set<SidecarDraftReviewResult['status']>([
    'ok',
    'none',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'invalid_request',
    'rate_limited',
    'network_error',
    'api_failed',
]);

const DRAFT_REVIEW_MUTATION_STATUSES = new Set<SidecarDraftReviewMutationResult['status']>([
    'ok',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'invalid_request',
    'rate_limited',
    'network_error',
    'api_failed',
]);

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

export function parseInitializeResult(value: unknown): SidecarInitializeResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (result.serviceName !== 'pr-pilot-sidecar'
        || typeof result.serviceVersion !== 'string'
        || !Number.isInteger(result.protocolVersion)
        || !result.capabilities
        || typeof result.capabilities !== 'object'
        || Array.isArray(result.capabilities)) return null;
    const capabilities = result.capabilities as Record<string, unknown>;
    if (Object.values(capabilities).some((enabled) => typeof enabled !== 'boolean')) return null;
    return {
        serviceName: result.serviceName,
        serviceVersion: result.serviceVersion,
        protocolVersion: result.protocolVersion as number,
        capabilities: capabilities as Record<string, boolean>,
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

export function parsePrSearchResult(value: unknown): SidecarPrSearchResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_SEARCH_STATUSES.has(result.status as SidecarPrSearchResult['status'])) return null;
    const parsed = parsePrListResult({
        ...result,
        status: result.status === 'invalid_request' ? 'api_failed' : result.status,
        query: null,
    });
    return parsed === null ? null : {
        status: result.status as SidecarPrSearchResult['status'],
        message: parsed.message,
        resultLimit: parsed.resultLimit,
        limited: parsed.limited,
        prs: parsed.prs,
    };
}

export function parseStarredReposResult(value: unknown): SidecarStarredReposResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_LIST_STATUSES.has(result.status as SidecarStarredReposResult['status'])
        || typeof result.message !== 'string'
        || typeof result.resultLimit !== 'number'
        || typeof result.limited !== 'boolean'
        || !Array.isArray(result.repositories)
        || result.repositories.some((repository) => typeof repository !== 'string')) return null;
    return {
        status: result.status as SidecarStarredReposResult['status'],
        message: result.message,
        resultLimit: result.resultLimit,
        limited: result.limited,
        repositories: result.repositories as string[],
    };
}

export function parseExistingReviewsResult(value: unknown): SidecarExistingReviewsResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_DETAIL_STATUSES.has(result.status as SidecarExistingReviewsResult['status'])
        || typeof result.message !== 'string'
        || typeof result.summary !== 'string') return null;
    return {
        status: result.status as SidecarExistingReviewsResult['status'],
        message: result.message,
        summary: result.summary,
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

/** Validates the token-free result shape returned by `prs/getDraftReview`. */
export function parseDraftReviewResult(value: unknown): SidecarDraftReviewResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !DRAFT_REVIEW_STATUSES.has(result.status as SidecarDraftReviewResult['status'])
        || typeof result.message !== 'string'
        || (result.id !== null && typeof result.id !== 'string')
        || (result.commitId !== null && typeof result.commitId !== 'string')
        || (result.review !== null && typeof result.review !== 'object')) {
        return null;
    }
    if (result.review === null) {
        return result.status === 'ok' ? null : {
            status: result.status as SidecarDraftReviewResult['status'],
            message: result.message,
            id: typeof result.id === 'string' ? result.id : null,
            commitId: typeof result.commitId === 'string' ? result.commitId : null,
            review: null,
        };
    }
    const review = result.review as Record<string, unknown>;
    if (typeof review.summary !== 'string'
        || typeof review.verdict !== 'string'
        || typeof review.importedFromGitHub !== 'boolean'
        || !Array.isArray(review.lineComments)) {
        return null;
    }
    const lineComments = review.lineComments.map((entry) => {
        if (!entry || typeof entry !== 'object') return null;
        const comment = entry as Record<string, unknown>;
        if (typeof comment.file !== 'string'
            || !Number.isInteger(comment.line)
            || typeof comment.type !== 'string'
            || typeof comment.body !== 'string'
            || (comment.severity !== null && typeof comment.severity !== 'string')
            || (comment.category !== null && typeof comment.category !== 'string')
            || (comment.confidence !== null && typeof comment.confidence !== 'string')
            || (comment.rationale !== null && typeof comment.rationale !== 'string')) {
            return null;
        }
        return {
            file: comment.file,
            line: comment.line,
            type: comment.type,
            body: comment.body,
            severity: typeof comment.severity === 'string' ? comment.severity : null,
            category: typeof comment.category === 'string' ? comment.category : null,
            confidence: typeof comment.confidence === 'string' ? comment.confidence : null,
            rationale: typeof comment.rationale === 'string' ? comment.rationale : null,
        };
    });
    if (lineComments.some((comment) => comment === null)) return null;
    return {
        status: result.status as SidecarDraftReviewResult['status'],
        message: result.message,
        id: typeof result.id === 'string' ? result.id : null,
        commitId: typeof result.commitId === 'string' ? result.commitId : null,
        review: {
            summary: review.summary,
            verdict: review.verdict,
            lineComments: lineComments as SidecarLineComment[],
            importedFromGitHub: review.importedFromGitHub,
        },
    };
}

/** Validates the token-free result shape returned by `prs/saveDraftReview`, `prs/submitReview`,
 * and `prs/deleteDraftReview` — all three share the same result shape. */
export function parseDraftReviewMutationResult(value: unknown): SidecarDraftReviewMutationResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !DRAFT_REVIEW_MUTATION_STATUSES.has(result.status as SidecarDraftReviewMutationResult['status'])
        || typeof result.message !== 'string'
        || (result.reviewId !== null && typeof result.reviewId !== 'string')
        || typeof result.commentsDropped !== 'boolean') {
        return null;
    }
    return {
        status: result.status as SidecarDraftReviewMutationResult['status'],
        message: result.message,
        reviewId: typeof result.reviewId === 'string' ? result.reviewId : null,
        commentsDropped: result.commentsDropped,
    };
}

/** Encodes a JSON-RPC payload with the same bounded Content-Length framing the sidecar's
 * StdioFrameCodec (Java) reads/writes. Kept as a pure function so framing can be unit tested
 * without spawning a real process. */
export function encodeFrame(payload: string): Buffer {
    const body = Buffer.from(payload, 'utf8');
    if (body.length > 8 * 1024 * 1024) throw new Error('JSON-RPC payload exceeds the maximum size');
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
            throw new Error('Sidecar sent an invalid Content-Length header');
        }
        const length = parseInt(match[1], 10);
        if (length > 8 * 1024 * 1024) throw new Error('Sidecar response exceeds the maximum size');
        const bodyStart = headerEnd + HEADER_TERMINATOR.length;
        if (remaining.length < bodyStart + length) return remaining;
        onFrame(remaining.subarray(bodyStart, bodyStart + length).toString('utf8'));
        remaining = remaining.subarray(bodyStart + length);
    }
}

/** Required transport to the shared Java GitHub engine used by the VS Code host. */
export class SidecarClient {
    private child: ChildProcessWithoutNullStreams | null = null;
    private startupFailure: Error | null = null;
    private readyPromise: Promise<void> | null = null;
    private disposed = false;
    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private buffer: Buffer = Buffer.alloc(0);
    private stderr = '';

    constructor(
        private readonly jarPath: string | null,
        private readonly javaBinary = 'java',
        private readonly spawnSidecar: SidecarSpawn = spawn,
        private readonly requestTimeoutMs = REQUEST_TIMEOUT_MS,
    ) {}

    async initialize(): Promise<void> {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        if (this.startupFailure) throw this.startupFailure;
        if (this.readyPromise) return this.readyPromise;
        this.ensureStarted();
        this.readyPromise = this.requestRaw('initialize', {}).then((value) => {
            const result = parseInitializeResult(value);
            if (!result) throw new Error('PR Pilot Java sidecar returned an invalid initialization response.');
            if (result.protocolVersion !== SIDECAR_PROTOCOL_VERSION) {
                throw new Error(
                    `PR Pilot Java sidecar protocol mismatch (expected ${SIDECAR_PROTOCOL_VERSION}, got ${result.protocolVersion}). Reinstall the extension.`,
                );
            }
            const missing = REQUIRED_CAPABILITIES.filter((capability) => result.capabilities[capability] !== true);
            if (missing.length > 0) {
                throw new Error(`PR Pilot Java sidecar is missing required capabilities: ${missing.join(', ')}. Reinstall the extension.`);
            }
        }).catch((err: unknown) => {
            const failure = err instanceof Error ? err : new Error(String(err));
            this.startupFailure = failure;
            this.stopChild();
            throw failure;
        });
        return this.readyPromise;
    }

    private ensureStarted(): void {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        if (this.startupFailure) throw this.startupFailure;
        if (this.child) return;
        if (!this.jarPath) {
            throw new Error(
                'PR Pilot Java sidecar is missing. Reinstall the extension or run ./gradlew :sidecar:bootJar for local development.',
            );
        }
        if (!fs.existsSync(this.jarPath)) {
            throw new Error(`PR Pilot Java sidecar was not found at ${this.jarPath}. Reinstall the extension.`);
        }
        try {
            const child = this.spawnSidecar(
                this.javaBinary,
                ['-jar', this.jarPath],
                { stdio: ['pipe', 'pipe', 'pipe'] },
            );
            child.on('error', (err: NodeJS.ErrnoException) => {
                if (this.disposed) return;
                const failure = err.code === 'ENOENT'
                    ? new Error('Java was not found. Install Java 17 or newer and ensure java is on PATH.')
                    : new Error(`PR Pilot Java sidecar failed to start: ${err.message}`);
                this.startupFailure ??= failure;
                if (this.child === child) this.child = null;
                this.failAllPending(this.startupFailure);
            });
            child.on('exit', (code, signal) => {
                if (this.child === child) this.child = null;
                if (this.disposed) return;
                this.startupFailure ??= this.processExitError(code, signal);
                this.failAllPending(this.startupFailure);
            });
            child.stdout.on('data', (chunk: Buffer) => this.onData(chunk));
            child.stderr.on('data', (chunk: Buffer) => {
                this.stderr = (this.stderr + chunk.toString('utf8')).slice(-4_096);
            });
            this.child = child;
        } catch (err) {
            const failure = err instanceof Error
                ? new Error(`PR Pilot Java sidecar failed to start: ${err.message}`)
                : new Error('PR Pilot Java sidecar failed to start.');
            this.startupFailure = failure;
            throw failure;
        }
    }

    private onData(chunk: Buffer): void {
        try {
            this.buffer = extractFrames(
                Buffer.concat([this.buffer, chunk]),
                (body) => this.handleMessage(body),
            );
        } catch (err) {
            const failure = err instanceof Error ? err : new Error(String(err));
            this.startupFailure = failure;
            this.failAllPending(failure);
            this.stopChild();
        }
    }

    private handleMessage(body: string): void {
        let message: SidecarRpcResponse;
        try {
            message = JSON.parse(body) as SidecarRpcResponse;
        } catch {
            const failure = new Error('PR Pilot Java sidecar sent malformed JSON.');
            this.startupFailure = failure;
            this.failAllPending(failure);
            this.stopChild();
            return;
        }
        if (typeof message.id !== 'number') return;
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        if (message.jsonrpc !== '2.0' || (message.error === undefined) === (message.result === undefined)) {
            pending.reject(new Error('PR Pilot Java sidecar returned a malformed JSON-RPC response.'));
        } else if (message.error) {
            pending.reject(new Error(message.error.message ?? 'Sidecar error'));
        } else {
            pending.resolve(message.result);
        }
    }

    private failAllPending(err: Error): void {
        for (const pending of this.pending.values()) pending.reject(err);
        this.pending.clear();
    }

    private async request(method: string, params: unknown): Promise<unknown> {
        await this.initialize();
        return this.requestRaw(method, params);
    }

    private requestRaw(method: string, params: unknown): Promise<unknown> {
        const child = this.child;
        if (!child) return Promise.reject(this.startupFailure ?? new Error('PR Pilot Java sidecar is not running.'));
        const id = this.nextId++;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                const failure = new Error(
                    `PR Pilot Java sidecar request "${method}" timed out. Reload VS Code to restart PR Pilot.`,
                );
                this.startupFailure = failure;
                this.failAllPending(failure);
                this.stopChild();
            }, this.requestTimeoutMs);
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

    /** Returns null only when the shared detector reports that no repository was found. */
    async detectRepo(path: string): Promise<string | null> {
        const result = (await this.request('repo/detect', { path })) as
            | { status?: string; repository?: { owner?: string; repo?: string } | null }
            | undefined;
        if (typeof result?.status !== 'string') throw this.invalidResponse('repository detection');
        if (result.status !== 'found') return null;
        const owner = result.repository?.owner;
        const repo = result.repository?.repo;
        if (!owner || !repo) throw this.invalidResponse('repository detection');
        return `${owner}/${repo}`;
    }

    /** Verifies GitHub CLI credentials without returning the token to the extension. */
    async checkGitHubAuth(githubBaseUrl: string): Promise<SidecarGitHubAuthResult> {
        return this.parseResult(
            'GitHub authentication',
            parseGitHubAuthResult,
            await this.request('github/checkAuth', { githubBaseUrl }),
        );
    }

    /** Lists pull requests without returning a GitHub token to the extension. */
    async listPullRequests(
        githubBaseUrl: string,
        state: string,
        searchScope: string,
        currentRepo?: string,
    ): Promise<SidecarPrListResult> {
        return this.parseResult('PR list', parsePrListResult, await this.request('prs/list', {
            githubBaseUrl,
            state,
            searchScope,
            ...(currentRepo ? { currentRepo } : {}),
        }));
    }

    async searchPullRequests(
        githubBaseUrl: string,
        query: string,
        limit: number,
    ): Promise<SidecarPrSearchResult> {
        return this.parseResult('PR search', parsePrSearchResult,
            await this.request('prs/search', { githubBaseUrl, query, limit }));
    }

    async listStarredRepositories(githubBaseUrl: string): Promise<SidecarStarredReposResult> {
        return this.parseResult('starred repositories', parseStarredReposResult,
            await this.request('repos/listStarred', { githubBaseUrl }));
    }

    /** Retrieves PR metadata without returning a GitHub token to the extension. */
    async getPullRequestDetail(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarPrDetailResult> {
        return this.parseResult('PR detail', parsePrDetailResult,
            await this.request('prs/getDetail', { githubBaseUrl, owner, repo, number }));
    }

    async getPullRequestDiff(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        mode: 'review' | 'validation' = 'review',
    ): Promise<SidecarPrDiffResult> {
        return this.parseResult('PR diff', parsePrDiffResult,
            await this.request('prs/getDiff', { githubBaseUrl, owner, repo, number, mode }));
    }

    async getExistingReviews(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarExistingReviewsResult> {
        return this.parseResult('existing reviews', parseExistingReviewsResult,
            await this.request('prs/getExistingReviews', { githubBaseUrl, owner, repo, number }));
    }

    /** Loads a pending review; `none` is a valid domain outcome. */
    async getDraftReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarDraftReviewResult> {
        return this.parseResult('draft review', parseDraftReviewResult,
            await this.request('prs/getDraftReview', { githubBaseUrl, owner, repo, number }));
    }

    /** Saves a pending review without returning a GitHub token to the extension. */
    async saveDraftReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        summary: string,
        verdict: string,
        lineComments: SidecarCommentInput[],
        orphans: SidecarCommentInput[],
    ): Promise<SidecarDraftReviewMutationResult> {
        return this.parseResult('save draft review', parseDraftReviewMutationResult,
            await this.request('prs/saveDraftReview', {
                githubBaseUrl, owner, repo, number, summary, verdict, lineComments, orphans,
            }));
    }

    /** Submits a pending review without returning a GitHub token to the extension. */
    async submitReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        reviewId: string,
        event: string,
        body: string,
    ): Promise<SidecarDraftReviewMutationResult> {
        return this.parseResult('submit review', parseDraftReviewMutationResult,
            await this.request('prs/submitReview', {
                githubBaseUrl, owner, repo, number, reviewId, event, body,
            }));
    }

    /** Deletes a pending review without returning a GitHub token to the extension. */
    async deleteDraftReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        reviewId: string,
    ): Promise<SidecarDraftReviewMutationResult> {
        return this.parseResult('delete draft review', parseDraftReviewMutationResult,
            await this.request('prs/deleteDraftReview', {
                githubBaseUrl, owner, repo, number, reviewId,
            }));
    }

    private parseResult<T>(description: string, parser: (value: unknown) => T | null, value: unknown): T {
        const result = parser(value);
        if (!result) throw this.invalidResponse(description);
        return result;
    }

    private invalidResponse(description: string): Error {
        return new Error(`PR Pilot Java sidecar returned an invalid ${description} response.`);
    }

    private processExitError(code: number | null, signal: NodeJS.Signals | null): Error {
        const diagnostics = this.stderr.trim();
        if (/UnsupportedClassVersionError|class file version/i.test(diagnostics)) {
            return new Error('PR Pilot requires Java 17 or newer. Update Java and reload VS Code.');
        }
        if (/Unable to access jarfile|Invalid or corrupt jarfile/i.test(diagnostics)) {
            return new Error('PR Pilot Java sidecar could not be opened. Reinstall the extension.');
        }
        const reason = signal ? `signal ${signal}` : `exit code ${code ?? 'unknown'}`;
        const diagnosticLines = diagnostics.split(/\r?\n/);
        const detail = diagnostics ? ` ${diagnosticLines[diagnosticLines.length - 1]}` : '';
        return new Error(`PR Pilot Java sidecar exited unexpectedly (${reason}).${detail}`);
    }

    private stopChild(): void {
        const child = this.child;
        this.child = null;
        if (child) {
            child.stdin.end();
            child.kill();
        }
    }

    dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.stopChild();
        this.failAllPending(new Error('PR Pilot Java sidecar has been disposed.'));
    }
}

/**
 * Resolves the sidecar jar to spawn: the packaged copy staged alongside the extension by
 * `scripts/stage-sidecar.mjs` (release/`.vsix` builds), falling back to the sibling Gradle
 * module's `build/libs` bootJar output for local development. Returns `null` when no jar can be
 * found so the client can surface an actionable installation error on first use.
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
