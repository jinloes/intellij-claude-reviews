# PR Pilot Architecture

```mermaid
flowchart LR
    Reviewer(["Reviewer"])
    GitHub[("GitHub REST and<br/>GraphQL APIs")]
    GhCli["GitHub CLI<br/>token resolution"]
    Provider["Claude CLI or<br/>Copilot runtime"]
    Checkout[("Local repository<br/>and detached PR worktree")]
    OutcomeData[("~/.pr-pilot/<br/>review-outcomes.jsonl")]
    HostState[("Host persistence<br/>IntelliJ local files / VS Code globalState")]

    subgraph SharedUi["Shared UI - webview/"]
        App["App / ReviewPane"]
        Bridge["Typed host bridge"]
        App <--> Bridge
    end

    subgraph IntelliJ["IntelliJ host - intellij-plugin/"]
        ToolWindow["JCEF tool window<br/>WebviewPanel"]
        IntelliJAdapters["IntellijGitHubService<br/>IntellijClaudeService"]
        ToolWindow <--> IntelliJAdapters
    end

    subgraph VsCode["VS Code host - vscode-extension/"]
        EditorPanel["Editor webview panel<br/>extension.ts"]
        SidecarClient["sidecar.ts<br/>JSON-RPC client"]
        EditorPanel <--> SidecarClient
    end

    subgraph RpcAdapter["VS Code transport - sidecar/"]
        Sidecar["Stdio JSON-RPC adapter<br/>validation, dispatch, notifications"]
    end

    subgraph Engines["Host-neutral Java 17 engines"]
        subgraph GitHubEngine["github-engine/"]
            GitHubApi["GitHubEngineApi"]
            GitHubServices["PR discovery and review freshness,<br/>context, diff, draft, and submission services"]
            GitHubApi --> GitHubServices
        end

        subgraph ReviewEngine["review-engine/"]
            ReviewApi["ReviewEngineApi"]
            Session["ReviewSessionService"]
            Pipeline["ReviewPipelineService<br/>primary or chunked review,<br/>bounded supervision,<br/>critique, CI suppression"]
            Worktrees["GitWorktreeService"]
            Outcomes["ReviewOutcomeLog"]
            ReviewApi --> Session
            Session --> Pipeline
            Session --> Worktrees
            Session --> Outcomes
        end

        Core["core/<br/>shared PR, review-status, comment,<br/>chat, and request models"]
        Core -. shared models .-> GitHubApi
        Core -. shared models .-> ReviewApi
    end

    Reviewer <--> App
    Bridge <--> ToolWindow
    Bridge <--> EditorPanel

    IntelliJAdapters --> GitHubApi
    IntelliJAdapters --> Pipeline
    IntelliJAdapters --> Worktrees
    IntelliJAdapters --> Outcomes

    SidecarClient <--> Sidecar
    Sidecar --> GitHubApi
    Sidecar --> ReviewApi

    GitHubServices --> GhCli
    GitHubServices <--> GitHub
    Worktrees <--> Checkout
    Pipeline <--> Provider
    Outcomes --> OutcomeData
    ToolWindow --> HostState
    EditorPanel --> HostState
```

## Boundary rules

- `core`, `github-engine`, and `review-engine` own host-neutral behavior.
- IntelliJ consumes GitHub capabilities through `GitHubEngineApi` and calls review-engine services
  in-process. VS Code reaches both capability surfaces only through the sidecar.
- The shared webview owns the user workflow and communicates through the typed host bridge.
- GitHub credentials remain inside `github-engine`; hosts receive only token-free results.
- Provider processes, worktrees, prompts, parsing, supervision, and review semantics remain inside
  `review-engine`.
- Engine capabilities are declared once and exposed to every host; hosts may not reimplement them.
