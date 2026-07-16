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
    ci.yml                           – Push/PR CI checks: Gradle verification plus webview and VS Code lint/typecheck/tests/build
    release.yml                      – Tag-driven GitHub release pipeline; builds IntelliJ + VS Code artifacts and marks `v*-rc.*` tags as prereleases

core/                                  – KMP module (jvm + js targets); Java services compiled as jvmMain
  src/commonMain/kotlin/com/jinloes/prpilot/
    model/
      PullRequest.kt                 – @Serializable data class (title, number, owner, repo, author, etc.)
      ReviewResult.kt                – @Serializable class; holds summary, verdict, mutable List<LineComment>
      LineComment.kt                 – @Serializable class; anchor/body plus optional severity, category, confidence, and rationale
      ChatMessage.kt                 – @Serializable data class; Role + content for chat history
      PRReviewRequest.kt             – @Serializable data class; parameter object for ClaudeService.reviewPR
      ReviewProvider.kt              – enum (CLAUDE | COPILOT); fromId(id) Java-friendly factory
    parser/
      DiffParser.kt                  – Kotlin object; unified diff parser; DiffFile / DiffLine types
    util/
      ProcessUtil.kt                 – expect object; findBinary(name, candidates); jvmMain actual uses java.io.File, jsMain actual uses Node.js fs
  src/commonMain/kotlin/com/jinloes/prpilot/services/
      GitHubService.kt               – GitHub REST API: search PRs, diff, draft review CRUD; Ktor + kotlinx.serialization; blocking wrappers for Java callers
      RunBlockingCompat.kt           – expect bridge from suspend to blocking API for Java callers
      UrlEncode.kt                   – expect URL encoding helper
  src/jvmMain/kotlin/com/jinloes/prpilot/services/
      GitHubAuthService.kt           – Runs `gh auth token`; probes known gh binary paths
      ClaudeService.kt               – Shells out to `claude --print`; synchronous/blocking API
      CopilotService.kt              – Uses the official Copilot Java SDK to drive local `copilot`; mirrors ClaudeService API
      CopilotModelDiscovery.kt       – Runs `copilot help config` once per session and caches model list
      GitWorktreeService.kt          – Creates/removes temporary git worktrees for PR branch reviews
      PendingReviewIndex.kt          – Local JSON index of saved drafts (~/.pr-pilot/pending-prs.json)
      SeenPRSet.kt                   – Local JSON set of notified PR IDs (~/.pr-pilot/seen-prs.json)
  src/jsMain/kotlin/com/jinloes/prpilot/
      services/RunBlockingCompat.kt  – JS actual throws UnsupportedOperationException
      services/UrlEncode.kt          – JS actual uses encodeURIComponent
      util/ProcessUtil.kt            – JS actual uses Node fs.existsSync/statSync

intellij-plugin/                       – IntelliJ plugin host; depends on :core
  src/main/java/com/jinloes/prpilot/
    services/
      IntellijGitHubService.java
      IntellijClaudeService.java
      UserFacingErrors.java           – Maps runtime/network exceptions to actionable UI copy
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
      RepoDetector.java

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
    components/layout/AccessibleResizer.tsx – Pointer and keyboard-accessible pane separator
    components/ReviewPane/chatHeight.ts – Validates persisted chat-panel heights against its usable layout range
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
    github.ts
    claude.ts
    copilot.ts                         – Copilot SDK service (`@github/copilot-sdk`) with streaming/status forwarding
    review.ts                          – Shared review-JSON extraction + schema validation (used by claude.ts + copilot.ts)
    worktree.ts                        – Creates/removes temporary git worktrees for PR branch reviews (mirrors GitWorktreeService.kt)
    settings.ts                        – Settings webview controller (panel lifecycle + config read/write); mirrors PluginSettingsConfigurable
    settingsView.ts                    – Pure settings-webview view logic (HTML, model-merge, escaping); no vscode import, unit-tested
    hostTheme.ts                       – Pure light/dark/high-contrast theme classification
    notifications.ts                   – Pure background-notification helpers (source labeling, dedupe/merge with review-requested precedence); no vscode import, unit-tested
    userFacingError.ts                 – Maps host/provider errors to user-actionable copy
    workspace.ts                       – Resolves the VS Code workspace dir, including dev-host target repo override
  shared/
    user-facing-errors.yaml            – Shared message templates consumed by both hosts
  test/
    claude.test.ts
    copilot.test.ts
    review.test.ts
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

### Module boundaries
`core` is KMP and has zero IntelliJ dependencies. `intellij-plugin` depends on JVM variant of `core`. Keep Java sources in `core/src/main/java` and `core/src/test/java` (do not move to `src/jvmMain/java`).

### Java interop conventions for commonMain Kotlin
Java callers must use generated getters/setters (`getX()`), not Kotlin property or record-style accessors. `DiffParser` access from Java is via `DiffParser.INSTANCE.*`. Keep JSON in `core` on kotlinx.serialization.

### expect/actual bridges
`ProcessUtil` and `runBlockingCompat` are expect/actual bridges; JVM provides blocking/runBlocking behavior, JS throws for blocking API path.

### GitHubService lifecycle
`GitHubService` is stateless except `apiBase`; IntelliJ adapter creates fresh instances so settings changes apply immediately. Keep `HTTP_CLIENT` shared/static to avoid per-call client pools.

### Threading model
`ClaudeService`/`CopilotService` are synchronous core services. IntelliJ adapters own threading (pooled thread for I/O, EDT for UI callbacks).

### Provider toggle and prompt sharing
Copilot and Claude share prompt builders/parsing (`ClaudeService` companion helpers). Do not fork prompt constants by provider unless absolutely required.

### Copilot SDK runtime
Both hosts use official Copilot SDKs (`com.github:copilot-sdk-java` and `@github/copilot-sdk`) to control local `copilot`. Stream `assistant.message_delta` to text chunks, surface `tool.execution_start` names as status, and parse final `assistant.message` JSON with delta fallback.

### Provider capability isolation
Claude review/chat processes disable tools, use `permission-mode=dontAsk`, pass a strict empty MCP configuration, and read user settings only. The review prompt therefore embeds the bounded GitHub diff instead of asking the CLI to fetch repository data. Temporary stream output is owner-only and deleted in `finally`; raw model output is not retained in logs.

Copilot review/chat sessions reject all SDK permission requests by default and disable config discovery. `copilotInheritMcp` is an explicit capability elevation: when enabled, config discovery loads MCP servers from the Copilot CLI config and repo-local `.mcp.json`, but the permission handler approves only MCP requests once and continues rejecting shell/write capabilities. The optional `copilotConfigDir` setting maps to `configDir`/`setConfigDirectory` for non-default Copilot homes. Never replace the explicit permission handler with blanket approval.

### Reasoning effort normalization
Persisted values are `none|low|medium|high|xhigh|max`; SDK accepts `low|medium|high|xhigh`. Normalize before session creation: `none -> low`, `max -> xhigh`, blank/unknown -> `medium`.

### Copilot model discovery
Both hosts discover the available Copilot model list at runtime and cache it for the session, falling back to a short hardcoded suggestion list on probe failure.

- **IntelliJ**: `CopilotModelDiscovery` runs `copilot help config` and parses the `` `model`: `` section; `PluginSettingsComponent` merges the result into the (editable) model combo in Settings → Tools → PR Pilot.
- **VS Code**: `copilot.ts` `listModels()` queries the SDK's `client.listModels()` directly (no CLI parsing) and `filterModelIds()` drops policy-`disabled`/blank IDs. Surfaced two ways: (1) the **settings webview** (`settings.ts` + `settingsView.ts`) — opened via the gear in the PR Pilot view title or the `pr-pilot.openSettings` command — shows a live model dropdown and hides the non-active provider's model field; (2) the `pr-pilot.selectCopilotModel` quick-pick command (palette). VS Code's declarative settings JSON can't self-populate an enum or conditionally hide fields, which is why a webview is used for the rich settings UI. The underlying settings (`reviewProvider`, `reviewModel`, `reviewModelCopilot`, `reviewEffort`, `githubBaseUrl`, `copilotInheritMcp`, `copilotConfigDir`) remain editable in native Settings too.
  The settings webview validates `githubBaseUrl` before saving, posts per-field saved/error feedback, exposes model-refresh status, and has a `Test` action that verifies `gh` authentication for the configured host.

### PR discovery scope
The shared PR list sends an explicit `searchScope` on `refreshPRs`: `currentRepo`, `reviewRequested`, `assigned`, or `authored`. Both hosts build GitHub search queries from that scope and return `listStatus` (`searchScope`, `currentRepo`, `resultLimit`, `limited`) with `prListLoaded` so the webview can explain what was searched and when additional PRs are hidden. To distinguish "exactly the limit" from "more exist", the search over-fetches one row beyond the display limit (`resultLimit` = 50, fetch 51): `limited` is true only when more than 50 match, and the list is sliced back to 50. `currentRepo` searches only the detected repository; if no repo is detected it falls back to `author:@me`. Main-list discovery intentionally includes GitHub draft pull requests (the old `draft:false` filter was removed) so authored WIP PRs are discoverable; the shared PR DTO distinguishes `isDraft` (GitHub PR draft / `PR-DRAFT`) from `hasReviewDraft` (saved PR Pilot review draft / `REV-DRAFT`). Starred repositories are used only by optional notification polling, not by the main list's current-repo scope.

### Binary resolution
Probe known hard-coded paths for `gh`, `claude`, and `copilot` before falling back to command name, because GUI-launched IntelliJ often has incomplete `PATH`.

### Provider preflight
Before running a review, both hosts check that the configured provider's CLI (`claude`/`copilot`) is resolvable — a hard-coded candidate path exists, or the binary is found on `PATH` — and, if not, push a `reviewError` with actionable `provider_not_installed` copy instead of attempting a doomed spawn. This surfaces in-context in the review pane (which already offers a "Try Again") rather than as the full-pane PR-list setup screen. The selected-PR no-draft state also receives a `providerReadiness` bridge payload so the review pane can show `Claude ready` / `Copilot ready` (or setup-needed) status before the user presses Generate. Availability checks: `ProcessUtil.isBinaryAvailable` + `ClaudeService.isBinaryAvailable()`/`CopilotService.isBinaryAvailable()` (JVM); `claudeBinaryAvailable()`/`copilotBinaryAvailable()` + shared `existsOnPath` (`claude.ts`/`copilot.ts`). The shared `provider_not_installed` template wording is kept in sync across `UserFacingErrors.java` and `userFacingError.ts`.

### Prompt-injection hardening
When wrapping untrusted payloads in XML-like tags, escape matching closing tags inside payload to prevent tag breakout. Repository guidelines, PR metadata, descriptions, diffs, prior reviews, and user messages remain untrusted data even when tag escaping is applied; provider capability isolation is the primary security boundary.

### Diff acquisition model
Hosts fetch and bound the GitHub diff before provider execution and embed it in `<pr_diff>`. Review providers run without repository tools by default, so they cannot fetch additional context during review. The separate full validation diff is retained host-side/webview-side for comment anchoring and is not sent to the provider.

### Worktree-based PR context
When the PR's repo matches the open project/workspace and a git root is found, both hosts create a temporary git worktree checked out to the PR branch and reuse it for both review and chat. This gives the model accurate local file context (correct branch state) for type lookups and cross-file references across the full PR session. Cleanup runs when the active PR changes or the view is disposed. Falls back silently to the open project/workspace dir if worktree creation fails or the PR is from an unrelated repo. Fork PRs use `git fetch <clone_url> <branch>` + `FETCH_HEAD`.

- **IntelliJ**: `WebviewPanel.resolvePrClaudeService` builds a per-PR `IntellijClaudeService` pointed at the worktree, using `GitWorktreeService` (jvmMain).
- **VS Code**: `extension.ts` `resolveWorkingDir`/`clearWorktree` resolve the per-view worktree dir passed as `workingDir` to the review/chat CLIs, using `worktree.ts`. Chat reuses an existing worktree with the cached token only (never triggers a fresh auth).

### Cross-host parity
When host-specific logic changes in IntelliJ or VS Code, update the paired implementation in the other host. The mapping table and enforcement workflow live in `AGENTS.md`.

### User-facing error copy
Do not surface raw provider/HTTP exception strings directly to users in review/draft/chat flows. Both hosts map low-level errors to actionable guidance (`UserFacingErrors` in IntelliJ, `userFacingError.ts` in VS Code) to keep messaging consistent across providers. Message strings live in shared YAML templates (`vscode-extension/shared/user-facing-errors.yaml`) and support `{placeholder}` substitution.

### Bridge payload validation
Bridge messages carry protocol version `1`. Both hosts validate webview-to-host messages before dispatching handlers (`BridgeMessageValidator` in IntelliJ, `bridgeValidation.ts` in VS Code), including PR identity, enums, nested review/comment shapes, booleans, collection sizes, and text bounds. The webview validates every host-to-webview payload in `bridge/validation.ts` before fan-out. Unknown versions/types and malformed nested payloads are rejected instead of reaching business logic. Optional wire fields are omitted rather than serialized as `null`; IntelliJ serializes bridge comments through strict MapStruct DTO mapping and omits absent rich metadata rather than emitting invalid empty enum values.

### GitHub API resilience policy
Both hosts apply a transient-failure policy on GitHub REST calls: 15s request/connect/socket timeout, retries on `429`/`5xx`, and retry of timeout-style transport errors. This keeps PR loading/review flows resilient to short-lived network or GitHub edge failures while preserving fast-fail behavior for permanent `4xx` errors.

### First-run onboarding path
When startup PR loading fails, hosts push a `setupRequired` bridge message with actionable detail instead of silently failing. Supported reasons are `gh_not_installed`, `gh_not_authenticated`, and `load_failed` (non-auth load errors). `PRList` renders a full-pane setup/error screen with a checklist covering GitHub CLI installation, GitHub authentication, and PR loading, plus a Refresh button when this message is received. IntelliJ maps auth diagnosis to stable reason IDs via `PRToolWindowFactory.setupReason` and also emits `load_failed` for post-auth load exceptions; VS Code classifies setup-worthy auth failures (including 401/403/bad-credentials responses) in `classifySetupAuthError`. The VS Code host also triggers an initial `handleRefreshPRs` call immediately after `resolveWebviewView` so the webview never hangs on its initial loading state.

The setup screen is a guided in-app wizard with host detection. Both hosts expose status re-check, settings, and auth-guide actions; VS Code additionally supports one-click `gh auth login` automation via a `runAuthLogin` bridge action that opens an integrated terminal and runs the command.
After the first successful load (or a recovery from `setupRequired`), the shared PR list shows a one-time success coach banner that points users at scope switching and the `PR-DRAFT` vs `REV-DRAFT` mental model.

### PR chat scope
Chat is available after PR selection, before and after review generation. Hosts build chat context from the active PR title/body, the full diff (already capped at 80 KB by the diff fetch), and the generated review when one exists; both hosts send the same full diff so chat answers do not diverge by host. The webview displays which context buckets are attached and adds selected text when the user right-clicks or verifies a comment. Chat reuses the PR worktree when available. The VS Code host sources the active PR's title/body from `getPRDetail` on select (the webview `selectPR` message carries only number/owner/repo), so the review prompt and chat context always include the real PR description.

### DTO mapping in IntelliJ webview bridge
`WebviewPanel` model-to-DTO conversion uses MapStruct (`ReviewMapper`) instead of hand-rolled mappers so field drift fails at compile time.

### Webview bridge PR correlation
PR-scoped lifecycle messages (`draftLoaded`, review generation/chunks/results/errors, draft save/submit/delete, and chat responses) carry a `prKey` of `owner/repo#number`. The React webview drops keyed messages that do not match the active PR so late async results from a previously selected PR cannot repaint or submit against the current PR. When adding a new PR-scoped host message, include the same `prKey` in both hosts. Host-driven selection changes that originate outside the list (for example, background notifications) use a separate `activatePR` message carrying the full PR DTO so the app can honor its unsaved-review confirmation flow before sending the normal `selectPR` request back to the host.

Draft mutations are serialized per host view and bind the pending review ID to its full `prKey`. Selection revisions prevent late loads/saves/generation results from installing state after a PR switch; submit/delete reject IDs not owned by the active PR. Regeneration preserves the existing GitHub draft until a replacement is explicitly saved.

### Max-turns recovery for Claude
If stream-json returns `error_max_turns` with `session_id`, auto-resume via `claude --resume <session_id> --max-turns 3` and nudge for final JSON.

### Draft review storage semantics
Inline comment metadata is encoded in review body HTML comment for resilient draft reload. Pending review creation omits `event`. On 422 for inline comments, fallback to body-first creation then per-comment POST. When a pending draft lacks usable hidden metadata, hosts fall back to GitHub API review comments and set `importedFromGitHub`; the webview warns that recovered review details may be incomplete and offers a **Re-anchor from current diff** action that re-runs `validateComments` to snap comments back to valid positions, clears the imported flag, and (via autosave) re-encodes proper hidden metadata to GitHub so the draft reloads cleanly next time.

### Draft autosave
The GitHub pending review is the single source of truth for in-progress reviews — there is no separate host-local draft buffer (rejected because the product can't operate offline anyway: diff fetch, worktree review, and submit all require `gh` auth + network). The webview autosaves the draft from shared code in `ReviewPane.tsx` driven by `lib/autosave.ts`: a freshly generated review saves immediately (save-on-generate, the costliest state to reproduce), and later edits to an already-saved draft are flushed on a 30s debounce, plus an immediate flush on panel hide (`visibilitychange`/`pagehide`) and on PR-switch/unmount. Autosave reuses the existing `saveDraft`/`draftSaved`/`draftSaveError` bridge messages, so it needs no host or schema changes and both hosts inherit it. The footer's secondary button is a status indicator (`Saving…` / `Save now` / `Saved`); the manual click is a "save now" convenience, never a required step. Dirty state is snapshot equality (`reviewSnapshot`) against the last successfully-saved result; saves are serialized via the in-flight `saving` flag so autosave never stacks on a manual save/submit. Submit always saves-first when the in-memory review may differ from the GitHub draft.

### Large diff visibility
GitHub diffs are truncated at 80 KB in both hosts. The webview detects the truncation marker and warns that diff display and chat context are incomplete, while `DiffViewer` still lazily limits rendered changed lines for browser performance.

### Review quality gate and chunked review mode
The webview runs a `Review Quality Check` pass automatically over the current draft and validation diff to flag trust risks (unanchored comments, low-evidence high-severity findings, and missing rationale metadata). When risks are present it surfaces a non-blocking badge (`N trust risks detected — Review`) that expands to a panel with one-click in-memory repairs (`remove unanchored`, `add rationale placeholders`, `downgrade high-risk issues`); a clean draft shows no nag. The check remains non-blocking, but the submit dialog now requires an explicit reviewer acknowledgement checkbox whenever unresolved trust risks remain.

For larger PRs, reviewers can enable chunked mode in per-review overrides. The webview splits the changed files into risk-priority batches, runs one model pass per batch with batch-scoped file instructions, then merges batch outputs into a single draft and shows explicit batch progress with per-file confidence summaries. The review setup UI is split into a simple focus-area field plus an Advanced section for chunking and one-off prompt overrides, and chunked mode auto-enables by default when the preflight heuristic recommends it (large file counts, large changed-line counts, or truncated diff context) unless the reviewer explicitly toggles it back off.

### Notification parity
Background PR notifications are available in both hosts and are off by default. The first poll seeds existing PRs silently. Both hosts support review-requested PR notifications and optional starred-repository PR notifications, using the persisted settings listed below. Each notification is labeled with its provenance (`Review requested` vs `★ Starred repo`) so a starred-repo PR — which need not appear in the main list's current-repo scope — is never mistaken for a review request; when a PR matches both sources, review-requested takes precedence. The labeling/merge logic is shared-shaped across hosts (`PRNotificationService.mergeCandidates`/`notificationTitle` in IntelliJ, `notifications.ts` `mergeBySource`/`notificationMessage` in VS Code). Notification actions now route into PR Pilot itself (`activatePR`) instead of straight to the browser: the shared list pins the opened PR into the list when needed and marks it as notification-opened even if it is outside the current discovery scope. The seen-PR set is persisted across reloads/restarts (IntelliJ via `SeenPRSet`, VS Code via extension `globalState`) so PRs that appear while the editor is closed are still announced on the next poll rather than silently absorbed by a re-seed. Changing the notification scope (enable/disable, review-requested, starred repos, or GitHub base URL) re-seeds silently so existing in-scope PRs are not announced retroactively.

### Comment anchoring policy
Client-side validation partitions comments: keep in-hunk, snap within +-3 lines, orphan otherwise. Orphans are excluded from inline POST and appended to review body section.

### Security constraints
`githubBaseUrl` and external links must use HTTPS. GitHub/provider tokens are not persisted by PR Pilot. Provider review input is adversarial: do not enable tools, MCP discovery, shell/write permissions, or broader environment capabilities by default. A detached worktree protects the active checkout but is not a machine sandbox, so explicit capability elevation must remain visible in settings.

### Repo detection and webview hosting
Repo detection walks upward to `.git/config` and reads the `[remote "origin"]` URL specifically (not the first `url=` in the file) so multi-remote/fork setups resolve to origin consistently across hosts, handling SCP and `ssh://` remotes correctly. Webview assets are served via loopback `HttpServer` for proper same-origin module loading; path normalization blocks traversal.

### VS Code webview surfaces
The VS Code host exposes PR Pilot as an editor-tab `WebviewPanel` opened by `pr-pilot.open`. The Activity Bar webview view (`pr-pilot.main`) is a thin launcher that renders only a single "Open PR Pilot" button. Resolving the launcher does not open the editor automatically; an explicit button click opens or reveals the editor and closes the sidebar so the two surfaces do not compete for workspace width. The full PR loading, review generation, chat, and worktree lifecycle run only in the editor-tab panel.

For development, the extension loads UI assets from the sibling `webview/dist` folder. For packaged `.vsix` builds, the release/package flow stages that same output into `vscode-extension/webview-dist`, and the extension resolves the bundled copy first so installed releases do not depend on the source repo layout.

All VS Code webview surfaces use restrictive Content Security Policy headers. The main Vite document rewrites only packaged local asset URIs and nonces scripts; the launcher and fallback error page nonce inline resources, and dynamic error text is HTML-escaped.

### IntelliJ webview surfaces
The IntelliJ `PR Pilot` tool window is the sole primary interactive surface. It owns one full `WebviewPanel` directly, so selecting the tool-window stripe always shows the real review UI without an editor-tab handoff, launcher, or duplicate webview lifecycle. Hiding the tool window preserves the session; removing its content or closing the project disposes the panel and its JCEF/loopback/worktree resources. This intentionally differs from VS Code's editor-panel container because IntelliJ tool windows can directly and reliably host the persistent JCEF Swing component; it is a container difference, not a feature difference.

### VS Code extension development target repo
The `.vscode/launch.json` config `Run PR Pilot Extension Against Target Repo` prompts for an absolute repository path and passes it as `PR_PILOT_TARGET_REPO` to the Extension Development Host. Use it when the PR Pilot source repo is open in the main VS Code window but PR Pilot should inspect PRs for a different local checkout; `workspace.ts` makes repo detection, worktree creation, and CLI working directories resolve against the target repo instead of whichever folder VS Code opened in the dev host.

## Settings persistence

`PluginSettings` (`claudeReviews.xml`) stores:

- `githubBaseUrl` (default `https://github.com`)
- `notificationsEnabled` (default `false`)
- `notifyReviewRequested` (default `true`)
- `notifyStarredRepos` (default `false`)
- `notificationPollMinutes` (default `5`)
- `githubUsername` (display cache)
- `reviewModel` (default `""`)
- `reviewModelCopilot` (default `"claude-sonnet-4.6"`)
- `reviewProvider` (default `"claude"`; values `claude|copilot`)
- `reviewEffort` (default `"medium"`; values `none|low|medium|high|xhigh|max`)
- `copilotInheritMcp` (default `false`) — explicit capability elevation that enables Copilot SDK config discovery for MCP servers while retaining the MCP-only permission allowlist. Copilot-only.
- `copilotConfigDir` (default `""`) — optional override of the Copilot config directory used to discover MCP servers; empty uses the CLI default (`~/.copilot`). Copilot-only.
- `reviewFocusAreas` (default `""`) — default reviewer focus areas; a non-empty per-review override takes precedence.
- `reviewCustomInstructions` (default `""`) — default extra review instructions; a non-empty per-review override takes precedence.

No API keys or tokens are written to disk.

## Local data files

| Path | Purpose |
|------|---------|
| `~/.pr-pilot/pending-prs.json` | Index of PRs with saved drafts (owner, repo, number, title, savedAt, headSha) |
| `~/.pr-pilot/seen-prs.json` | Set of `owner/repo#number` strings already notified about |
