import type { ReviewResult, LineComment, Severity, Category, Confidence } from './github';

const VERDICTS = ['APPROVE', 'REQUEST_CHANGES', 'COMMENT'] as const;
const COMMENT_TYPES = ['issue', 'suggestion', 'note'] as const;
const SEVERITIES = ['blocker', 'major', 'minor', 'nit'] as const;
const CATEGORIES = ['correctness', 'security', 'performance', 'tests', 'maintainability'] as const;
const CONFIDENCES = ['low', 'medium', 'high'] as const;

function hasExactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
    const keys = Object.keys(value);
    return keys.length === expected.length && keys.every((key) => expected.includes(key));
}

function isLineComment(value: unknown): value is LineComment {
    if (typeof value !== 'object' || value === null) return false;
    const c = value as Record<string, unknown>;
    const type = c.type;
    const expectedKeys = type === 'note'
        ? ['file', 'line', 'type', 'severity', 'category', 'confidence', 'body']
        : ['file', 'line', 'type', 'severity', 'category', 'confidence', 'rationale', 'body'];
    return hasExactKeys(c, expectedKeys)
        && typeof c.file === 'string' && c.file.length > 0
        && typeof c.line === 'number'
        && Number.isInteger(c.line) && c.line > 0
        && typeof type === 'string' && (COMMENT_TYPES as readonly string[]).includes(type)
        && typeof c.body === 'string'
        && c.body.length > 0 && c.body.length <= 300 && !/[\r\n]/.test(c.body)
        && typeof c.severity === 'string' && (SEVERITIES as readonly string[]).includes(c.severity)
        && typeof c.category === 'string' && (CATEGORIES as readonly string[]).includes(c.category)
        && typeof c.confidence === 'string' && (CONFIDENCES as readonly string[]).includes(c.confidence)
        && !(type === 'issue' && c.confidence === 'low')
        && (type === 'note'
            || (typeof c.rationale === 'string' && c.rationale.length > 0 && c.rationale.length <= 200));
}

function normalizeComment(value: LineComment): LineComment {
    const c = value as unknown as Record<string, string>;
    return {
        file: value.file,
        line: value.line,
        type: value.type,
        body: value.body,
        severity: c.severity as Severity,
        category: c.category as Category,
        confidence: c.confidence as Confidence,
        rationale: c.rationale,
    };
}

/**
 * Extracts and validates a {@link ReviewResult} from raw provider output (which may include
 * markdown fences or leading/trailing prose). Unlike a bare `JSON.parse(...) as ReviewResult`,
 * this enforces the schema so malformed-but-valid JSON fails here with a clear error instead of
 * crashing later in consumers like `buildCommentArray`. Mirrors the strict validation in the Kotlin
 * host.
 */
export function parseReview(raw: string): ReviewResult {
    let json = raw.trim();
    if (json.startsWith('```')) {
        const newline = json.indexOf('\n');
        const closing = json.lastIndexOf('```');
        if (newline > 0 && closing > newline) json = json.substring(newline + 1, closing).trim();
    }
    const start = json.indexOf('{');
    const end = json.lastIndexOf('}');
    if (start >= 0 && end > start) json = json.substring(start, end + 1);

    const parsed: unknown = JSON.parse(json);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        throw new Error('review JSON is not an object');
    }
    const obj = parsed as Record<string, unknown>;
    if (typeof obj.summary !== 'string') {
        throw new Error('review JSON missing string "summary"');
    }
    if (obj.summary.length > 800) {
        throw new Error('review JSON summary exceeds 800 characters');
    }
    if (!(VERDICTS as readonly string[]).includes(obj.verdict as string)) {
        throw new Error('review JSON has invalid "verdict"');
    }
    if (!hasExactKeys(obj, ['summary', 'verdict', 'lineComments'])) {
        throw new Error('review JSON has unexpected or missing top-level fields');
    }
    if (!Array.isArray(obj.lineComments) || obj.lineComments.length > 20 || !obj.lineComments.every(isLineComment)) {
        throw new Error('review JSON has invalid "lineComments"');
    }
    const hasIssue = obj.lineComments.some((comment) => comment.type === 'issue');
    if ((obj.verdict === 'REQUEST_CHANGES') !== hasIssue) {
        throw new Error('review JSON verdict does not match issue comments');
    }
    return {
        summary: obj.summary,
        verdict: obj.verdict as ReviewResult['verdict'],
        lineComments: obj.lineComments.map(normalizeComment),
    };
}
