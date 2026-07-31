export interface OperationCorrelationState {
    disposed: boolean;
    selectionRevision: number;
    generationRevision: number;
    chatRevision: number;
}

export async function cancelForSelection(
    state: OperationCorrelationState,
    selectionRevision: number,
    cancel: () => Promise<void>,
): Promise<boolean> {
    await cancel();
    return !state.disposed && state.selectionRevision === selectionRevision;
}

export async function invalidateGenerationAndCancel(
    state: OperationCorrelationState,
    cancel: () => Promise<void>,
): Promise<void> {
    state.generationRevision++;
    await cancel();
}

export function invalidateChat(state: OperationCorrelationState): void {
    state.chatRevision++;
}

export async function invalidateChatAndCancel(
    state: OperationCorrelationState,
    cancel: () => Promise<void>,
    ownsProvider = true,
): Promise<void> {
    invalidateChat(state);
    if (ownsProvider) await cancel();
}

export async function cancelThenCleanup(
    cancel: () => Promise<void>,
    cleanup: () => void,
): Promise<void> {
    try {
        await cancel();
    } finally {
        cleanup();
    }
}

export function canPersistDraft(activePr: boolean, hasExplicitResult: boolean): boolean {
    return activePr || hasExplicitResult;
}
