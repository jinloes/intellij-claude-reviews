# PR Pilot Architecture

IntelliJ and VS Code extension that lists GitHub Pull Requests and generates AI-powered code reviews using a local AI CLI/runtime (Claude Code or GitHub Copilot, selected per-host in settings).

## Scope

- Keep this file focused on stable architecture and design constraints.
- Put workflow/checklist instructions in `AGENTS.md`.
- Put volatile version inventory in build manifests unless a version is itself an architecture constraint.

## Project layout

Multi-module Gradle project:

```
README.md                              – Root guide: setup, development, checks, and release flow

.github/
  workflows/
    ci.yml                           – Push/PR CI checks, Java 17 sidecar smoke test, and packaged-VSIX assertion
    release.yml                      – Tag-driven GitHub release pipeline; builds IntelliJ + VS Code artifacts and marks `v*-rc.*` tags as prereleases

core/                                  – Plain Java 17 JVM module (no longer Kotlin at all — DiffParser.kt was converted to Java once the sidecar removed the last reason to keep any part of this module in Kotlin)
  src/main/java/com/jinloes/prpilot/
    model/
      PullRequest.java                 – Immutable value object (title, number, owner, repo, author, etc.); two constructors (with/without isDraft)
      ReviewResult.java                – Plain mutable class; holds summary, verdict, mutable List<LineComment>
      LineComment.java                 – Plain mutable class; anchor/body plus optional severity, category, confidence, and rationale
      ChatMessage.java                 – Immutable value object; nested Role enum + content for chat history
      PRReviewRequest.java             – Immutable value object; parameter object for ClaudeService.reviewPR, with convenience constructors
      ReviewProvider.java              – enum (CLAUDE | COPILOT); fromId(id) factory
    parser/
      DiffParser.java                  – Unified diff parser; DiffFile / DiffLine (record) types
  src/test/java/com/jinloes/prpilot/parser/DiffParserTest.java – JUnit 5 + AssertJ

review-engine/                         – Plain Java 17 library owning Claude/Copilot CLI invocation, prompt building, and review-JSON parsing; depends on :core for models only
  src/main/java/com/jinloes/prpilot/review/
    ClaudeService.java                – Shells out to `claude --print`; synchronous/blocking API; owns REVIEW_INSTRUCTIONS/CHAT_PERSONA prompt constants
    CopilotService.java               – Uses the official Copilot Java SDK to drive local `copilot`; mirrors ClaudeService API
    CopilotModelDiscovery.java        – Runs `copilot help config` once per session and caches model list
    GitWorktreeService.java           – Creates/removes temporary git worktrees for PR branch reviews
    BinaryLocator.java                – Shared CLI binary-path probing helper for ClaudeService/CopilotService
    stream/                           – Jackson DTOs for Claude's stream-json event protocol
  src/test/java/com/jinloes/prpilot/review/ – JUnit 5 + AssertJ tests mirroring the module's Java classes

intellij-plugin/                       – IntelliJ plugin host; depends on :core, :github-engine, and :review-engine
  src/main/java/com/jinloes/prpilot/
    services/
      IntellijGitHubService.java
      IntellijClaudeService.java       – Adapts review-engine's ClaudeService/CopilotService to IntelliJ threading (pooled I/O, EDT callbacks)
      UserFacingErrors.java           – Maps runtime/network exceptions to actionable UI copy
      PendingReviewIndex.java         – Local JSON index of saved drafts (~/.pr-pilot/pending-prs.json)
      SeenPRSet.java                  – Local JSON set of notified PR IDs (~/.pr-pilot/seen-prs.json)
      PRNotificationService.java
      PRNotificationStartup.java
    settings/
      PluginSettings.java
      PluginSettingsComponent.java
      PluginSettingsConfigurable.java
      GithubBaseUrlValidator.java      – Normalizes settings input to an HTTPS GitHub origin
    ui/
      PRToolWindowFactory.java
      WebviewPanel.java               – Hosts the OSR JCEF browser and Java↔webview bridge in the tool window
      HostThemeClassifier.java       – Pure light/dark/high-contrast theme classification
      ReviewMapper.java              – MapStruct mapper (core model -> webview DTOs)
      WebviewDtos.java               – package-private DTO records serialized to webview bridge

github-engine/                          – Plain Java 17 library containing host-neutral GitHub, PR, repository, and review behavior; no Spring or IDE APIs
  src/main/java/com/jinloes/prpilot/sidecar/
    pr/
      PrSearchQueryService.java          – Pure normalized GitHub PR-search query construction
      PrListService.java                 – Token-safe authenticated GitHub PR search with retry and truncation handling
      PrListResult.java                  – Structured list outcome DTO (status, limit metadata, PRs)
      PullRequestSummary.java            – Token-free PR list item DTO
      PrDetailService.java               – Token-safe authenticated GitHub PR metadata lookup
      PrDetailResult.java                – Structured PR detail outcome DTO
      PrDetail.java                      – Token-free PR metadata and worktree head DTO
      PrDiffService.java                 – Token-safe byte-bounded review diff retrieval
      PrDiffResult.java                  – Structured bounded review diff outcome DTO
      DraftReviewService.java            – Token-safe pending-review lookup and decoding orchestration
      DraftReviewResult.java             – Structured token-free pending-review outcome DTO
      DraftReviewCodec.java              – Encodes/decodes PR Pilot review metadata tags, falling back to raw GitHub inline comments
      DraftReviewMutationService.java    – Token-safe pending-review save/submit/delete orchestration (incl. body-first 422 fallback)
      DraftReviewMutationResult.java     – Structured token-free save/submit/delete outcome DTO
      PrSupplementalService.java         – Token-safe raw PR search, starred-repository lookup, and existing-review prompt context
      PrSearchResult.java                – Structured bounded arbitrary-search outcome
      StarredReposResult.java            – Structured bounded starred-repository outcome
      ExistingReviewsResult.java         – Structured existing-review summary outcome
    github/
      GitHubAuthService.java              – Token-safe gh CLI and GitHub API authentication verification
      CheckAuthResult.java                – Stable authentication diagnosis DTO
    repo/
      RepoDetector.java                  – Orchestrates git metadata discovery + origin parsing into owner/repo
      GitDirectoryResolver.java          – Resolves the git metadata dir; understands linked-worktree `.git` files
      GitConfigOriginReader.java         – Reads the `[remote "origin"]` url from `config`
      RemoteUrlParser.java               – Strict HTTPS/SSH-URI/SCP url -> owner/repo parsing
      RepositoryId.java                  – owner/repo DTO
      DetectResult.java                  – Typed detect() outcome (status + optional RepositoryId)
      DetectStatus.java                  – Non-fatal detection outcomes (found, not_git, origin_missing, etc.)
  src/test/java/com/jinloes/prpilot/sidecar/
    pr/PrSearchQueryServiceTest.java
    pr/PrListServiceTest.java
    pr/PrDetailServiceTest.java
    github/GitHubAuthServiceTest.java
    pr/DraftReviewCodecTest.java
    pr/DraftReviewServiceTest.java
    pr/DraftReviewMutationServiceTest.java
    repo/RepoDetectorTest.java
    repo/RemoteUrlParserTest.java

sidecar/                                – Java 17, non-web Spring Boot/stdio JSON-RPC adapter over :github-engine and :review-engine; initialized during VS Code activation
  src/main/java/com/jinloes/prpilot/sidecar/
    PrPilotSidecarApplication.java      – Non-web Boot entry point; owns stdio process lifecycle
    SidecarConfiguration.java           – Explicit Spring bean composition root
    StdioFrameCodec.java                – Bounded Content-Length UTF-8 framing for JSON-RPC over stdio
    StdioJsonRpcServer.java             – JSON-RPC parameter validation, dispatch, structured protocol errors, and async review/chat request handling plus notification push
    SidecarBootstrapService.java        – Sidecar initialization capability response
    review/
      ReviewSessionService.java         – Orchestrates review-engine's ClaudeService/CopilotService on behalf of the JSON-RPC `reviews/*` methods
  src/main/resources/
    logback-spring.xml                  – Sends all sidecar logs to stderr; stdout is protocol-only
  src/test/java/com/jinloes/prpilot/sidecar/
    StdioFrameCodecTest.java
    StdioJsonRpcServerTest.java

webview/                               – Vite + React + TypeScript webview
  a11y/
    app.a11y.spec.ts                 – Full-page Playwright + axe checks for setup, discovery, review, and submit states
    hostFixture.ts                   – Deterministic host bridge fixture shared by browser-level tests
  visual/
    app.visual.spec.ts               – Deterministic visual scenarios, including narrow pseudo-localized layout
  playwright.a11y.config.ts          – Dedicated Playwright config for webview accessibility gate
  playwright.visual.config.ts        – Dedicated Chromium visual-regression configuration
  src/
    bridge/types.ts
    components/a11y/LiveStatus.tsx   – Reusable screen-reader status announcements
    components/DiffViewer/fileNavigation.ts – Pure changed-file tree + active-file tracking helpers for diff navigation
    components/layout/AccessibleResizer.tsx – Pointer and keyboard-accessible pane separator
    components/ReviewPane/chatHeight.ts – Validates persisted chat-panel heights against its usable layout range
    components/ReviewPane/verifyPrompt.ts – Builds focused Verify-with-AI prompts from comment metadata plus the nearest diff excerpt
    i18n/                            – Typed English catalog plus test-only pseudo-localization
    theme/hostTheme.ts               – Applies host light/dark/high-contrast state to the document
    lib/keyboard.ts                  – Editable-safe global keyboard shortcut helpers
    lib/layout.ts                    – Responsive pane-size bounds
    lib/motion.ts                    – Reduced-motion-aware scrolling behavior
    lib/validateComments.ts
    lib/reviewQuality.ts            – Review Quality Check heuristics, repair helpers, and diff chunk planning
    lib/autosave.ts                 – Pure draft-autosave decisions (dirty check, snapshot, debounce delay)
    components/...
    App.tsx

vscode-extension/                      – VS Code extension host
  src/
    extension.ts                       – VS Code activation plus PR Pilot webview tab/view bridge
    webviewAssets.ts                   – Resolves packaged `webview-dist/` assets with a dev fallback to sibling `webview/dist`
    models.ts                          – Host-neutral PR/review view models; contains no GitHub transport
    claude.ts                          – Prompt-building helpers (buildPrompt/buildChatPrompt/buildFocusedChatPrompt) and `claude` binary preflight; review/chat generation itself routes through sidecar.ts
    copilot.ts                         – Copilot model discovery (`listModels`, still spawns the SDK locally — no sidecar RPC endpoint yet) and `copilot` binary preflight; review/chat generation itself routes through sidecar.ts
    worktree.ts                        – Creates/removes temporary git worktrees for PR branch reviews (mirrors GitWorktreeService.kt)
    settings.ts                        – Settings webview controller (panel lifecycle + config read/write); mirrors PluginSettingsConfigurable
    settingsView.ts                    – Pure settings-webview view logic (HTML, model-merge, escaping); no vscode import, unit-tested
    hostTheme.ts                       – Pure light/dark/high-contrast theme classification
    notifications.ts                   – Pure background-notification helpers (source labeling, dedupe/merge with review-requested precedence); no vscode import, unit-tested
    userFacingError.ts                 – Maps host/provider errors to user-actionable copy
    workspace.ts                       – Resolves the VS Code workspace dir, including dev-host target repo override
    sidecar.ts                         – Mandatory JSON-RPC client for the Java GitHub engine process
  shared/
    user-facing-errors.yaml            – Shared message templates consumed by both hosts
  scripts/
    stage-webview.mjs                  – Copies built webview/dist into vscode-extension/webview-dist for packaging
    stage-sidecar.mjs                  – Copies the built sidecar/build/libs/pr-pilot-sidecar.jar into vscode-extension/sidecar for packaging
    smoke-sidecar.mjs                  – Launches the staged jar and verifies the initialize protocol/capabilities
  test/
    claude.test.ts
    copilot.test.ts
    sidecar.test.ts
    userFacingError.test.ts
```

## Key design decisions

Only decisions that encode active constraints future code must respect and are not obvious from source.

### Webview styling

All webview UI uses shadcn/ui + Tailwind CSS. Avoid ad-hoc CSS modules/inline layout styles. `DiffViewer.css` is the only hand-crafted CSS exception for diff-table specifics. Use semantic status tokens (`text-status-*`, `bg-status-*/10`, `border-status-*/50`) rather than hardcoded palette classes. Theme colors are semantic CSS variables selected by host-provided `light`, `dark`, `highContrastLight`, or `highContrastDark` bridge state; do not infer the IDE theme from browser media queries alone.

The shared webview switches from split panes to explicit list/review navigation below 640px. Pane separators must remain operable by pointer and keyboard, keep ARIA values within viewport-derived bounds, and honor reduced-motion preferences. The application shell is pinned to the browser viewport with `overflow: clip`, and pane contents grow with `flex: 1` plus `min-height: 0`; do not replace this with a nested `height: 100%` chain because IntelliJ's JCEF Chromium can resolve a flex-stretched parent's percentage-height child to content height. Descendant auto-scroll must update its local scroll container directly rather than call `scrollIntoView`, because `overflow: hidden` ancestors remain programmatically scrollable and JCEF can translate the fixed shell off-screen.

The IntelliJ host uses JCEF off-screen rendering (OSR) and attaches its layout-managed component directly to the tool-window content. Do not replace this with heavyweight native rendering or call `CefBrowser.wasResized` during ordinary Swing layout: on macOS, the native child window can retain an intermediate height and clip the webview. JCEF OSR can also retain stale pixels after DOM elements move without changing the browser viewport; dynamic webview geometry changes must send `webviewLayoutChanged` so IntelliJ can debounce a targeted `CefBrowser.invalidate` after React commits the layout. VS Code validates and consumes the same parity message but needs no host repaint.

### Webview accessibility tooling
Webview development runs dev-only runtime accessibility scans via `@axe-core/react` in `main.tsx` so missing labels/roles and semantic issues surface early in local runs. ESLint also applies `eslint-plugin-jsx-a11y` rules.

CI runs full-page Playwright + axe scenarios (`npm run test:a11y`) using `playwright.a11y.config.ts` and fails on any reported violation. Deterministic screenshot scenarios use `playwright.visual.config.ts`; pseudo-localization is test-only and enabled with `?locale=pseudo` to expose narrow-layout overflow. Visual snapshots are platform-sensitive and remain a manual verification suite unless CI and canonical baselines are updated together.

### Webview draft mutation watchdog
`ReviewPane`'s `saveDraft`/`submitReview`/`deleteDraft` round trip (webview → host → GitHub → host → webview) has no host-side timeout of its own — a dropped bridge message, a wedged host-side mutation queue, or a crashed sidecar process leaves the corresponding `saving`/`submitting`/`deleting` spinner state stuck forever with no recovery path, since the webview only clears those flags in response to a message it may never receive. `ReviewPane` arms a client-side watchdog (`MUTATION_WATCHDOG_MS`, 45s) whenever it dispatches one of these three requests and clears it when the matching success/error response arrives; if the watchdog fires first, it synthesizes a `saveError`/`submitError`/generic `error` state so the user can retry instead of the UI hanging indefinitely. Do not raise this window to match `REVIEW_REQUEST_TIMEOUT_MS`/`reviewExecutor` — GitHub draft mutations are simple bounded REST calls (see `DraftReviewMutationService`'s own 15s-per-attempt/3-attempt bound), unlike AI review generation which can legitimately run for minutes.

### Chat pane structured verify/fix results
The "Verify" and "Suggest fix" per-comment actions (`buildVerifyCommentPrompt`/`buildExampleFixPrompt` in `ReviewPane/verifyPrompt.ts`) instruct the model to return only a bare JSON object (no prose) matching one of two fixed schemas. `ChatPane` treats every assistant reply as potentially structured: `structuredResult.ts`'s `parseStructuredResult` tries to parse the content (tolerating a stray ```` ```json ```` fence some models add despite instructions) against the verify schema (`verdict`/`why`/`action`/`replacementComment`) and the example-fix schema (`approach`/`examplePatch`/`why`/`risks`/`testUpdates`/`missingContext`); a match renders a dedicated card (verdict badge, why, suggested replacement, etc.) instead of the raw JSON string being passed through the Markdown renderer, which previously showed the reviewer a wall of escaped-quote JSON text. Ordinary free-form chat replies simply fail both schema checks and fall back to the normal Markdown bubble. `parseStructuredResult` is applied both to finalized `chatResponse` messages and to the live `streaming` buffer (accumulated from `chatChunk` notifications) — without the latter, a completed structured JSON reply would render as raw escaped JSON text (with the streaming cursor still attached) for the entire duration the chunks are arriving, since the buffer only becomes valid JSON once the last chunk lands. If either prompt's JSON schema changes, `structuredResult.ts`'s parser and field rendering must be updated in lockstep.

### Module boundaries
`core` is a plain Java 17 JVM module (Kotlin Multiplatform was removed once the `js` target and every shared model class had no reason to stay Kotlin — the review-engine extraction moved the JS target's last real consumers to the sidecar, and none of `PullRequest`/`ReviewResult`/`LineComment`/`ChatMessage`/`PRReviewRequest`/`ReviewProvider` ever exercised kotlinx.serialization's JSON encode/decode at runtime, so they were converted to plain Java classes with JavaBean-style getters/setters matching their prior Kotlin-generated method names exactly — no downstream call sites changed). `DiffParser.kt` was later converted to Java (`DiffParser.java`) too: the sidecar (Java) had by then replaced the old Kotlin/JS multiplatform target as VS Code's integration path, so there was no remaining Java-vs-Kotlin interop reason to keep it Kotlin, removing the last Kotlin file from the module. `core` has zero IntelliJ dependencies. `intellij-plugin` also owns two IntelliJ-only local-file-index services (`PendingReviewIndex`, `SeenPRSet`, plain Java records/classes using Jackson) rather than `core`, since they are not shared with VS Code — the VS Code equivalent (`globalState`, see Notification parity below) is a different persistence mechanism entirely, so there is no cross-host code-sharing benefit to keeping them in `core`. `github-engine` is a plain Java 17 library with no Spring or IDE APIs; both `intellij-plugin` and `sidecar` depend on it. `review-engine` is a plain Java 17 library owning Claude/Copilot CLI invocation, prompt building, and review-JSON parsing (`ClaudeService`, `CopilotService`, `CopilotModelDiscovery`, `GitWorktreeService`); both `intellij-plugin` and `sidecar` depend on it, so AI review generation has exactly one JVM implementation rather than being duplicated per host. `review-engine` depends on `core` only for shared models. `intellij-plugin` also depends on `core` and no longer needs `jackson-module-kotlin`/`KotlinModule` now that `core`'s models are plain Java.

`sidecar` is a Java 17 Spring Boot application configured with `WebApplicationType.NONE`; it is not an HTTP server. Its protocol uses bounded `Content-Length`-framed UTF-8 JSON-RPC over standard input/output. Standard output must contain protocol frames only—diagnostics belong on standard error. GitHub, PR, repository, and review behavior belongs in `github-engine`/`review-engine`; Spring remains only the sidecar composition and lifecycle layer.

### Sidecar streaming: async requests plus server-push notifications
The base JSON-RPC loop in `StdioJsonRpcServer.run()` is still one blocking read → dispatch → optional single write per iteration, but `reviews/generate` and `reviews/chat` are the two methods that deviate: `generateReview`/`chatReview` submit the actual review-engine call to a dedicated `reviewExecutor` (a single-thread pool) and return `null` immediately, so the read loop stays free to accept a `reviews/cancel` request (or any other RPC) while a review is in flight. The eventual result is written asynchronously from the background thread once the provider CLI completes. Progress is reported via `reviews/status`/`reviews/chunk`/`reviews/chatChunk` — JSON-RPC notifications (no `id` field) carrying a `requestId` field that correlates them back to the originating request, sent from the background thread under the same `writeLock` used by the main loop so frames never interleave. `ReviewSessionService` tracks at most one active `ClaudeService`/`CopilotService` per sidecar process (mirroring IntelliJ's "only one has an active process at any time"), so `reviews/cancel` is synchronous and side-effect-free even with no active request.

On the VS Code side, `SidecarClient` correlates notifications to their request via a `notificationHandlers` map keyed by the same numeric `id` used for the request/response pending map; `requestRaw` accepts an optional per-call timeout (`REVIEW_REQUEST_TIMEOUT_MS`, 35 minutes, since a real review/chat CLI invocation can legitimately run far longer than the default 60s RPC timeout) and optional notification handlers, cleaned up on both success and failure paths.

The engine's repository detector reads local git metadata directly (no git process spawned) to resolve the owner/repo for a directory. `GitDirectoryResolver` understands linked-worktree `.git` files (a regular file containing `gitdir: <path>`, relative or absolute) in addition to a standard `.git` directory. `RemoteUrlParser` requires exactly two non-blank path segments (owner and repo), including for SCP-style URLs. Every non-`found` outcome (`not_git`, `config_missing`, `origin_missing`, `origin_url_malformed`, `gitdir_malformed`, `gitdir_unreadable`, etc.) is a typed, non-fatal `DetectStatus`; over JSON-RPC these are normal results, never protocol errors. `-32602` is reserved for malformed RPC params (missing/non-string `path`).

The sidecar's `github/checkAuth` capability validates an HTTPS GitHub origin, runs `gh auth token` with the matching Enterprise hostname when needed, and verifies the resulting token with the host's `/user` API. It returns token-free structured statuses (`authenticated`, `not_installed`, `not_authenticated`, `api_failed`, or `invalid_base_url`) as normal domain results; never log, serialize, or include the token in an error message. Invalid JSON-RPC request parameters remain protocol errors.

The sidecar's `prs/list` capability owns its `gh auth token` lookup and GitHub `/search/issues` call; hosts never pass a token over JSON-RPC. It uses the canonical `PrSearchQueryService` rules, fetches 51 rows but returns at most 50 with an explicit `limited` flag, and returns token-free domain statuses (`not_installed`, `not_authenticated`, `invalid_base_url`, `rate_limited`, `network_error`, or `api_failed`) rather than JSON-RPC errors. JSON-RPC errors remain reserved for malformed parameters and protocol failures.

The sidecar's `prs/getDetail` capability owns its `gh auth token` lookup and GitHub pull-request metadata request. It validates owner/repository path segments, maps malformed GitHub JSON to `api_failed`, and returns only title/body, merged status, and nullable head/base repository metadata needed for fork-aware worktrees. No token, HTTP response body, or raw exception becomes protocol output.

The sidecar's `prs/getDiff` supports both review mode (250,000-byte limit with a visible truncation marker) and validation mode (1,000,000-byte limit without a marker, used for inline-comment position validation and draft anchoring). The stdio JSON-RPC frame ceiling is 8 MiB in both Java and TypeScript so an escaped validation diff remains bounded while fitting safely in a response.

The sidecar's `prs/getDraftReview` capability owns its `gh auth token` lookup and the GitHub pending-review lookup (`GET .../pulls/{number}/reviews` filtered to `state == PENDING`, plus that review's inline comments). `DraftReviewCodec` decodes the PR Pilot `claude-verdict`/`claude-summary`/`claude-comments` HTML-comment tags embedded in the review body, falling back to raw GitHub inline comments (with an `importedFromGitHub` flag) when those tags are absent or malformed. `none` (no pending review exists) is a normal domain result, not a failure; other token-free domain statuses (`invalid_request`, `invalid_base_url`, `not_installed`, `not_authenticated`, `rate_limited`, `network_error`, `api_failed`) are returned the same way `prs/getDetail` does. No token is ever logged, serialized, or included in an error message.

The sidecar's `prs/saveDraftReview`, `prs/submitReview`, and `prs/deleteDraftReview` capabilities expose `DraftReviewMutationService` over JSON-RPC. `save` deletes any existing pending review first (non-fatal on failure), fetches the PR's head SHA, and POSTs a new pending review; on a GitHub `422` (one or more inline comments reference an invalid path/line) it falls back to creating the review body-only, then POSTs each comment individually so only the bad ones are dropped, appending a "Comments not attached inline" section to the body via `DraftReviewCodec.buildDroppedSection` when any are dropped. `DraftReviewCodec` owns the stable metadata tag format and short JSON keys used by both hosts. `submit` posts to `.../reviews/{id}/events` with a default body substituted when blank (GitHub 422s on an empty `REQUEST_CHANGES`/`COMMENT` body). `delete` removes a pending review. All three share the token-free status vocabulary (`invalid_request`, `invalid_base_url`, `not_installed`, `not_authenticated`, `rate_limited`, `network_error`, `api_failed`) plus `ok`. The host-side mutation-serialization guard (`state.mutationQueue`/`enqueueMutation` in `extension.ts`) is UI-state sequencing local to each host, not a GitHub API concern.

`PrSupplementalService` supplies the remaining token-safe read paths needed to remove host-local GitHub HTTP: bounded arbitrary PR searches (`prs/search`, query at most 8 KiB and limit at most 100), up to 200 starred repositories (`repos/listStarred`), and formatted submitted-review context (`prs/getExistingReviews`). Individual inline-comment lookup failures do not discard otherwise usable submitted-review context.

### GitHub engine host wiring
GitHub behavior has one implementation in `github-engine`. IntelliJ calls its services directly in-process through `IntellijGitHubService`; the VS Code extension calls the same services through the sidecar's bounded Content-Length-framed JSON-RPC protocol. Hosts never receive, cache, or pass GitHub tokens. Authentication, retries, URL/API normalization, PR queries, repository detection, diffs, review context, and draft mutations belong only in `github-engine`.

For VS Code, `extension.ts` owns one process-wide `SidecarClient` created and initialized in `activate()` and disposed during extension teardown. Initialization verifies protocol version 1 and every required GitHub capability before requests proceed. The sidecar is mandatory for GitHub operations: missing Java 17+, a missing/corrupt jar, process failure, timeout, incompatible capabilities, or malformed protocol output is surfaced as a setup/runtime error and must never activate a TypeScript GitHub fallback. RPC requests have a 60-second bound for GitHub network operations. A user-facing **Retry** action restarts the stopped sidecar, clears only transport state, and re-runs initialization/capability validation; it never falls back to host-local GitHub behavior. `none` remains a valid `prs/getDraftReview` domain result; other token-free domain statuses flow into the existing setup/error UI.

`resolveSidecarJarPath` mirrors `resolveWebviewDistPath`'s dev/packaged fallback: it prefers a jar staged at `vscode-extension/sidecar/pr-pilot-sidecar.jar` (produced by `scripts/stage-sidecar.mjs` from the Gradle `:sidecar:bootJar` output, matching the webview's `stage-webview.mjs` pattern) and falls back to the sibling `sidecar/build/libs/pr-pilot-sidecar.jar` during local development. The release pipeline runs `:sidecar:bootJar` before packaging so shipped `.vsix` builds bundle the jar.

IntelliJ intentionally does not spawn the sidecar process: the plugin already runs in the same JVM and calls `github-engine` directly. This is a container difference, not a behavior fork.

### core: plain Java module
`core`'s shared models (`model/*.java`) and `DiffParser` (`parser/DiffParser.java`) are plain Java classes with JavaBean getters/setters (e.g. `getOwner()`, `isDraft()`) — no Kotlin-Java interop shims (`@JvmOverloads`, `@JvmStatic`, `KotlinModule`) are needed anywhere downstream, and the module has no Kotlin plugin/dependency at all.

### Threading model
`ClaudeService`/`CopilotService` (in `review-engine`) are synchronous services. IntelliJ's `IntellijClaudeService` adapter owns threading (pooled thread for I/O, EDT for UI callbacks); the sidecar's `StdioJsonRpcServer` owns threading for VS Code (single-thread `reviewExecutor` per the streaming design above, JSON-RPC notifications instead of a UI-toolkit callback).

### Provider toggle and prompt sharing
Copilot and Claude share prompt builders/parsing (`ClaudeService` static helpers in `review-engine`). Do not fork prompt constants by provider unless absolutely required. Full-review and regular-chat prompts are built server-side (inside `ClaudeService`/`CopilotService`) from raw PR/diff/context fields; only the small focused-chat prompt (`buildFocusedChatPrompt`) is still built by the caller (`IntellijClaudeService.chatFocused` on IntelliJ, `extension.ts`'s `handleAskClaude` using `claude.ts`'s copy on VS Code) before being sent as a raw prompt, matching how a focused question carries no PR metadata or history.

### Copilot SDK runtime
Both the IntelliJ plugin and the sidecar use the official Java Copilot SDK (`com.github:copilot-sdk-java`, wrapped by `review-engine`'s `CopilotService`) to control local `copilot`. Stream `assistant.message_delta` to text chunks, surface `tool.execution_start` names as status, and parse final `assistant.message` JSON with delta fallback. VS Code's `copilot.ts` additionally uses the TypeScript SDK (`@github/copilot-sdk`) directly, but only for model discovery (`listModels`) — there is no sidecar RPC endpoint for model discovery yet, so that one path still spawns the CLI in the extension process.


### Provider capability isolation
Claude review/chat processes disable tools, use `permission-mode=dontAsk`, pass a strict empty MCP configuration, and read user settings only. The review prompt therefore embeds the bounded GitHub diff instead of asking the CLI to fetch repository data. Temporary stream output is owner-only and deleted in `finally`; raw model output is not retained in logs.

Copilot review/chat sessions reject all SDK permission requests by default and disable config discovery. `copilotInheritMcp` is an explicit capability elevation: when enabled, config discovery loads MCP servers from the Copilot CLI config and repo-local `.mcp.json`, but the permission handler approves only MCP requests once and continues rejecting shell/write capabilities. The optional `copilotConfigDir` setting maps to `configDir`/`setConfigDirectory` for non-default Copilot homes. Never replace the explicit permission handler with blanket approval.

### Reasoning effort normalization
Persisted values are `none|low|medium|high|xhigh|max`; SDK accepts `low|medium|high|xhigh`. Normalize before session creation: `none -> low`, `max -> xhigh`, blank/unknown -> `medium`.

### Review JSON parsing is self-healing, not all-or-nothing
`ClaudeService.parseReview` (in `review-engine`, shared by both the Claude CLI and Copilot SDK paths on both hosts — IntelliJ calls it in-process, VS Code via the sidecar's `reviews/generate`) tolerates and repairs common model schema deviations instead of rejecting the entire review, which previously surfaced as "The model returned an invalid review format" for otherwise-good output. Unknown top-level/comment fields are ignored; an over-long `summary` is truncated to 800 chars; a comment `body` with embedded newlines is collapsed to one line and truncated to 300 chars; a low-confidence `"issue"` is downgraded to `"suggestion"` rather than failing the review; the final `verdict` is derived from (and corrected to match) the surviving comments rather than trusting the model's stated verdict. Only individually-malformed line comments (missing/blank required field, invalid enum value) are dropped — one bad comment no longer discards the other 19. A hard parse failure remains only for genuinely non-JSON output or a missing `summary`/non-object root, since there is nothing to salvage in that case.

### Copilot model discovery
Both hosts discover the available Copilot model list at runtime and cache it for the session, falling back to a short hardcoded suggestion list on probe failure.

- **IntelliJ**: `CopilotModelDiscovery` runs `copilot help config` and parses the `` `model`: `` section; `PluginSettingsComponent` merges the result into the (editable) model combo in Settings → Tools → PR Pilot.
- **VS Code**: `copilot.ts` `listModels()` queries the SDK's `client.listModels()` directly (no CLI parsing) and `filterModelIds()` drops policy-`disabled`/blank IDs. The native Settings entries use static dropdowns for the supported Claude and Copilot fallback models. The **settings webview** (`settings.ts` + `settingsView.ts`) — opened via the gear in the PR Pilot view title or the `pr-pilot.openSettings` command — shows a live model dropdown and hides the non-active provider's model field. VS Code's declarative settings JSON cannot self-populate an enum or conditionally hide fields, which is why the webview is used for the account-specific picker. The underlying settings (`reviewProvider`, `reviewModel`, `reviewModelCopilot`, `reviewEffort`, `githubBaseUrl`, `copilotInheritMcp`, `copilotAutoEnableMcpOnReview`, `copilotConfigDir`) remain configurable in native Settings.
  The settings webview validates `githubBaseUrl` before saving, posts per-field saved/error feedback, exposes model-refresh status, and has a `Test` action that verifies `gh` authentication for the configured host.

### PR discovery scope
The shared PR list sends an explicit `searchScope` on `refreshPRs`: `currentRepo`, `reviewRequested`, `assigned`, or `authored`. `PrSearchQueryService` in `github-engine` builds the query for both hosts, and hosts return `listStatus` (`searchScope`, `currentRepo`, `resultLimit`, `limited`) with `prListLoaded` so the webview can explain what was searched and when additional PRs are hidden. To distinguish "exactly the limit" from "more exist", the search over-fetches one row beyond the display limit (`resultLimit` = 50, fetch 51): `limited` is true only when more than 50 match, and the list is sliced back to 50. `currentRepo` searches only the engine-detected repository; if no repo is detected it falls back to `author:@me`. Main-list discovery intentionally includes GitHub draft pull requests (the old `draft:false` filter was removed) so authored WIP PRs are discoverable; the shared PR DTO distinguishes `isDraft` (GitHub PR draft / `PR-DRAFT`) from `hasReviewDraft` (saved PR Pilot review draft / `REV-DRAFT`). Starred repositories are used only by optional notification polling, not by the main list's current-repo scope.

### Binary resolution
Probe known hard-coded paths for `gh`, `claude`, and `copilot` before falling back to command name, because GUI-launched IntelliJ often has incomplete `PATH`. `review-engine`'s `BinaryLocator` is the single JVM implementation of this probing logic (used by both `ClaudeService` and `CopilotService`, for both IntelliJ and the sidecar); `claude.ts`/`copilot.ts` keep an independent TypeScript copy for the same probing since the sidecar (Java) and the extension process (Node) are different runtimes — the sidecar is the one that actually spawns the CLI for VS Code, but the extension still preflight-checks binary availability locally before even asking the sidecar to start a review (see Provider preflight below). JVM provider subprocesses must prepend `~/.local/bin`, `~/.npm-global/bin`, and `~/.volta/bin` as well as Homebrew/local-bin paths: a resolved Node-backed CLI wrapper otherwise cannot find its runtime when Toolbox launches the IDE without shell initialization.

### Provider preflight
Before running a review, both hosts check that the configured provider's CLI (`claude`/`copilot`) is resolvable — a hard-coded candidate path exists, or the binary is found on `PATH` — and, if not, push a `reviewError` with actionable `provider_not_installed` copy instead of attempting a doomed spawn. This surfaces in-context in the review pane (which already offers a "Try Again") rather than as the full-pane PR-list setup screen. The selected-PR no-draft state also receives a `providerReadiness` bridge payload so the review pane can show `Claude ready` / `Copilot ready` (or setup-needed) status before the user presses Generate. Availability checks: `ClaudeService.isBinaryAvailable()`/`CopilotService.isBinaryAvailable()` (JVM, backed by `BinaryLocator` in `review-engine`); `claudeBinaryAvailable()`/`copilotBinaryAvailable()` + shared `existsOnPath` (`claude.ts`/`copilot.ts`) as a local VS Code preflight even though the actual CLI spawn happens in the sidecar process. The shared `provider_not_installed` template wording is kept in sync across `UserFacingErrors.java` and `userFacingError.ts`.

### Prompt-injection hardening
When wrapping untrusted payloads in XML-like tags, escape matching closing tags inside payload to prevent tag breakout. Repository guidelines, PR metadata, descriptions, diffs, prior reviews, and chat context remain untrusted reference data even when tag escaping is applied; the latest `<user_message>` is the authorized request but cannot override persona or confidentiality constraints. Chat builders retain only ten history turns and bound each turn, PR context, focused context, and current request before provider execution. Provider capability isolation is the primary security boundary.

Review prompts permit only evidence supplied in the prompt and require a fixed JSON contract. Both provider parsers reject unknown fields, incomplete line comments, invalid enums, oversized values, low-confidence issues, and verdict/comment mismatches before review data reaches the UI. Verify-comment and example-fix prompts use tagged reference data and strict JSON response shapes for the same reason.

### Diff acquisition model
Hosts fetch and bound the GitHub diff before provider execution and embed it in `<pr_diff>`. Review providers run without repository tools by default, so they cannot fetch additional context during review. The separate full validation diff is retained host-side/webview-side for comment anchoring and is not sent to the provider. In VS Code, that validation diff is capped at 1 MB to match the webview bridge validator; a larger payload is rejected before the review pane receives `draftLoaded` or `reviewResult`. It is fetched only after the draft-status response so a slow large-diff download cannot leave the review pane stuck in draft loading.

### Worktree-based PR context
When the PR's repo matches the open project/workspace and a git root is found, both hosts create a temporary git worktree checked out to the PR branch and reuse it for both review and chat. This gives the model accurate local file context (correct branch state) for type lookups and cross-file references across the full PR session. Cleanup runs when the active PR changes or the view is disposed. Falls back silently to the open project/workspace dir if worktree creation fails or the PR is from an unrelated repo. Fork PRs use `git fetch <clone_url> <branch>` + `FETCH_HEAD`.

- **IntelliJ**: `WebviewPanel.resolvePrClaudeService` builds a per-PR `IntellijClaudeService` pointed at the worktree, using `review-engine`'s `GitWorktreeService`.
- **VS Code**: `extension.ts` `resolveWorkingDir`/`clearWorktree` resolve the per-view worktree dir using `worktree.ts` and pass it as `projectDir` to the sidecar's `reviews/generate`/`reviews/chat`, which in turn hands it to `review-engine`'s `ClaudeService`/`CopilotService` as the process working directory. Chat reuses an existing worktree and never requests GitHub credentials directly.

### Cross-host parity
When host-specific logic changes in IntelliJ or VS Code, update the paired implementation in the other host. The mapping table and enforcement workflow live in `AGENTS.md`.

### User-facing error copy
Do not surface raw provider/HTTP exception strings directly to users in review/draft/chat flows. Both hosts map low-level errors to actionable guidance (`UserFacingErrors` in IntelliJ, `userFacingError.ts` in VS Code) to keep messaging consistent across providers. Provider errors must be mapped exactly once from the original exception; remapping rendered copy destroys the failure classification. Message strings live in shared YAML templates (`vscode-extension/shared/user-facing-errors.yaml`) and support `{placeholder}` substitution.

### Bridge payload validation
Bridge messages carry protocol version `1`. Both hosts validate webview-to-host messages before dispatching handlers (`BridgeMessageValidator` in IntelliJ, `bridgeValidation.ts` in VS Code), including PR identity, enums, nested review/comment shapes, booleans, collection sizes, and text bounds. The webview validates every host-to-webview payload in `bridge/validation.ts` before fan-out. Unknown versions/types and malformed nested payloads are rejected instead of reaching business logic. Optional wire fields are omitted rather than serialized as `null`; IntelliJ serializes bridge comments through strict MapStruct DTO mapping and omits absent rich metadata rather than emitting invalid empty enum values.

### GitHub API resilience policy
Both hosts apply a transient-failure policy on GitHub REST calls: 15s request/connect/socket timeout, retries on `429`/`5xx`, and retry of timeout-style transport errors. This keeps PR loading/review flows resilient to short-lived network or GitHub edge failures while preserving fast-fail behavior for permanent `4xx` errors.

### First-run onboarding path
When startup PR loading fails, hosts push a `setupRequired` bridge message with actionable detail instead of silently failing. Supported reasons are `gh_not_installed`, `gh_not_authenticated`, and `load_failed` (non-auth load errors). `PRList` renders a full-pane setup/error screen with a checklist covering GitHub CLI installation, GitHub authentication, and PR loading, plus a Refresh button when this message is received. Shared-engine domain messages and each host's error mapper preserve actionable authentication guidance; VS Code additionally classifies setup-worthy auth failures in `classifySetupAuthError`. The VS Code host also triggers an initial `handleRefreshPRs` call immediately after `resolveWebviewView` so the webview never hangs on its initial loading state.

The setup screen is a guided in-app wizard with host detection. Both hosts expose status re-check, settings, and auth-guide actions; VS Code additionally supports one-click `gh auth login` automation via a `runAuthLogin` bridge action that opens an integrated terminal and runs the command.
After the first successful load (or a recovery from `setupRequired`), the shared PR list shows a one-time success coach banner that points users at scope switching and the `PR-DRAFT` vs `REV-DRAFT` mental model.

### PR chat scope
Chat is available after PR selection, before and after review generation. Hosts build chat context from the active PR title/body, the full diff (already capped at 250 KB by the diff fetch), and the generated review when one exists; both hosts send the same full diff so chat answers do not diverge by host. The webview displays which context buckets are attached and adds selected text when the user right-clicks or verifies a comment. Chat reuses the PR worktree when available. The VS Code host sources the active PR's title/body from `getPRDetail` on select (the webview `selectPR` message carries only number/owner/repo), so the review prompt and chat context always include the real PR description.

### DTO mapping in IntelliJ webview bridge
`WebviewPanel` model-to-DTO conversion uses MapStruct (`ReviewMapper`) instead of hand-rolled mappers so field drift fails at compile time.

### Webview bridge PR correlation
PR-scoped lifecycle messages (`draftLoaded`, review generation/chunks/results/errors, draft save/submit/delete, and chat responses) carry a `prKey` of `owner/repo#number`. The React webview drops keyed messages that do not match the active PR so late async results from a previously selected PR cannot repaint or submit against the current PR. When adding a new PR-scoped host message, include the same `prKey` in both hosts. Host-driven selection changes that originate outside the list (for example, background notifications) use a separate `activatePR` message carrying the full PR DTO so the app can honor its unsaved-review confirmation flow before sending the normal `selectPR` request back to the host.

Draft mutations are serialized per host view and bind the pending review ID to its full `prKey`. Selection revisions prevent late loads/saves/generation results from installing state after a PR switch; submit/delete reject IDs not owned by the active PR. Regeneration preserves the existing GitHub draft until a replacement is explicitly saved.

### Max-turns recovery for Claude
If stream-json returns `error_max_turns` with `session_id`, auto-resume via `claude --resume <session_id> --max-turns 3` and nudge for final JSON. `review-engine`'s `ClaudeService` is the single implementation of this behavior for both hosts (IntelliJ in-process, VS Code via the sidecar).

### Draft review storage semantics
Inline comment metadata is encoded in review body HTML comment for resilient draft reload. Pending review creation omits `event`. On 422 for inline comments, fallback to body-first creation then per-comment POST. When a pending draft lacks usable hidden metadata, hosts fall back to GitHub API review comments and set `importedFromGitHub`; the webview warns that recovered review details may be incomplete and offers a **Re-anchor from current diff** action that re-runs `validateComments` to snap comments back to valid positions, clears the imported flag, and (via autosave) re-encodes proper hidden metadata to GitHub so the draft reloads cleanly next time.

### Draft autosave
The GitHub pending review is the single source of truth for in-progress reviews — there is no separate host-local draft buffer (rejected because the product can't operate offline anyway: diff fetch, worktree review, and submit all require `gh` auth + network). The webview autosaves the draft from shared code in `ReviewPane.tsx` driven by `lib/autosave.ts`: a freshly generated review saves immediately (save-on-generate, the costliest state to reproduce), and later edits to an already-saved draft are flushed on a 30s debounce, plus an immediate flush on panel hide (`visibilitychange`/`pagehide`) and on PR-switch/unmount. Autosave reuses the existing `saveDraft`/`draftSaved`/`draftSaveError` bridge messages, so it needs no host or schema changes and both hosts inherit it. The footer's secondary button is a status indicator (`Saving…` / `Save now` / `Saved`); the manual click is a "save now" convenience, never a required step. Dirty state is snapshot equality (`reviewSnapshot`) against the last successfully-saved result; saves are serialized via the in-flight `saving` flag so autosave never stacks on a manual save/submit. Submit always saves-first when the in-memory review may differ from the GitHub draft.

### Large diff visibility
GitHub diffs are truncated at 250 KB in both hosts. The webview detects the truncation marker and warns that diff display and chat context are incomplete, while `DiffViewer` still lazily limits rendered changed lines for browser performance.

The shared diff viewer also owns file-level navigation: it keeps a sticky “currently viewing” file indicator visible while the review body scrolls and renders a GitHub-style changed-files tree for jumping between files. If a reviewer jumps to a file outside the initial 500 changed-line preview, `DiffViewer` expands the full diff before scrolling so navigation and comment focus never strand hidden files.

### Review quality gate and chunked review mode
The webview runs a `Review Quality Check` pass automatically over the current draft and validation diff to flag trust risks (unanchored comments, low-evidence high-severity findings, and missing rationale metadata). When risks are present it surfaces a non-blocking badge (`N trust risks detected — Review`) that expands to a panel with one-click in-memory repairs (`remove unanchored`, `add rationale placeholders`, `downgrade high-risk issues`); a clean draft shows no nag. The check remains non-blocking, but the submit dialog now requires an explicit reviewer acknowledgement checkbox whenever unresolved trust risks remain.

For larger PRs, reviewers can enable chunked mode in per-review overrides. The webview splits the changed files into risk-priority batches bounded by both file count and patch size, and sends only the selected files' raw patch in each model request; hosts retain the full validation diff for final comment anchoring. A single oversized file remains intact rather than silently discarding hunks. It then merges batch outputs into a single draft and shows explicit batch progress with per-file confidence summaries. The review setup UI is split into a simple focus-area field plus an Advanced section for chunking and one-off prompt overrides, and chunked mode auto-enables by default when the preflight heuristic recommends it (large file counts, large changed-line counts, or truncated diff context) unless the reviewer explicitly toggles it back off.

### Notification parity
Background PR notifications are available in both hosts and are off by default. The first poll seeds existing PRs silently. Both hosts support review-requested PR notifications and optional starred-repository PR notifications, using the persisted settings listed below. Each notification is labeled with its provenance (`Review requested` vs `★ Starred repo`) so a starred-repo PR — which need not appear in the main list's current-repo scope — is never mistaken for a review request; when a PR matches both sources, review-requested takes precedence. The labeling/merge logic is shared-shaped across hosts (`PRNotificationService.mergeCandidates`/`notificationTitle` in IntelliJ, `notifications.ts` `mergeBySource`/`notificationMessage` in VS Code). Notification actions now route into PR Pilot itself (`activatePR`) instead of straight to the browser: the shared list pins the opened PR into the list when needed and marks it as notification-opened even if it is outside the current discovery scope. The seen-PR set is persisted across reloads/restarts (IntelliJ via `SeenPRSet`, VS Code via extension `globalState`) so PRs that appear while the editor is closed are still announced on the next poll rather than silently absorbed by a re-seed. Changing the notification scope (enable/disable, review-requested, starred repos, or GitHub base URL) re-seeds silently so existing in-scope PRs are not announced retroactively.

### Comment anchoring policy
Client-side validation partitions comments: keep in-hunk, snap within +-3 lines, orphan otherwise. Orphans are excluded from inline POST and appended to review body section.

### Security constraints
`githubBaseUrl` must be an HTTPS origin without credentials, an explicit port, path, query, or fragment; external links must use HTTPS. GitHub/provider tokens are not persisted by PR Pilot. Provider review input is adversarial: do not enable tools, MCP discovery, shell/write permissions, or broader environment capabilities by default. A detached worktree protects the active checkout but is not a machine sandbox, so explicit capability elevation must remain visible in settings.

### Repo detection and webview hosting
Repo detection walks upward to `.git/config` and reads the `[remote "origin"]` URL specifically (not the first `url=` in the file) so multi-remote/fork setups resolve to origin consistently across hosts, handling SCP and `ssh://` remotes correctly. Webview assets are served via loopback `HttpServer` for proper same-origin module loading; path normalization blocks traversal.

### VS Code webview surfaces
The VS Code host exposes PR Pilot as an editor-tab `WebviewPanel` opened by `pr-pilot.open`. The Activity Bar webview view (`pr-pilot.main`) is a thin launcher that renders only a single "Open PR Pilot" button. Resolving the launcher does not open the editor automatically; an explicit button click opens or reveals the editor and closes the sidebar so the two surfaces do not compete for workspace width. The full PR loading, review generation, chat, and worktree lifecycle run only in the editor-tab panel.

For development, the extension loads UI assets from the sibling `webview/dist` folder. For packaged `.vsix` builds, the release/package flow stages that same output into `vscode-extension/webview-dist`, and the extension resolves the bundled copy first so installed releases do not depend on the source repo layout.

All VS Code webview surfaces use restrictive Content Security Policy headers. The main Vite document rewrites only packaged local asset URIs and nonces scripts; the launcher and fallback error page nonce inline resources, and dynamic error text is HTML-escaped.

### IntelliJ webview surfaces
The IntelliJ `PR Pilot` tool window is the sole primary interactive surface. It owns one full `WebviewPanel` directly, so selecting the tool-window stripe always shows the real review UI without an editor-tab handoff, launcher, or duplicate webview lifecycle. Hiding the tool window preserves the session; removing its content or closing the project disposes the panel and its JCEF/loopback/worktree resources. This intentionally differs from VS Code's editor-panel container because IntelliJ tool windows can directly and reliably host the persistent JCEF Swing component; it is a container difference, not a feature difference. The plugin declares the `com.intellij.modules.jcef` bundled dependency in both `plugin.xml` and the IntelliJ Platform Gradle configuration; keep both declarations because Toolbox-based IDE distributions provide JCEF as a separate bundled module.

### VS Code extension development target repo
The `.vscode/launch.json` config `Run PR Pilot Extension Against Target Repo` prompts for an absolute repository path and passes it as `PR_PILOT_TARGET_REPO` to the Extension Development Host. Use it when the PR Pilot source repo is open in the main VS Code window but PR Pilot should inspect PRs for a different local checkout; `workspace.ts` makes repo detection, worktree creation, and CLI working directories resolve against the target repo instead of whichever folder VS Code opened in the dev host.

## Settings persistence

`PluginSettings` (`claudeReviews.xml`) stores:

- `githubBaseUrl` (default `https://github.com`)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `reviewModel` (default `""`)
- `reviewModelCopilot` (default `"claude-sonnet-4.6"`)
- `reviewProvider` (default `"claude"`; values `claude|copilot`)
- `reviewEffort` (default `"medium"`; values `none|low|medium|high|xhigh|max`)
- `copilotInheritMcp` (default `false`) — explicit capability elevation that enables Copilot SDK config discovery for MCP servers while retaining the MCP-only permission allowlist. Copilot-only.
- `copilotAutoEnableMcpOnReview` (default `false`) — review-only opt-in that forces MCP enablement for Copilot review generation even when `copilotInheritMcp` is off; chat still follows `copilotInheritMcp`. Copilot-only.
- `copilotConfigDir` (default `""`) — optional override of the Copilot config directory used to discover MCP servers; empty uses the CLI default (`~/.copilot`). Copilot-only.
- `reviewFocusAreas` (default `""`) — default reviewer focus areas; a non-empty per-review override takes precedence.
- `reviewCustomInstructions` (default `""`) — default extra review instructions; a non-empty per-review override takes precedence.

No API keys or tokens are written to disk.

## Local data files

IntelliJ-only (`intellij-plugin`'s `PendingReviewIndex`/`SeenPRSet`); VS Code persists the equivalent state via extension `globalState` instead (see Notification parity above).

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
