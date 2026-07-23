export type Severity = 'blocker' | 'major' | 'minor' | 'nit';
export type Category = 'correctness' | 'security' | 'performance' | 'tests' | 'maintainability' | 'style';
export type Confidence = 'low' | 'medium' | 'high';

export interface PR {
    number: number;
    title: string;
    owner: string;
    repo: string;
    author: string;
    createdAt: string;
    htmlUrl: string;
    isDraft: boolean;
    hasReviewDraft: boolean;
}

export interface LineComment {
    file: string;
    line: number;
    type: 'issue' | 'suggestion' | 'note';
    body: string;
    severity?: Severity;
    category?: Category;
    confidence?: Confidence;
    rationale?: string;
}

export interface ReviewResult {
    summary: string;
    verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT';
    lineComments: LineComment[];
}

export type PRSearchScope = 'currentRepo' | 'authored' | 'assigned' | 'reviewRequested';

