export type ProviderAuthenticationStatus = 'ready' | 'unavailable' | 'unverified';

export type ClaudeAuthProbeRunner = (
    complete: (error: Error | null, stdout: string, stderr: string) => void,
) => void;

export function classifyClaudeAuthProbe(
    error: Error | null,
    stdout: string,
    stderr: string,
): ProviderAuthenticationStatus {
    const output = `${stdout}\n${stderr}`.toLowerCase();
    if (output.includes('unknown command')
        || output.includes('unrecognized command')
        || output.includes('unsupported command')
        || output.includes('invalid command')
        || output.includes('unknown option')
        || output.includes('unrecognized option')
        || output.includes('unexpected argument')) {
        return 'unverified';
    }
    if (!error) return 'ready';

    const processError = error as Error & { code?: unknown; killed?: boolean; signal?: unknown };
    if (processError.killed || processError.signal || typeof processError.code !== 'number') {
        return 'unverified';
    }
    return output.includes('not logged in')
        || output.includes('not authenticated')
        || output.includes('signed out')
        || output.includes('logged out')
        ? 'unavailable'
        : 'unverified';
}

export async function probeClaudeAuthentication(
    run: ClaudeAuthProbeRunner,
): Promise<ProviderAuthenticationStatus> {
    return new Promise((resolve) => {
        try {
            run((error, stdout, stderr) => resolve(classifyClaudeAuthProbe(error, stdout, stderr)));
        } catch {
            resolve('unverified');
        }
    });
}
