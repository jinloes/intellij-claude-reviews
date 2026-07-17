import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as github from './github';
import * as claude from './claude';
import * as copilot from './copilot';
import * as worktree from './worktree';
import * as settings from './settings';
import * as workspace from './workspace';
import { hasStaleCommits } from './draftState';
import { mergeBySource, notificationMessage, prNotificationKey } from './notifications';
import { BRIDGE_PROTOCOL_VERSION, isValidBridgeRequest } from './bridgeValidation';
import { classifySetupAuthError } from './authError';
import { toUserFacingError, providerNotInstalledMessage } from './userFacingError';
import { resolveWebviewDistPath } from './webviewAssets';
import { buildErrorHtml, buildLauncherHtml, buildMainWebviewHtml } from './webviewHtml';
import { classifyHostTheme, type HostTheme } from './hostTheme';

type Provider = 'claude' | 'copilot';

function provider(): Provider {
    const value = config().get<string>('reviewProvider', 'claude');
    return value === 'copilot' ? 'copilot' : 'claude';
}

function cancelActiveProvider(): void {
    // Cancel both — only one has an active process at any time, but reading the provider
    // setting here can race with a config change so we just send the signal to both.
    claude.cancelCurrentRequest();
    copilot.cancelCurrentRequest();
}

export function activate(context: vscode.ExtensionContext) {
    const provider = new ClaudeReviewsViewProvider(context.extensionUri);
    const notificationPoller = new PRNotificationPoller(context, (pr) => provider.openPullRequest(pr));
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider('pr-pilot.main', provider, {
            webviewOptions: { retainContextWhenHidden: true },
        }),
        vscode.commands.registerCommand('pr-pilot.open', () => provider.openPanel()),
        vscode.commands.registerCommand('pr-pilot.selectCopilotModel', selectCopilotModel),
        vscode.commands.registerCommand('pr-pilot.openSettings', () => settings.openSettings(context)),
        notificationPoller,
    );
    notificationPoller.syncFromSettings();
    context.subscriptions.push(vscode.workspace.onDidChangeConfiguration((event) => {
        // A change to the notification scope (which PRs match) must re-seed silently so existing
        // PRs are not announced retroactively; an interval-only change keeps the current seed.
        if (event.affectsConfiguration('pr-pilot.notificationsEnabled')
            || event.affectsConfiguration('pr-pilot.notifyReviewRequested')
            || event.affectsConfiguration('pr-pilot.notifyStarredRepos')
            || event.affectsConfiguration('pr-pilot.githubBaseUrl')) {
            notificationPoller.resetAndSync();
        } else if (event.affectsConfiguration('pr-pilot.notificationPollMinutes')) {
            notificationPoller.syncFromSettings();
        }
    }));
}

/** Kept for backwards compatibility; model selection now lives in PR Pilot Settings. */
async function selectCopilotModel(): Promise<void> {
    await vscode.commands.executeCommand('pr-pilot.openSettings');
}

export function deactivate() {}

// ── Background PR notifications ───────────────────────────────────────────────

/** globalState key for the persisted seen-PR set so notifications survive reloads/restarts. */
const NOTIFY_STATE_KEY = 'pr-pilot.notifications.seenState';
const MAX_SEEN_NOTIFICATION_PRS = 500;

interface SeenState {
    seeded: boolean;
    seen: string[];
}

class PRNotificationPoller implements vscode.Disposable {
    private timer: NodeJS.Timeout | null = null;
    private seeded: boolean;
    private readonly seen: Set<string>;
    private running = false;

    constructor(
        private readonly context: vscode.ExtensionContext,
        private readonly onOpenPr: (pr: github.PR) => void,
    ) {
        // Restore prior seen state so a reload/restart does not silently re-seed and swallow PRs
        // that appeared while the window was closed.
        const saved = context.globalState.get<SeenState>(NOTIFY_STATE_KEY);
        this.seeded = saved?.seeded ?? false;
        this.seen = new Set(saved?.seen ?? []);
    }

    /** Starts/restarts the timer for the current interval, preserving the existing seed. */
    syncFromSettings(): void {
        this.stop();
        if (!config().get<boolean>('notificationsEnabled', false)) return;
        const minutes = Math.max(1, config().get<number>('notificationPollMinutes', 5));
        void this.poll();
        this.timer = setInterval(() => void this.poll(), minutes * 60_000);
    }

    /** Clears the seed and restarts so a scope/host change re-seeds silently instead of flooding. */
    resetAndSync(): void {
        this.seeded = false;
        this.seen.clear();
        void this.persist();
        this.syncFromSettings();
    }

    dispose(): void {
        this.stop();
    }

    private stop(): void {
        if (this.timer) clearInterval(this.timer);
        this.timer = null;
    }

    private persist(): Thenable<void> {
        return this.context.globalState.update(NOTIFY_STATE_KEY, {
            seeded: this.seeded,
            seen: [...this.seen],
        } satisfies SeenState);
    }

    private async poll(): Promise<void> {
        if (this.running) return;
        this.running = true;
        try {
            const token = await github.resolveToken(githubBaseUrl());
            const reviewRequested: github.PR[] = [];
            const starred: github.PR[] = [];

            if (config().get<boolean>('notifyReviewRequested', true)) {
                reviewRequested.push(...await github.searchPRsByQuery(
                    token,
                    githubBaseUrl(),
                    'is:open is:pr draft:false review-requested:@me',
                ));
            }

            if (config().get<boolean>('notifyStarredRepos', false)) {
                const starredRepos = (await github.getStarredRepos(token, githubBaseUrl())).slice(0, 25);
                if (starredRepos.length > 0) {
                    const repoQ = starredRepos.map((repo) => `repo:${repo}`).join(' ');
                    starred.push(...await github.searchPRsByQuery(
                        token,
                        githubBaseUrl(),
                        `is:open is:pr draft:false ${repoQ}`,
                    ));
                }
            }

            const merged = mergeBySource(reviewRequested, starred);
            if (!this.seeded) {
                for (const { pr } of merged) this.seen.add(prNotificationKey(pr));
                this.seeded = true;
                trimSeenSet(this.seen, MAX_SEEN_NOTIFICATION_PRS);
                await this.persist();
                return;
            }

            for (const { pr, source } of merged) {
                const key = prNotificationKey(pr);
                if (this.seen.has(key)) continue;
                this.seen.add(key);
                void vscode.window.showInformationMessage(
                    notificationMessage(pr, source),
                    'Open in PR Pilot',
                ).then((choice) => {
                    if (choice === 'Open in PR Pilot') this.onOpenPr(pr);
                });
            }
            trimSeenSet(this.seen, MAX_SEEN_NOTIFICATION_PRS);
            await this.persist();
        } catch (err) {
            console.warn('[pr-pilot] PR notification poll failed:', err instanceof Error ? err.message : String(err));
        } finally {
            this.running = false;
        }
    }
}

function trimSeenSet(seen: Set<string>, maxSize: number): void {
    if (seen.size <= maxSize) return;
    while (seen.size > maxSize) {
        const first = seen.values().next().value;
        if (!first) break;
        seen.delete(first);
    }
}

// ── State per webview view ─────────────────────────────────────────────────────

interface ActivePR {
    number: number;
    owner: string;
    repo: string;
    title: string;
    body: string;
}

/**
 * Provides the PR Pilot editor tab plus a small Activity Bar launcher.
 * Serves the pre-built webview/dist/ React app and bridges all messages in the editor tab.
 */
class ClaudeReviewsViewProvider implements vscode.WebviewViewProvider {
    private readonly distUri: vscode.Uri;
    private panel: vscode.WebviewPanel | undefined;
    private state: ViewState | undefined;
    private pendingActivation: { pr: github.PR; source: 'notification' } | null = null;

    constructor(extensionUri: vscode.Uri) {
        this.distUri = vscode.Uri.file(resolveWebviewDistPath(extensionUri.fsPath, fs.existsSync));
    }

    openPanel(): void {
        if (this.panel) {
            this.panel.reveal(vscode.ViewColumn.Active);
            return;
        }

        const panel = vscode.window.createWebviewPanel(
            'pr-pilot.main',
            'PR Pilot',
            vscode.ViewColumn.Active,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [this.distUri],
            },
        );
        this.panel = panel;
        this.initializeWebview(panel.webview, panel.onDidDispose, () => {
            if (this.state?.webview === panel.webview) this.state = undefined;
            if (this.panel === panel) this.panel = undefined;
        });
    }

    openPullRequest(pr: github.PR): void {
        this.pendingActivation = { pr, source: 'notification' };
        const hadLiveState = Boolean(this.state);
        this.openPanel();
        if (hadLiveState) {
            this.flushPendingActivation();
        }
    }

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ): void {
        webviewView.webview.options = {
            enableScripts: true,
        };
        webviewView.webview.html = buildLauncherHtml(webviewView.webview.cspSource);
        webviewView.webview.onDidReceiveMessage((message: unknown) => {
            if (
                typeof message === 'object'
                && message !== null
                && (message as { type?: unknown }).type === 'open'
            ) {
                this.openPanel();
                void vscode.commands.executeCommand('workbench.action.closeSidebar');
            }
        });
    }

    private initializeWebview(
        webview: vscode.Webview,
        onDidDispose: vscode.Event<void>,
        onDispose?: () => void,
    ): void {
        webview.html = this.getHtmlContent(webview);

        // Per-view state — each panel gets its own instance of these fields
        const state: ViewState = {
            webview,
            cachedToken: null,
            prStateFilter: 'open',
            searchScope: 'currentRepo',
            activePR: null,
            activeDiff: '',
            activeValidationDiff: '',
            activeReviewResult: null,
            pendingReviewId: null,
            pendingReviewKey: null,
            selectionRevision: 0,
            mutationQueue: Promise.resolve(),
            chatHistory: new Map(),
            worktreeDir: null,
            gitRoot: null,
            worktreeKey: null,
        };
        this.state = state;

        this.setupMessageBridge(state);
        const themeSubscription = vscode.window.onDidChangeActiveColorTheme((theme) => {
            push(state, { type: 'themeChanged', theme: mapHostThemeKind(theme.kind) });
        });
        push(state, { type: 'themeChanged', theme: mapHostThemeKind(vscode.window.activeColorTheme.kind) });

        // Tear down any PR-branch worktree when the view is disposed so we don't leak
        // temp directories or detached worktrees registered against the user's repo.
        onDidDispose(() => {
            themeSubscription.dispose();
            clearWorktree(state);
            onDispose?.();
        });

        // Trigger initial PR load so the user sees results (or setup guidance) immediately
        // rather than an indefinite loading spinner.
        handleRefreshPRs(state, {})
            .catch(console.error)
            .finally(() => {
                push(state, { type: 'themeChanged', theme: mapHostThemeKind(vscode.window.activeColorTheme.kind) });
                this.flushPendingActivation();
            });
    }

    private flushPendingActivation(): void {
        if (!this.state || !this.pendingActivation) return;
        const activation = this.pendingActivation;
        this.pendingActivation = null;
        push(this.state, {
            type: 'activatePR',
            pr: activation.pr,
            source: activation.source,
        });
    }

    private getHtmlContent(webview: vscode.Webview): string {
        const indexPath = path.join(this.distUri.fsPath, 'index.html');
        if (!fs.existsSync(indexPath)) {
            return buildErrorHtml(
                'webview/dist/index.html not found. Run "npm run build" inside webview/.',
            );
        }
        const html = fs.readFileSync(indexPath, 'utf8');
        return buildMainWebviewHtml(
            html,
            webview.cspSource,
            (assetPath) => webview.asWebviewUri(vscode.Uri.joinPath(this.distUri, assetPath)).toString(),
        );
    }

    private setupMessageBridge(state: ViewState): void {
        state.webview.onDidReceiveMessage(async (msg: { type?: string } & Record<string, unknown>) => {
            if (!isValidBridgeRequest(msg)) {
                console.warn('[pr-pilot] invalid bridge payload:', msg);
                return;
            }
            switch (msg.type) {
                case 'refreshPRs':
                    await handleRefreshPRs(state, msg);
                    break;
                case 'selectPR':
                    await handleSelectPR(state, msg);
                    break;
                case 'generateReview':
                    await handleGenerateReview(state, msg);
                    break;
                case 'cancelReview':
                    cancelActiveProvider();
                    break;
                case 'saveDraft':
                    await enqueueMutation(state, () => handleSaveDraft(state, msg));
                    break;
                case 'submitReview':
                    await enqueueMutation(state, () => handleSubmitReview(state, msg));
                    break;
                case 'deleteDraft':
                    await enqueueMutation(state, () => handleDeleteDraft(state, msg));
                    break;
                case 'askClaude':
                    await handleAskClaude(state, msg);
                    break;
                case 'clearChat': {
                    const key = prKey(state.activePR);
                    if (key) state.chatHistory.delete(key);
                    break;
                }
                case 'openUrl':
                    if (typeof msg.url === 'string' && msg.url.startsWith('https://')) {
                        void vscode.env.openExternal(vscode.Uri.parse(msg.url));
                    }
                    break;
                case 'openSettings':
                    await vscode.commands.executeCommand('pr-pilot.openSettings');
                    break;
                case 'runAuthLogin':
                    runAuthLoginInTerminal();
                    break;
                case 'webviewLayoutChanged':
                    break;
                default:
                    console.warn('[pr-pilot] unknown message type:', msg.type);
            }
        });
    }
}

export function mapHostThemeKind(kind: vscode.ColorThemeKind): HostTheme {
    const highContrast = kind === vscode.ColorThemeKind.HighContrast
        || kind === vscode.ColorThemeKind.HighContrastLight;
    const dark = kind !== vscode.ColorThemeKind.Light
        && kind !== vscode.ColorThemeKind.HighContrastLight;
    return classifyHostTheme(dark, highContrast);
}

function runAuthLoginInTerminal(): void {
    const terminal = vscode.window.createTerminal({ name: 'PR Pilot Setup' });
    terminal.show(true);
    terminal.sendText('gh auth login', true);
}

// ── Per-view state ─────────────────────────────────────────────────────────────

interface ViewState {
    webview: vscode.Webview;
    cachedToken: string | null;
    prStateFilter: string;
    searchScope: github.PRSearchScope;
    activePR: ActivePR | null;
    activeDiff: string;
    activeValidationDiff: string;
    activeReviewResult: github.ReviewResult | null;
    pendingReviewId: string | null;
    pendingReviewKey: string | null;
    selectionRevision: number;
    mutationQueue: Promise<void>;
    chatHistory: Map<string, claude.ChatMessage[]>;
    // PR-branch worktree, lazily created on first review/chat for the active PR and reused until
    // the PR changes or the view is disposed. Mirrors WebviewPanel.java's activePr* fields.
    worktreeDir: string | null;
    gitRoot: string | null;
    worktreeKey: string | null;
}

function providerReadiness(): { provider: Provider; available: boolean; detail: string } {
    const current = provider();
    const available = current === 'copilot' ? copilot.copilotBinaryAvailable() : claude.claudeBinaryAvailable();
    return {
        provider: current,
        available,
        detail: available
            ? 'Ready to generate reviews with the configured CLI.'
            : providerNotInstalledMessage(current),
    };
}

function prKey(pr: ActivePR | null): string | null {
    return pr ? `${pr.owner}/${pr.repo}#${pr.number}` : null;
}

function prKeyFromParts(number: number, owner: string, repo: string): string {
    return `${owner}/${repo}#${number}`;
}

function worktreeKey(pr: ActivePR): string {
    return `${pr.owner.toLowerCase()}/${pr.repo.toLowerCase()}#${pr.number}`;
}

function isSameActivePR(state: ViewState, pr: ActivePR): boolean {
    const active = state.activePR;
    return !!active
        && active.number === pr.number
        && active.owner.toLowerCase() === pr.owner.toLowerCase()
        && active.repo.toLowerCase() === pr.repo.toLowerCase();
}

/** Removes the active PR worktree (if any) and clears the cached fields. Non-blocking cleanup. */
function clearWorktree(state: ViewState): void {
    const wt = state.worktreeDir;
    const root = state.gitRoot;
    state.worktreeDir = null;
    state.gitRoot = null;
    state.worktreeKey = null;
    if (wt && root) {
        void worktree.removeWorktree(root, wt).catch(() => undefined);
    }
}

/**
 * Resolves the working directory for a review/chat against `pr`. Creates a detached git worktree
 * checked out to the PR's head branch so the CLI can read PR-branch source for type lookups and
 * cross-file references, then caches it for reuse. Falls back to the open workspace folder when a
 * worktree can't be created (no git root, different repo, fork fetch fails, etc.).
 *
 * Mirrors WebviewPanel.resolvePrClaudeService. Only builds a worktree when the open workspace is
 * the same repo as the PR — the worktree shares that repo's local object store.
 */
async function resolveWorkingDir(
    state: ViewState,
    pr: ActivePR,
    token: string,
    emitStatus: boolean,
    bridgePrKey?: string,
): Promise<string> {
    const fallback = workingDir();
    const key = worktreeKey(pr);
    if (state.worktreeDir && state.worktreeKey === key) return state.worktreeDir;
    if (!token || !fallback) return fallback;

    const gitRoot = worktree.findGitRoot(fallback);
    const currentRepo = github.detectCurrentRepo(fallback);
    const sameRepo = currentRepo !== null
        && currentRepo.toLowerCase() === `${pr.owner}/${pr.repo}`.toLowerCase();
    if (!gitRoot || !sameRepo) return fallback;

    if (emitStatus) {
        push(state, { type: 'reviewGenerating', prKey: bridgePrKey, message: 'Preparing PR branch…' });
    }

    try {
        const headInfo = await github.getPRHeadInfo(token, githubBaseUrl(), pr.owner, pr.repo, pr.number);
        if (!headInfo.ref.trim()) return fallback;

        const wt = worktree.worktreePath(pr.number);
        if (headInfo.isFork) {
            await worktree.createWorktreeFromFork(gitRoot, headInfo.forkCloneUrl, headInfo.ref, wt);
        } else {
            await worktree.createWorktree(gitRoot, headInfo.ref, wt);
        }

        // The PR may have changed while we awaited git; discard the worktree if so.
        if (!isSameActivePR(state, pr)) {
            void worktree.removeWorktree(gitRoot, wt).catch(() => undefined);
            return fallback;
        }

        state.worktreeDir = wt;
        state.gitRoot = gitRoot;
        state.worktreeKey = key;
        return wt;
    } catch (err) {
        console.warn(`[pr-pilot] Worktree creation for PR #${pr.number} failed; using workspace dir:`,
            err instanceof Error ? err.message : String(err));
        return fallback;
    }
}

function push(state: ViewState, msg: object): void {
    state.webview.postMessage({ protocolVersion: BRIDGE_PROTOCOL_VERSION, ...msg });
}

function enqueueMutation(state: ViewState, action: () => Promise<void>): Promise<void> {
    const queued = state.mutationQueue.then(action, action);
    state.mutationQueue = queued.catch(() => undefined);
    return queued;
}

function config(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('pr-pilot');
}

function githubBaseUrl(): string {
    return config().get<string>('githubBaseUrl', 'https://github.com');
}

function reviewModel(): string {
    const key = provider() === 'copilot' ? 'reviewModelCopilot' : 'reviewModel';
    return config().get<string>(key, '');
}

function reviewEffort(): string {
    const value = config().get<string>('reviewEffort', 'medium');
    return value && value.trim().length > 0 ? value : 'medium';
}

function copilotInheritMcp(): boolean {
    return config().get<boolean>('copilotInheritMcp', false);
}

function copilotConfigDir(): string {
    return config().get<string>('copilotConfigDir', '').trim();
}

function reviewFocusAreas(): string {
    return config().get<string>('reviewFocusAreas', '').trim();
}

function reviewCustomInstructions(): string {
    return config().get<string>('reviewCustomInstructions', '').trim();
}

/** Candidate contributor-doc files, in priority order, scanned for repo review guidelines. */
const GUIDELINE_FILES = [
    'AGENTS.md',
    'CONTRIBUTING.md',
    '.github/CONTRIBUTING.md',
    'docs/CONTRIBUTING.md',
    '.github/pull_request_template.md',
];

/** Total cap on guideline bytes fed to the prompt so a large doc can't blow up the context. */
const MAX_GUIDELINES_BYTES = 6_000;

/**
 * Reads repo contributor docs from the review working directory (the PR-branch worktree or the
 * open workspace) and concatenates them, capped at {@link MAX_GUIDELINES_BYTES}, so the model can
 * weight findings against the project's own review conventions. Returns '' when none are found.
 */
function readRepoGuidelines(dir: string): string {
    if (!dir) return '';
    const parts: string[] = [];
    let total = 0;
    for (const rel of GUIDELINE_FILES) {
        if (total >= MAX_GUIDELINES_BYTES) break;
        try {
            const full = path.join(dir, rel);
            if (!fs.statSync(full).isFile()) continue;
            let content = fs.readFileSync(full, 'utf8').trim();
            if (!content) continue;
            const remaining = MAX_GUIDELINES_BYTES - total;
            if (content.length > remaining) content = `${content.substring(0, remaining)}\n…(truncated)`;
            parts.push(`## ${rel}\n${content}`);
            total += content.length;
        } catch { /* unreadable or missing — skip */ }
    }
    return parts.join('\n\n');
}

/** Formats a prior generated review as compact context for a re-generation prompt. */
function formatPriorReview(result: github.ReviewResult | null): string {
    if (!result) return '';
    const lines = [`Verdict: ${result.verdict}`];
    if (result.summary) lines.push(`Summary: ${result.summary}`);
    for (const c of result.lineComments) {
        lines.push(`- ${c.file}:${c.line} [${c.type}] ${c.body}`);
    }
    return lines.join('\n');
}

function workingDir(): string {
    return workspace.resolveWorkspaceDir(
        vscode.workspace.workspaceFolders?.map(folder => folder.uri.fsPath) ?? [],
    );
}

async function getToken(state: ViewState): Promise<string> {
    if (state.cachedToken) return state.cachedToken;
    const token = await github.resolveToken(githubBaseUrl());
    state.cachedToken = token;
    return token;
}

// ── Message handlers ───────────────────────────────────────────────────────────

async function handleRefreshPRs(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    try {
        const token = await getToken(state);
        if (typeof msg.state === 'string') state.prStateFilter = msg.state;
        if (typeof msg.searchScope === 'string') {
            state.searchScope = normalizeSearchScope(msg.searchScope);
        } else if (typeof msg.assignedToMe === 'boolean' && msg.assignedToMe) {
            state.searchScope = 'assigned';
        } else if (typeof msg.reviewRequested === 'boolean' && msg.reviewRequested) {
            state.searchScope = 'reviewRequested';
        }

        const currentRepo = github.detectCurrentRepo(workingDir() || process.cwd());
        const found = await github.searchPRs(
            token,
            githubBaseUrl(),
            state.prStateFilter,
            state.searchScope,
            currentRepo ?? undefined,
        );
        // searchPRs over-fetches by one to distinguish "exactly the limit" from "more exist".
        const limited = found.length > github.PR_SEARCH_LIMIT;
        const prs = (limited ? found.slice(0, github.PR_SEARCH_LIMIT) : found).map((pr) => {
            if (!state.activePR || !state.pendingReviewId) return pr;
            return pr.number === state.activePR.number && pr.owner === state.activePR.owner && pr.repo === state.activePR.repo
                ? { ...pr, hasReviewDraft: true }
                : pr;
        });
        prs.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        push(state, {
            type: 'prListLoaded',
            prs,
            defaultRepo: currentRepo ?? undefined,
            listStatus: {
                searchScope: state.searchScope,
                currentRepo: currentRepo ?? undefined,
                resultLimit: github.PR_SEARCH_LIMIT,
                limited,
            },
        });
    } catch (err) {
        state.cachedToken = null;
        const reason = classifySetupAuthError(err);
        const detail = reason === 'gh_not_installed'
            ? "The 'gh' CLI was not found. Install it from https://cli.github.com, then run 'gh auth login' in a terminal and click Refresh."
            : reason === 'gh_not_authenticated'
                ? "Run 'gh auth login' in a terminal to authenticate, then click Refresh."
                : toUserFacingError(err, 'load pull requests');
        push(state, { type: 'setupRequired', reason, detail });
    }
}

function normalizeSearchScope(value: string): github.PRSearchScope {
    if (value === 'authored' || value === 'assigned' || value === 'reviewRequested') return value;
    return 'currentRepo';
}

async function handleSelectPR(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = prKeyFromParts(number, owner, repo);
    const selectionRevision = ++state.selectionRevision;
    const title = typeof msg.title === 'string' ? msg.title : '';
    const body = typeof msg.body === 'string' ? msg.body : '';

    clearWorktree(state);
    state.activePR = { number, owner, repo, title, body };
    state.activeDiff = '';
    state.activeValidationDiff = '';
    state.activeReviewResult = null;
    state.pendingReviewId = null;
    state.pendingReviewKey = null;
    push(state, { type: 'draftLoading', prKey: key });
    try {
        const token = await getToken(state);
        const base = githubBaseUrl();
        const readiness = providerReadiness();

        const [diff, validationDiffRaw, detail, draft] = await Promise.all([
            github.getPRDiff(token, base, owner, repo, number),
            github.getPRDiffFull(token, base, owner, repo, number).catch(() => ''),
            github.getPRDetail(token, base, owner, repo, number),
            github.loadDraftReview(token, base, owner, repo, number),
        ]);
        const validationDiff = validationDiffRaw || diff;

        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;

        state.activePR = {
            number,
            owner,
            repo,
            title: detail.title ?? title,
            body: detail.body ?? body,
        };
        state.activeDiff = diff;
        state.activeValidationDiff = validationDiff;
        state.activeReviewResult = draft?.result ?? null;
        state.pendingReviewId = draft?.id ?? null;
        state.pendingReviewKey = draft ? key : null;

        if (detail.merged) {
            push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
            push(state, { type: 'draftLoaded', prKey: key, prState: 'MERGED', diff, validationDiff, providerReadiness: readiness });
        } else if (draft) {
            const staleCommits = hasStaleCommits(draft.commitId, detail.head?.sha ?? '');
            push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: true });
            push(state, {
                type: 'draftLoaded',
                prKey: key,
                prState: 'DRAFT_PRESENT',
                reviewId: draft.id,
                result: draft.result,
                diff,
                validationDiff,
                staleCommits,
                importedFromGitHub: draft.importedFromGitHub,
                providerReadiness: readiness,
            });
        } else {
            push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
            push(state, { type: 'draftLoaded', prKey: key, prState: 'NO_DRAFT', diff, validationDiff, providerReadiness: readiness });
        }
    } catch (err) {
        state.cachedToken = null;
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        push(state, {
            type: 'draftLoaded',
            prKey: key,
            prState: 'NO_DRAFT',
            status: toUserFacingError(err, 'load PR details'),
            providerReadiness: providerReadiness(),
        });
    }
}

async function handleGenerateReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo) return;
    const key = prKeyFromParts(number, owner, repo);
    if (prKey(state.activePR) !== key) {
        push(state, { type: 'reviewError', prKey: key, message: 'The selected pull request changed. Try again.' });
        return;
    }
    const selectionRevision = state.selectionRevision;

    // Provider preflight: fail fast with actionable guidance instead of a raw CLI spawn error
    // when the configured review provider's binary isn't installed/resolvable.
    const isCopilot = provider() === 'copilot';
    if (isCopilot ? !copilot.copilotBinaryAvailable() : !claude.claudeBinaryAvailable()) {
        push(state, {
            type: 'reviewError',
            prKey: key,
            message: providerNotInstalledMessage(isCopilot ? 'copilot' : 'claude'),
        });
        return;
    }

    push(state, { type: 'reviewGenerating', prKey: key, message: 'Fetching PR data…' });
    try {
        const token = await getToken(state);
        const base = githubBaseUrl();

        let diff = state.activeDiff;
        let validationDiff = state.activeValidationDiff;
        if (!diff) {
            diff = await github.getPRDiff(token, base, owner, repo, number);
            if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
            state.activeDiff = diff;
        }
        if (!validationDiff) {
            validationDiff = await github.getPRDiffFull(token, base, owner, repo, number).catch(() => diff);
            if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
            state.activeValidationDiff = validationDiff;
        }

        const existingReviews = await github.getExistingReviewsSummary(token, base, owner, repo, number).catch(() => '');

        const pr: claude.PR = {
            number,
            owner,
            repo,
            title: state.activePR?.title ?? '',
            body: state.activePR?.body ?? '',
        };

        const reviewDir = await resolveWorkingDir(
            state,
            { number, owner, repo, title: pr.title, body: pr.body ?? '' },
            token,
            true,
            key,
        );

        // Per-review overrides from the webview take precedence over the saved settings defaults.
        const focusAreas = typeof msg.focusAreas === 'string' && msg.focusAreas.trim()
            ? msg.focusAreas.trim()
            : reviewFocusAreas();
        const customInstructions = typeof msg.customInstructions === 'string' && msg.customInstructions.trim()
            ? msg.customInstructions.trim()
            : reviewCustomInstructions();

        const prompt = claude.buildPrompt({
            pr,
            diff,
            existingReviews,
            repoGuidelines: readRepoGuidelines(reviewDir),
            priorReview: formatPriorReview(state.activeReviewResult),
            focusAreas,
            customInstructions,
        });

        const result = isCopilot
            ? await copilot.reviewPR({
                prompt,
                model: reviewModel(),
                effort: reviewEffort(),
                workingDir: reviewDir,
                inheritMcp: copilotInheritMcp(),
                configDir: copilotConfigDir(),
                onStatus: (status) => push(state, { type: 'reviewGenerating', prKey: key, message: status }),
                onChunk: (kind, chunk) => push(state, { type: 'reviewChunk', prKey: key, kind, chunk }),
            })
            : await claude.reviewPR({
                prompt,
                model: reviewModel(),
                workingDir: reviewDir,
                onStatus: (status) => push(state, { type: 'reviewGenerating', prKey: key, message: status }),
                onChunk: (kind, chunk) => push(state, { type: 'reviewChunk', prKey: key, kind, chunk }),
            });

        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        state.activeReviewResult = result;
        push(state, { type: 'reviewResult', prKey: key, result, diff, validationDiff });
    } catch (err) {
        if (isCancellationError(err)) return;
        state.cachedToken = null;
        push(state, { type: 'reviewError', prKey: key, message: toUserFacingError(err, 'generate review') });
    }
}

async function handleSaveDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const resultFromMsg = msg.result as github.ReviewResult | undefined;
    const orphansFromMsg = (msg.orphans as github.LineComment[] | undefined) ?? [];
    const review = resultFromMsg ?? state.activeReviewResult;
    if (!number || !owner || !repo || !review) return;
    const key = prKeyFromParts(number, owner, repo);
    if (prKey(state.activePR) !== key) {
        push(state, { type: 'draftSaveError', prKey: key, message: 'The selected pull request changed before the draft could be saved.' });
        return;
    }
    const selectionRevision = state.selectionRevision;

    try {
        const token = await getToken(state);
        const { reviewId, commentsDropped } = await github.saveDraftReview(
            token, githubBaseUrl(), owner, repo, number, review, orphansFromMsg,
        );
        if (prKey(state.activePR) !== key || state.selectionRevision !== selectionRevision) return;
        state.pendingReviewId = reviewId;
        state.pendingReviewKey = key;
        push(state, { type: 'draftSaved', prKey: key, reviewId, commentsDropped });
        push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: true });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'draftSaveError',
            prKey: key,
            message: toUserFacingError(err, 'save draft review'),
        });
    }
}

async function handleSubmitReview(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    const verdict = msg.verdict as string;
    const comment = msg.comment as string ?? '';
    if (!number || !owner || !repo || !verdict || !state.pendingReviewId) return;
    const key = prKeyFromParts(number, owner, repo);
    if (prKey(state.activePR) !== key || state.pendingReviewKey !== key) {
        push(state, { type: 'reviewSubmitError', prKey: key, message: 'The pending draft does not belong to the selected pull request.' });
        return;
    }
    const reviewId = state.pendingReviewId;

    try {
        const token = await getToken(state);
        await github.submitReview(
            token, githubBaseUrl(), owner, repo, number,
            reviewId, verdict, comment,
        );
        if (state.pendingReviewId === reviewId && state.pendingReviewKey === key) {
            state.pendingReviewId = null;
            state.pendingReviewKey = null;
        }
        push(state, { type: 'reviewSubmitted', prKey: key });
        push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'reviewSubmitError',
            prKey: key,
            message: toUserFacingError(err, 'submit draft review'),
        });
    }
}

async function handleDeleteDraft(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const number = msg.number as number;
    const owner = msg.owner as string;
    const repo = msg.repo as string;
    if (!number || !owner || !repo || !state.pendingReviewId) return;
    const key = prKeyFromParts(number, owner, repo);
    if (prKey(state.activePR) !== key || state.pendingReviewKey !== key) {
        push(state, { type: 'draftDeleteError', prKey: key, message: 'The pending draft does not belong to the selected pull request.' });
        return;
    }
    const reviewId = state.pendingReviewId;

    try {
        const token = await getToken(state);
        await github.deleteDraftReview(
            token, githubBaseUrl(), owner, repo, number, reviewId,
        );
        if (state.pendingReviewId === reviewId && state.pendingReviewKey === key) {
            state.pendingReviewId = null;
            state.pendingReviewKey = null;
        }
        push(state, { type: 'draftDeleted', prKey: key });
        push(state, { type: 'prDraftStatusUpdated', number, owner, repo, hasReviewDraft: false });
    } catch (err) {
        state.cachedToken = null;
        push(state, {
            type: 'draftDeleteError',
            prKey: key,
            message: toUserFacingError(err, 'delete draft review'),
        });
    }
}

async function handleAskClaude(state: ViewState, msg: Record<string, unknown>): Promise<void> {
    const context = msg.context as string ?? '';
    const question = msg.question as string ?? '';
    if (!question.trim()) return;

    const key = prKey(state.activePR);
    if (key && !state.chatHistory.has(key)) state.chatHistory.set(key, []);
    const history = key ? (state.chatHistory.get(key) ?? []) : [];

    // Add user turn to history before sending
    history.push({ role: 'USER', content: question });

    const prompt = context.trim()
        ? claude.buildFocusedChatPrompt(context, question)
        : claude.buildChatPrompt(buildPrContext(state), history.slice(0, -1), question);

    const isCopilot = provider() === 'copilot';
    try {
        // Reuse the PR-branch worktree if one was built for the active PR (e.g. during review) so
        // chat sees the same source. Uses the cached token only — never triggers a fresh auth.
        const chatDir = state.activePR
            ? await resolveWorkingDir(state, state.activePR, state.cachedToken ?? '', false)
            : workingDir();
        const response = isCopilot
            ? await copilot.chat({
                prompt,
                effort: reviewEffort(),
                workingDir: chatDir,
                inheritMcp: copilotInheritMcp(),
                configDir: copilotConfigDir(),
                onChunk: (chunk) => push(state, { type: 'chatChunk', prKey: key ?? undefined, chunk }),
            })
            : await claude.chat({
                prompt,
                workingDir: chatDir,
                onChunk: (chunk) => push(state, { type: 'chatChunk', prKey: key ?? undefined, chunk }),
            });
        history.push({ role: 'ASSISTANT', content: response });
        push(state, { type: 'chatResponse', prKey: key ?? undefined, response });
    } catch (err) {
        history.pop(); // always undo the pre-push so cancelled/failed turns don't orphan in history
        if (isCancellationError(err)) return;
        push(state, { type: 'chatError', prKey: key ?? undefined, message: toUserFacingError(err, 'answer chat question') });
    }
}

// ── Utilities ──────────────────────────────────────────────────────────────────

function buildPrContext(state: ViewState): string {
    if (!state.activePR) return '';
    const pr = state.activePR;
    const lines = [
        `PR #${pr.number}: ${pr.title}`,
        `Repo: ${pr.owner}/${pr.repo}`,
    ];
    if (state.activeReviewResult) {
        const r = state.activeReviewResult;
        lines.push('', `Review verdict: ${r.verdict}`);
        if (r.summary) lines.push(`Summary: ${r.summary}`);
    }
    if (state.activeDiff) {
        // Pass the full diff (already capped at 250 KB by getPRDiff) for parity with the IntelliJ
        // host, which also sends the complete diff as chat context.
        lines.push('', 'Diff:', state.activeDiff);
    }
    return lines.join('\n');
}

function errorMessage(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
}

function isCancellationError(err: unknown): boolean {
    return errorMessage(err).toLowerCase().includes('cancel');
}
