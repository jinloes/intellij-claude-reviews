# PR Pilot Code Map

Lookup guide for implementation work. Read this file when locating code or tests; read
`ARCHITECTURE.md` only when the change affects system boundaries, persistence, or design constraints.

## Task-to-code index

| Task | Start here | Follow through | Primary tests |
|---|---|---|---|
| Change review generation or prompts | `review-engine/.../ClaudeService.java`, `CopilotService.java` | `ReviewEngineApi.java`, `ReviewSessionService.java`, host request wiring | Matching `review-engine` service tests; prompt mirrors listed in `AGENTS.md` |
| Add an engine capability | `GitHubEngineApi.java` or `ReviewEngineApi.java` | `StdioJsonRpcServer.java`, `SidecarBootstrapService.java`, `vscode-extension/src/sidecar.ts` | `EngineCapabilityCoverageTest.java`, `wireCatalog.test.ts` |
| Change PR discovery, metadata, diff, or draft mutations | `github-engine/.../sidecar/pr/` | `GitHubEngine.java`, both host bridges, shared webview messages | Matching `github-engine` service test plus host bridge tests |
| Change the shared review UI | `webview/src/App.tsx`, `webview/src/components/` | `webview/src/bridge/types.ts`, both host bridge handlers | Component tests plus accessibility/visual suites when behavior or layout changes |
| Change IntelliJ host behavior | `intellij-plugin/.../ui/WebviewPanel.java` | `services/`, `settings/`, shared webview bridge | Matching IntelliJ JUnit tests |
| Change VS Code host behavior | `vscode-extension/src/extension.ts` | `sidecar.ts`, `settings.ts`, `settingsView.ts` | `vscode-extension/test/` |
| Change settings | `intellij-plugin/.../settings/PluginSettings.java` | Both settings UIs, `vscode-extension/package.json`, host readers | IntelliJ settings tests and VS Code settings tests |
| Change notifications | `intellij-plugin/.../PRNotificationService.java` | `vscode-extension/src/notifications.ts`, both host lifecycle entry points | Notification tests in both hosts |
| Change local draft/index persistence | `PendingReviewIndex.java`, `DraftRecoveryStore.java`, `SeenPRSet.java`, `vscode-extension/src/draftRecovery.ts` | Both host lifecycle callers; persistence contract in `ARCHITECTURE.md` | Matching IntelliJ and VS Code service tests |
| Change packaging or releases | `.github/workflows/`, module build files | VS Code staging scripts, root Gradle configuration | CI workflow commands and sidecar smoke test |

Paths below omit `src/main/java/com/jinloes/prpilot/` and equivalent test roots where the module
context makes them unambiguous.

## Repository map

### Root and automation

- `README.md` - User setup, development, checks, and release flow.
- `AGENTS.md` - Agent workflow, testing rules, and cross-host obligations.
- `ARCHITECTURE.md` - Stable design constraints, settings persistence, and local data.
- `.github/workflows/ci.yml` - Push/PR checks, Java 17 sidecar smoke test, and packaged-VSIX assertion.
- `.github/workflows/release.yml` - Tag-driven IntelliJ ZIP and VSIX GitHub releases.
- `scripts/portable-process.mjs` and `run-gradle.mjs` - Shell-free npm and Gradle wrapper
  invocation used by portable packaging/tests and targeted host CI.

### `core/`

Plain Java 17 shared models with no host dependencies.

- `model/PullRequest.java` - Immutable PR identity and metadata.
- `model/ReviewResult.java` - Review summary, verdict, and line comments.
- `model/LineComment.java` - Inline comment anchor and quality metadata.
- `model/ChatMessage.java` - Immutable chat role and content.
- `model/PRReviewRequest.java` - Immutable review-generation parameter object.
- `model/ReviewProvider.java` - Claude/Copilot provider enum.
- Tests: `core/src/test/java/com/jinloes/prpilot/`.

### `review-engine/`

Host-neutral provider invocation, prompt construction, review parsing, worktrees, and repository
guidance.

- `engine/ReviewEngineApi.java` - Complete review capability surface and JSON-RPC wire-name map.
- `engine/ReviewSessionService.java` - Provider dispatch and operation-scoped cancellation.
- `review/ClaudeService.java` - Claude CLI execution and canonical review/chat prompts.
- `review/CopilotService.java` - Copilot SDK execution with the same review API.
- `review/ChunkedReviewService.java` - Shared diff batching and mandatory global reconciliation.
- `review/ReviewPipelineService.java` - Shared primary/chunked orchestration, bounded supervision,
  final critique, cancellation checkpoints, fallback behavior, and final CI suppression.
- `review/InspectionManifest.java`, `ReviewPassParser.java`, `InspectionLedger.java`, and
  `EvidenceRef.java` - Stable changed targets plus validated inspection/evidence accounting.
- `review/ReviewCoverageAnalyzer.java`, `CoverageGap.java`, `ReviewSupervisorPrompts.java`, and
  `FollowUpDirective.java` - High-risk gap detection and bounded follow-up selection.
- `review/ReviewAnchorValidator.java` and `ReviewResultMerger.java` - Changed-line filtering and
  baseline/follow-up deduplication.
- `review/CancellationToken.java` - Shared cancellation state.
- `review/BoundedProcessRunner.java` - Bounded subprocess lifecycle and output draining.
- `review/CopilotModelDiscovery.java` - Session-cached Copilot model probing.
- `review/GitWorktreeService.java` - Temporary PR-head worktree lifecycle.
- `review/RepoGuidelinesReader.java` - Bounded repository-guidance discovery.
- `review/BinaryLocator.java` - Provider binary-path probing.
- `review/ProviderSetupProbe.java` - Bounded provider authentication readiness for onboarding.
- `review/stream/` - Jackson DTOs for Claude stream-json events.
- Tests: `review-engine/src/test/java/com/jinloes/prpilot/`.

### `github-engine/`

Host-neutral GitHub, repository detection, PR context, and draft-review behavior. IDE hosts never
receive GitHub tokens.

- `engine/GitHubEngineApi.java` - Complete GitHub capability surface and JSON-RPC wire-name map.
- `engine/GitHubEngine.java` - Composition root delegating to GitHub services.
- `sidecar/github/GitHubAuthService.java` - `gh` and GitHub API authentication checks.
- `sidecar/github/CheckAuthResult.java` - Stable authentication diagnosis.
- `sidecar/pr/PrSearchQueryService.java` - Normalized PR search construction.
- `sidecar/pr/PrListService.java` - Authenticated PR search with retry and truncation handling.
- `sidecar/pr/PrDetailService.java` - PR metadata and worktree-head lookup.
- `sidecar/pr/PrDiffService.java` - Byte-bounded review diff retrieval.
- `sidecar/pr/DraftReviewService.java` - Pending-review lookup and decoding.
- `sidecar/pr/DraftReviewCodec.java` - PR Pilot review metadata encoding/decoding.
- `sidecar/pr/DraftReviewMutationService.java` - Save, submit, and delete orchestration.
- `sidecar/pr/PrSupplementalService.java` - Raw search, starred repositories, and prompt context.
- `sidecar/pr/*Result.java` and DTOs - Token-free engine outcomes.
- `sidecar/repo/RepoDetector.java` - Repository detection orchestration.
- `sidecar/repo/GitDirectoryResolver.java` - Git metadata/worktree resolution.
- `sidecar/repo/GitConfigOriginReader.java` - Origin URL reading.
- `sidecar/repo/RemoteUrlParser.java` - HTTPS/SSH/SCP owner/repository parsing.
- Tests: `github-engine/src/test/java/com/jinloes/prpilot/sidecar/`, mirroring service packages.

### `sidecar/`

Thin Java 17, non-web Spring Boot stdio JSON-RPC adapter used only by VS Code.

- `sidecar/PrPilotSidecarApplication.java` - Process entry point and stdio lifecycle.
- `sidecar/SidecarConfiguration.java` - Engine composition.
- `sidecar/StdioFrameCodec.java` - Bounded Content-Length UTF-8 framing.
- `sidecar/StdioJsonRpcServer.java` - Validation, dispatch, errors, and async notifications.
- `sidecar/SidecarBootstrapService.java` - Initialize response and advertised capability groups.
- `src/main/resources/logback-spring.xml` - Stderr logging; stdout remains protocol-only.
- `EngineCapabilityCoverageTest.java` - Enforces declaration, registration, and advertisement parity.
- Other tests mirror the frame codec and RPC server.

### `intellij-plugin/`

IntelliJ host integration. Depends directly on `core`, `github-engine`, and `review-engine`.

- `services/IntellijGitHubService.java` - IntelliJ-facing GitHub engine adapter.
- `services/IntellijClaudeService.java` - Provider adapter with pooled I/O and EDT callbacks.
- `services/UserFacingErrors.java` - Actionable host error copy.
- `services/PendingReviewIndex.java` - Saved-draft index.
- `services/DraftRecoveryStore.java` - Token-free local recovery snapshots for interrupted draft replacement.
- `services/PendingReviewIndexNotifications.java` - Corrupt-index warning and quarantine action.
- `services/SeenPRSet.java` - Notification deduplication state.
- `services/PRNotificationService.java` - PR polling, source labels, and merge behavior.
- `services/PRNotificationStartup.java` - Notification lifecycle entry point.
- `settings/PluginSettings.java` - Persisted settings model.
- `settings/PluginSettingsComponent.java` - Provider-aware settings UI.
- `settings/PluginSettingsConfigurable.java` - Settings lifecycle integration.
- `settings/GithubBaseUrlValidator.java` - HTTPS GitHub-origin normalization.
- `ui/PRToolWindowFactory.java` - Tool-window entry point.
- `ui/WebviewPanel.java` - JCEF host and Java/webview bridge.
- `ui/HostThemeClassifier.java` - Host theme normalization.
- `ui/ReviewMapper.java` and `WebviewDtos.java` - Core-to-bridge DTO mapping.
- Tests: `intellij-plugin/src/test/java/com/jinloes/prpilot/`, mirroring production packages.

### `webview/`

Shared Vite/React/TypeScript UI used by both IDE hosts.

- `src/App.tsx` - Application state and top-level host workflow.
- `src/bridge/types.ts` - Cross-host message schemas.
- `src/components/` - PR discovery, diff, review, chat, settings-adjacent UI, and reusable controls.
- `src/components/PRList/` - Pull-request discovery, filtering, scope controls, and success coaching.
- `src/components/Setup/` - App-level prerequisite recovery UI and the setup reason/action matrix.
- `src/components/ReviewPane/ReviewPane.tsx` - Review feature composition root and public component API.
- `src/components/ReviewPane/useReviewController.ts` - PR-scoped bridge events, autosave, mutation
  watchdogs, review commands, chat sizing, and the view-model/action contract.
- `src/components/ReviewPane/reviewState.ts` - Pure review lifecycle transitions and state selectors.
- `src/components/ReviewPane/reviewActivity.ts`, `ReviewActivityLog.tsx` - Timestamped review-generation
  lifecycle/tool activity state and its expandable, privacy-safe timeline.
- `src/components/ReviewPane/ReviewOverrides.tsx`, `ReviewQuality.tsx`, `ReviewContent.tsx`,
  `ReviewFooter.tsx`, and `OrphanComments.tsx` - Feature-private review presentation modules.
- `src/lib/reviewQuality.ts` - Quality heuristics and in-memory repair suggestions.
- `src/lib/autosave.ts` - Draft dirty-check, snapshot, and debounce decisions.
- `src/lib/validateComments.ts` - Inline-comment validation.
- `src/lib/keyboard.ts`, `layout.ts`, `motion.ts` - Shared interaction/layout policies.
- `src/components/DiffViewer/fileNavigation.ts` - Changed-file navigation.
- `src/components/ReviewPane/` helpers - Chat sizing, comment navigation, and verify prompts.
- `src/components/a11y/LiveStatus.tsx` - Screen-reader announcements.
- `src/i18n/` - Typed English catalog and test pseudo-localization.
- `src/theme/hostTheme.ts` - Host theme application.
- `a11y/` - Playwright and axe end-to-end accessibility scenarios.
- `visual/` - Deterministic visual-regression scenarios.
- Tests: colocated `*.test.ts`/`*.test.tsx` files.

### `vscode-extension/`

VS Code host integration. All GitHub and review generation routes through the Java sidecar.

- `src/extension.ts` - Activation, commands, webview bridge, and host lifecycle.
- `src/draftRecovery.ts` - Token-free `globalState` snapshots used until GitHub confirms a draft save.
- `src/sidecar.ts` - Mandatory JSON-RPC client and notification dispatch.
- `src/models.ts` - Host-neutral PR/review view models.
- `src/claude.ts` - Claude preflight and remaining mirrored prompt helpers.
- `src/copilot.ts` - Copilot model discovery and binary preflight.
- `src/providerSetup.ts` - Conservative Claude authentication probe classification for onboarding.
- `src/settings.ts` and `settingsView.ts` - Settings controller and pure webview rendering.
- `src/reviewGuidanceProfiles.ts` - Guidance-profile validation and resolution.
- `src/operationCorrelation.ts` - Async selection/generation/chat invalidation.
- `src/notifications.ts` - Notification labeling, deduplication, and merge rules.
- `src/hostTheme.ts` - Theme classification.
- `src/userFacingError.ts` - User-actionable error mapping.
- `src/workspace.ts` - Workspace and development-target resolution.
- `src/webviewAssets.ts` - Packaged assets with development fallback.
- `shared/user-facing-errors.yaml` - Shared host error templates.
- `scripts/stage-webview.mjs` - Packages the built shared webview.
- `scripts/stage-sidecar.mjs` - Packages the sidecar JAR.
- `scripts/smoke-sidecar.mjs` - Verifies initialize protocol and capabilities.
- `test/wireCatalog.test.ts` - Enforces engine declarations against the TypeScript client.
- Other tests in `test/` mirror extension helpers and host behavior.

## Cross-module paths

### Review generation

`ReviewPane`/`App.tsx` -> host bridge -> `ReviewEngineApi` -> `ReviewSessionService` ->
`ReviewPipelineService` -> direct or chunked primary pass -> optional bounded supervisor/follow-up ->
final critique/CI suppression -> status/chunk notifications -> host bridge -> shared webview.

### GitHub operations

Shared webview -> host bridge -> `GitHubEngineApi` -> `GitHubEngine` -> focused service in
`github-engine/sidecar/` -> token-free result -> host bridge -> shared webview.

### VS Code transport

`extension.ts` -> `sidecar.ts` -> stdio framing -> `StdioJsonRpcServer` -> engine API. IntelliJ skips
this adapter and calls the same engine implementations in-process.

### Settings

IntelliJ: `PluginSettingsConfigurable` -> `PluginSettingsComponent` -> `PluginSettings`.

VS Code: configuration contributions in `package.json` -> `settings.ts`/`settingsView.ts` ->
configuration reads in `extension.ts`.
