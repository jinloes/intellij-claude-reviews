import test from 'node:test';
import assert from 'node:assert/strict';

import {
    normalizeReviewGuidanceGlobs,
    normalizeReviewGuidanceProfiles,
    normalizeReviewGuidanceState,
    resolveReviewGuidance,
} from '../src/reviewGuidanceProfiles';

const defaults = {
    focusAreas: 'legacy focus',
    customInstructions: 'legacy instructions',
    guidanceGlobs: ['AGENTS.md'],
};

const profile = {
    id: 'team-java',
    name: ' Team Java ',
    focusAreas: ' security, concurrency ',
    customInstructions: ' Require regression tests ',
    guidanceGlobs: [' CLAUDE.md ', '', '.review/ai-agent/*.md'],
};

test('normalizeReviewGuidanceGlobs trims values and rejects malformed entries', () => {
    assert.deepEqual(normalizeReviewGuidanceGlobs([' AGENTS.md ', '', 'CLAUDE.md']), [
        'AGENTS.md',
        'CLAUDE.md',
    ]);
    assert.equal(normalizeReviewGuidanceGlobs(['AGENTS.md', 42]), null);
    assert.equal(normalizeReviewGuidanceGlobs('AGENTS.md'), null);
});

test('normalizeReviewGuidanceProfiles trims fields and removes blank globs', () => {
    assert.deepEqual(normalizeReviewGuidanceProfiles([profile]), [{
        id: 'team-java',
        name: 'Team Java',
        focusAreas: 'security, concurrency',
        customInstructions: 'Require regression tests',
        guidanceGlobs: ['CLAUDE.md', '.review/ai-agent/*.md'],
    }]);
});

test('normalizeReviewGuidanceProfiles rejects malformed or duplicate profiles', () => {
    assert.equal(normalizeReviewGuidanceProfiles('not an array'), null);
    assert.equal(normalizeReviewGuidanceProfiles([{ ...profile, name: '' }]), null);
    assert.equal(normalizeReviewGuidanceProfiles([profile, { ...profile }]), null);
    assert.equal(normalizeReviewGuidanceProfiles([{ ...profile, guidanceGlobs: [42] }]), null);
});

test('normalizeReviewGuidanceState accepts only catalog-backed active IDs', () => {
    assert.deepEqual(normalizeReviewGuidanceState([profile], ' team-java '), {
        profiles: [{
            id: 'team-java',
            name: 'Team Java',
            focusAreas: 'security, concurrency',
            customInstructions: 'Require regression tests',
            guidanceGlobs: ['CLAUDE.md', '.review/ai-agent/*.md'],
        }],
        activeProfileId: 'team-java',
    });
    assert.equal(normalizeReviewGuidanceState([profile], 'deleted'), null);
    assert.equal(normalizeReviewGuidanceState('invalid', ''), null);
});

test('resolveReviewGuidance atomically selects a valid named profile', () => {
    assert.deepEqual(resolveReviewGuidance([profile], 'team-java', defaults), {
        activeProfileId: 'team-java',
        focusAreas: 'security, concurrency',
        customInstructions: 'Require regression tests',
        guidanceGlobs: ['CLAUDE.md', '.review/ai-agent/*.md'],
    });
});

test('resolveReviewGuidance falls back for missing or malformed active profiles', () => {
    assert.deepEqual(resolveReviewGuidance([profile], 'deleted', defaults), {
        ...defaults,
        activeProfileId: '',
    });
    assert.deepEqual(resolveReviewGuidance([{ ...profile, id: '' }], 'team-java', defaults), {
        ...defaults,
        activeProfileId: '',
    });
});

test('resolveReviewGuidance preserves empty profile globs for engine defaults', () => {
    assert.deepEqual(resolveReviewGuidance([{ ...profile, guidanceGlobs: [] }], 'team-java', defaults), {
        activeProfileId: 'team-java',
        focusAreas: 'security, concurrency',
        customInstructions: 'Require regression tests',
        guidanceGlobs: [],
    });
});
