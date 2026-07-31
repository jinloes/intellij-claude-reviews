export interface ReviewGuidanceProfile {
    id: string;
    name: string;
    focusAreas: string;
    customInstructions: string;
    guidanceGlobs: string[];
}

export interface ReviewGuidanceDefaults {
    focusAreas: string;
    customInstructions: string;
    guidanceGlobs: string[];
}

export interface ResolvedReviewGuidance extends ReviewGuidanceDefaults {
    activeProfileId: string;
}

export interface ReviewGuidanceState {
    profiles: ReviewGuidanceProfile[];
    activeProfileId: string;
}

const MAX_PROFILES = 50;
const MAX_ID_LENGTH = 128;
const MAX_NAME_LENGTH = 100;
const MAX_TEXT_LENGTH = 20_000;
const MAX_GLOBS = 100;
const MAX_GLOB_LENGTH = 500;

function normalizedString(value: unknown, maxLength: number): string | null {
    if (typeof value !== 'string' || value.length > maxLength) return null;
    return value.trim();
}

export function normalizeReviewGuidanceGlobs(value: unknown): string[] | null {
    if (!Array.isArray(value) || value.length > MAX_GLOBS) return null;
    const normalized: string[] = [];
    for (const globValue of value) {
        const glob = normalizedString(globValue, MAX_GLOB_LENGTH);
        if (glob === null) return null;
        if (glob) normalized.push(glob);
    }
    return normalized;
}

/** Strictly validates and normalizes profile data read from settings or the settings webview. */
export function normalizeReviewGuidanceProfiles(value: unknown): ReviewGuidanceProfile[] | null {
    if (!Array.isArray(value) || value.length > MAX_PROFILES) return null;
    const normalized: ReviewGuidanceProfile[] = [];
    const ids = new Set<string>();
    for (const candidate of value) {
        if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) return null;
        const record = candidate as Record<string, unknown>;
        const id = normalizedString(record.id, MAX_ID_LENGTH);
        const name = normalizedString(record.name, MAX_NAME_LENGTH);
        const focusAreas = normalizedString(record.focusAreas, MAX_TEXT_LENGTH);
        const customInstructions = normalizedString(record.customInstructions, MAX_TEXT_LENGTH);
        if (!id || !name || focusAreas === null || customInstructions === null) return null;
        const guidanceGlobs = normalizeReviewGuidanceGlobs(record.guidanceGlobs);
        if (guidanceGlobs === null) return null;
        if (ids.has(id)) return null;
        ids.add(id);
        normalized.push({ id, name, focusAreas, customInstructions, guidanceGlobs });
    }
    return normalized;
}

export function normalizeReviewGuidanceState(
    profilesValue: unknown,
    activeProfileIdValue: unknown,
): ReviewGuidanceState | null {
    const profiles = normalizeReviewGuidanceProfiles(profilesValue);
    const activeProfileId = normalizedString(activeProfileIdValue, MAX_ID_LENGTH);
    if (profiles === null || activeProfileId === null) return null;
    if (activeProfileId && !profiles.some((profile) => profile.id === activeProfileId)) return null;
    return { profiles, activeProfileId };
}

/** Resolves a saved profile, falling back atomically to the legacy built-in defaults. */
export function resolveReviewGuidance(
    profilesValue: unknown,
    activeProfileIdValue: unknown,
    defaults: ReviewGuidanceDefaults,
): ResolvedReviewGuidance {
    const profiles = normalizeReviewGuidanceProfiles(profilesValue) ?? [];
    const activeProfileId = typeof activeProfileIdValue === 'string' ? activeProfileIdValue.trim() : '';
    const active = profiles.find((profile) => profile.id === activeProfileId);
    if (!active) {
        return { ...defaults, activeProfileId: '' };
    }
    return {
        focusAreas: active.focusAreas,
        customInstructions: active.customInstructions,
        guidanceGlobs: [...active.guidanceGlobs],
        activeProfileId: active.id,
    };
}
