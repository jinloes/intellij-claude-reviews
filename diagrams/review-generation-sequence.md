# PR Review Generation Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Reviewer
    participant UI as Shared webview
    participant Host as IDE host
    participant Sidecar as VS Code sidecar
    participant GitHubEngine as GitHubEngineApi
    participant GitHub as GitHub API
    participant ReviewEngine as ReviewEngineApi
    participant Worktree as GitWorktreeService
    participant Pipeline as ReviewPipelineService
    participant Provider as Claude or Copilot

    Reviewer->>UI: Select pull request
    UI->>Host: selectPR(prKey)
    Host->>GitHubEngine: Load detail, review diff, validation diff, and draft
    GitHubEngine->>GitHub: Authenticated read requests
    GitHub-->>GitHubEngine: PR metadata, diffs, and draft
    GitHubEngine-->>Host: Token-free results
    Host->>Worktree: Find repository root and create PR-head worktree
    Worktree-->>Host: Exact or failed worktree result
    Note over Host,Worktree: IntelliJ calls in-process while VS Code routes these calls through the sidecar.

    Reviewer->>UI: Generate review with per-review overrides
    UI->>Host: generateReview(prKey, options)
    Host->>Host: Snapshot generation ID, settings, provider, and guidance

    par Load additive review context
        Host->>GitHubEngine: Get checks and CI annotations
        GitHubEngine->>GitHub: Read check runs and annotations
        GitHub-->>GitHubEngine: CI state
        GitHubEngine-->>Host: Bounded CI summary and annotations
    and
        Host->>GitHubEngine: Get commits and linked issues
        GitHubEngine->>GitHub: Read commits and referenced issues
        GitHub-->>GitHubEngine: Commit and issue context
        GitHubEngine-->>Host: Bounded prompt context
    and
        Host->>GitHubEngine: Detect repository profile
        GitHubEngine-->>Host: Languages and build tools
    end

    alt IntelliJ
        Host->>Pipeline: review(request, options) in-process
    else VS Code
        Host->>Sidecar: reviews/generate JSON-RPC request
        Sidecar->>ReviewEngine: generate(params)
        ReviewEngine->>Pipeline: review(request, options)
    end

    alt Direct review
        Pipeline->>Provider: Primary review with read-only worktree tools
        Provider-->>Pipeline: Review JSON and inspection ledger
    else Chunked review
        loop Each bounded file batch
            Pipeline->>Provider: Review batch and record contract signals
            Provider-->>Pipeline: Batch review and inspection ledger
        end
        Pipeline->>Provider: Mandatory global reconciliation
        Provider-->>Pipeline: Complete reconciled review
    end

    opt Supervisor enabled and high-risk gaps remain
        Pipeline->>Pipeline: Validate anchors and analyze inspection coverage
        opt More than three candidate gaps
            Pipeline->>Provider: Tool-free prioritization of supplied gap IDs
            Provider-->>Pipeline: At most three selected targets
        end
        Pipeline->>Provider: One targeted read-only follow-up with MCP disabled
        Provider-->>Pipeline: Follow-up review and inspection ledger
        Pipeline->>Pipeline: Merge and deduplicate findings
    end

    opt Self-critique enabled
        Pipeline->>Provider: Validate findings against the same bounded context
        Provider-->>Pipeline: Refined review
    end

    Pipeline->>Pipeline: Validate changed-line anchors and suppress CI duplicates

    alt IntelliJ
        Pipeline-->>Host: Completion callback with final ReviewResult
    else VS Code
        Pipeline-->>ReviewEngine: Final ReviewResult
        ReviewEngine-->>Sidecar: JSON-RPC result
        Sidecar-->>Host: Parsed ReviewResult
    end

    Host-->>UI: reviewResult(prKey, generation ID)
    UI-->>Reviewer: Editable review, findings, verdict, and activity history

    Note over Pipeline,UI: Lifecycle status is reduced to safe phase labels before display.<br/>Provider text and private thinking are never persisted or rendered.
    Note over Reviewer,ReviewEngine: Stop generation invalidates the active generation ID and cancels the matching operation before worktree cleanup.
```

## Failure behavior

- Missing optional GitHub context omits that prompt section without failing the review.
- Primary provider failure is terminal.
- Supervisor selection, targeted follow-up, and final critique failures keep the best valid review.
- Chunk reconciliation failure returns a visibly marked degraded batch-only result.
- Cancellation and interruption propagate rather than returning a success-shaped fallback.
