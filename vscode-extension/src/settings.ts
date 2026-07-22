import * as vscode from 'vscode';
import * as crypto from 'crypto';
import * as copilot from './copilot';
import * as github from './github';
import {
    buildSettingsHtml,
    GITHUB_BASE_URL_ERROR,
    mergeCopilotModelOptions,
    normalizeGithubBaseUrl,
    normalizeProvider,
    type SettingsState,
} from './settingsView';

let panel: vscode.WebviewPanel | undefined;

interface GitHubAuthResult {
    status: string;
    username: string | null;
    message: string;
}

export interface GitHubAuthSidecar {
    checkGitHubAuth(githubBaseUrl: string): Promise<GitHubAuthResult | null>;
}

function config(): vscode.WorkspaceConfiguration {
    return vscode.workspace.getConfiguration('pr-pilot');
}

function readState(): SettingsState {
    const c = config();
    return {
        provider: normalizeProvider(c.get<string>('reviewProvider', 'claude')),
        reviewModel: c.get<string>('reviewModel', ''),
        reviewModelCopilot: c.get<string>('reviewModelCopilot', ''),
        reviewEffort: c.get<string>('reviewEffort', 'medium'),
        githubBaseUrl: c.get<string>('githubBaseUrl', 'https://github.com'),
        copilotInheritMcp: c.get<boolean>('copilotInheritMcp', false),
        copilotAutoEnableMcpOnReview: c.get<boolean>('copilotAutoEnableMcpOnReview', false),
        copilotConfigDir: c.get<string>('copilotConfigDir', ''),
        reviewFocusAreas: c.get<string>('reviewFocusAreas', ''),
        reviewCustomInstructions: c.get<string>('reviewCustomInstructions', ''),
        notificationsEnabled: c.get<boolean>('notificationsEnabled', false),
        notifyReviewRequested: c.get<boolean>('notifyReviewRequested', true),
        notifyStarredRepos: c.get<boolean>('notifyStarredRepos', false),
        notificationPollMinutes: c.get<number>('notificationPollMinutes', 5),
    };
}

const ALLOWED_KEYS = new Set([
    'reviewProvider', 'reviewModel', 'reviewModelCopilot', 'reviewEffort', 'githubBaseUrl',
    'copilotInheritMcp', 'copilotAutoEnableMcpOnReview', 'copilotConfigDir', 'reviewFocusAreas',
    'reviewCustomInstructions',
    'notificationsEnabled', 'notifyReviewRequested', 'notifyStarredRepos', 'notificationPollMinutes',
]);

const BOOLEAN_KEYS = new Set([
    'copilotInheritMcp',
    'copilotAutoEnableMcpOnReview',
    'notificationsEnabled',
    'notifyReviewRequested',
    'notifyStarredRepos',
]);

/** Opens (or reveals) the PR Pilot settings webview panel. */
export function openSettings(context: vscode.ExtensionContext, sidecar?: GitHubAuthSidecar): void {
    if (panel) {
        panel.reveal();
        return;
    }
    panel = vscode.window.createWebviewPanel(
        'pr-pilot.settings',
        'PR Pilot Settings',
        vscode.ViewColumn.Active,
        { enableScripts: true, retainContextWhenHidden: true },
    );
    const current = panel;
    const nonce = crypto.randomBytes(16).toString('base64');
    current.webview.html = buildSettingsHtml(current.webview.cspSource, nonce);

    const sendInit = async () => {
        const state = readState();
        const discovered = await copilot.listModels().catch(() => []);
        current.webview.postMessage({
            type: 'init',
            state,
            copilotModels: mergeCopilotModelOptions(discovered, state.reviewModelCopilot),
        });
    };

    current.webview.onDidReceiveMessage(async (msg: { type?: string } & Record<string, unknown>) => {
        if (!msg || typeof msg.type !== 'string') return;
        switch (msg.type) {
            case 'ready':
                await sendInit();
                break;
            case 'update': {
                const key = typeof msg.key === 'string' ? msg.key : '';
                if (!ALLOWED_KEYS.has(key)) return;
                if (BOOLEAN_KEYS.has(key)) {
                    const boolValue = msg.value === true;
                    try {
                        await config().update(key, boolValue, vscode.ConfigurationTarget.Global);
                        current.webview.postMessage({ type: 'saveResult', ok: true, key, message: 'Saved.' });
                    } catch (err) {
                        current.webview.postMessage({
                            type: 'saveResult',
                            ok: false,
                            key,
                            message: err instanceof Error ? err.message : 'Could not save setting.',
                        });
                    }
                    break;
                }
                if (key === 'notificationPollMinutes') {
                    const numericValue = typeof msg.value === 'number' ? msg.value : Number.NaN;
                    if (!Number.isInteger(numericValue) || numericValue < 1 || numericValue > 60) {
                        current.webview.postMessage({ type: 'saveResult', ok: false, key, message: 'Polling interval must be between 1 and 60 minutes.' });
                        return;
                    }
                    await config().update(key, numericValue, vscode.ConfigurationTarget.Global);
                    current.webview.postMessage({ type: 'saveResult', ok: true, key, message: 'Saved.' });
                    break;
                }
                let value = typeof msg.value === 'string' ? msg.value : '';
                if (key === 'githubBaseUrl') {
                    try {
                        value = normalizeGithubBaseUrl(value);
                    } catch {
                        current.webview.postMessage({
                            type: 'saveResult',
                            ok: false,
                            key,
                            message: GITHUB_BASE_URL_ERROR,
                        });
                        return;
                    }
                }
                try {
                    await config().update(key, value, vscode.ConfigurationTarget.Global);
                    current.webview.postMessage({ type: 'saveResult', ok: true, key, message: 'Saved.' });
                } catch (err) {
                    current.webview.postMessage({
                        type: 'saveResult',
                        ok: false,
                        key,
                        message: err instanceof Error ? err.message : 'Could not save setting.',
                    });
                }
                break;
            }
            case 'refreshModels': {
                let ok = true;
                let message = 'Model list refreshed.';
                const discovered = await copilot.listModels(true).catch((err) => {
                    ok = false;
                    message = err instanceof Error ? err.message : 'Could not refresh models.';
                    return [];
                });
                const state = readState();
                current.webview.postMessage({
                    type: 'models',
                    ok,
                    message,
                    copilotModels: mergeCopilotModelOptions(discovered, state.reviewModelCopilot),
                });
                break;
            }
            case 'testConnection': {
                const configuredBaseUrl = typeof msg.githubBaseUrl === 'string' && msg.githubBaseUrl.trim()
                    ? msg.githubBaseUrl.trim()
                    : readState().githubBaseUrl;
                let baseUrl: string;
                try {
                    baseUrl = normalizeGithubBaseUrl(configuredBaseUrl);
                } catch {
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: false,
                        message: GITHUB_BASE_URL_ERROR,
                    });
                    return;
                }
                try {
                    const authResult = await sidecar?.checkGitHubAuth(baseUrl);
                    if (authResult) {
                        current.webview.postMessage({
                            type: 'testResult',
                            ok: authResult.status === 'authenticated',
                            message: authResult.status === 'authenticated' && authResult.username
                                ? `Signed in as @${authResult.username}.`
                                : authResult.message,
                        });
                        return;
                    }
                    await github.resolveToken(baseUrl);
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: true,
                        message: 'gh authentication is available for this host.',
                    });
                } catch (err) {
                    current.webview.postMessage({
                        type: 'testResult',
                        ok: false,
                        message: err instanceof Error ? err.message : 'Could not verify gh authentication.',
                    });
                }
                break;
            }
            default:
                break;
        }
    }, undefined, context.subscriptions);

    current.onDidDispose(() => { panel = undefined; }, undefined, context.subscriptions);
}
