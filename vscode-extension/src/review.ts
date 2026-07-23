import type { ReviewResult, LineComment, Severity, Category, Confidence } from './models';

const VERDICTS = ['APPROVE', 'REQUEST_CHANGES', 'COMMENT'] as const;
const COMMENT_TYPES = ['issue', 'suggestion', 'note'] as const;
const SEVERITIES = ['blocker', 'major', 'minor', 'nit'] as const;
const CATEGORIES = ['correctness', 'security', 'performance', 'tests', 'maintainability'] as const;
const CONFIDENCES = ['low', 'medium', 'high'] as const;
const MAX_SUMMARY_CHARS = 800;
const MAX_BODY_CHARS = 300;
const MAX_RATIONALE_CHARS = 200;
const MAX_LINE_COMMENTS = 20;

function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

/**
 * Validates and normalizes a single raw line comment, returning `undefined` when it is
 * unsalvageable. A low-confidence "issue" is downgraded to "suggestion" instead of being
 * dropped, matching the auto-repair already offered to users in the review-quality UI. Mirrors
 * `ClaudeService.repairLineComment` in the Kotlin host.
 */
function repairLineComment(value: unknown): LineComment | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const c = value as Record<string, unknown>;

  const file = asString(c.file)?.trim();
  if (!file) return undefined;

  const line = typeof c.line === 'number' && Number.isInteger(c.line) && c.line > 0 ? c.line : undefined;
  if (!line) return undefined;

  const type = asString(c.type);
  if (!type || !(COMMENT_TYPES as readonly string[]).includes(type)) return undefined;

  let body = asString(c.body)?.replace(/[\r\n]+/g, ' ').trim();
  if (!body) return undefined;
  if (body.length > MAX_BODY_CHARS) body = body.slice(0, MAX_BODY_CHARS);

  const severity = asString(c.severity);
  if (!severity || !(SEVERITIES as readonly string[]).includes(severity)) return undefined;

  const category = asString(c.category);
  if (!category || !(CATEGORIES as readonly string[]).includes(category)) return undefined;

  const confidence = asString(c.confidence);
  if (!confidence || !(CONFIDENCES as readonly string[]).includes(confidence)) return undefined;

  const effectiveType = type === 'issue' && confidence === 'low' ? 'suggestion' : type;

  let rationale: string | undefined;
  if (effectiveType !== 'note') {
    rationale = asString(c.rationale)?.trim();
    if (!rationale) return undefined;
    if (rationale.length > MAX_RATIONALE_CHARS) rationale = rationale.slice(0, MAX_RATIONALE_CHARS);
  }

  return {
    file,
    line,
    type: effectiveType as LineComment['type'],
    body,
    severity: severity as Severity,
    category: category as Category,
    confidence: confidence as Confidence,
    rationale,
  };
}

/**
 * Extracts and validates a {@link ReviewResult} from raw provider output (which may include
 * markdown fences or leading/trailing prose). Individual malformed line comments are dropped
 * (and a low-confidence "issue" is downgraded to "suggestion") rather than failing the entire
 * review — capable models occasionally emit one non-conforming comment among otherwise-valid
 * output, and rejecting the whole review in that case throws away every other good comment to
 * punish one bad one. The top-level shape (an object with a string "summary" and an array
 * "lineComments") is still a hard requirement, since there is nothing to salvage without it.
 * Mirrors the strict-then-lenient validation in the Kotlin host (`ClaudeService.parseReview`).
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
    const summary = obj.summary.length > MAX_SUMMARY_CHARS ? obj.summary.slice(0, MAX_SUMMARY_CHARS) : obj.summary;

    const requestedVerdict = (VERDICTS as readonly string[]).includes(obj.verdict as string)
        ? (obj.verdict as ReviewResult['verdict'])
        : undefined;

    const rawComments = Array.isArray(obj.lineComments) ? obj.lineComments : [];
    const lineComments: LineComment[] = [];
    for (const candidate of rawComments) {
        if (lineComments.length >= MAX_LINE_COMMENTS) break;
        const repaired = repairLineComment(candidate);
        if (repaired) lineComments.push(repaired);
    }

    const hasIssue = lineComments.some((comment) => comment.type === 'issue');
    const verdict: ReviewResult['verdict'] =
        requestedVerdict === 'REQUEST_CHANGES' && !hasIssue
            ? 'COMMENT'
            : requestedVerdict !== 'REQUEST_CHANGES' && hasIssue
              ? 'REQUEST_CHANGES'
              : (requestedVerdict ?? (hasIssue ? 'REQUEST_CHANGES' : 'COMMENT'));

    return { summary, verdict, lineComments };
}
