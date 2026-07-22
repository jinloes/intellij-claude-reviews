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






