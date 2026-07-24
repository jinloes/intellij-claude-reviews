# PR Pilot Agent Guide

Operational guide for coding agents working in this repository.

## Source of truth split

- `AGENTS.md` (this file): workflow, testing, coding conventions, and cross-host sync obligations.
- `ARCHITECTURE.md`: project layout, design constraints, settings, and local data files.
- Treat `AGENTS.md` as the single instruction file for agent workflows in this repo.

## Maintenance rule

Update docs as part of each coding task:

- Update `ARCHITECTURE.md` "Project layout" when files are added/renamed in documented areas.
- Add to `ARCHITECTURE.md` "Key design decisions" only for non-obvious constraints future code must respect.
- Update `ARCHITECTURE.md` "Settings persistence" when new persisted settings are added.
- Update `ARCHITECTURE.md` "Local data files" when persistent files are added.
- Keep this file focused on workflow rules; keep implementation details in code.
- Prefer updating `ARCHITECTURE.md` over adding architectural detail here.

## IntelliJ <-> VS Code sync obligations

**Feature parity is mandatory.** Every user-facing feature must work in both the IntelliJ plugin and the VS Code extension. When you add or change a feature in one host, implement the equivalent in the other host in the same change — do not ship a feature to only one host. If a true platform constraint makes parity impossible, document the gap and the reason in `ARCHITECTURE.md` "Key design decisions" and call it out explicitly in the PR description. Logic that belongs in shared code goes in `core` (plain Java 17 JVM module — no Kotlin) or `review-engine`/`github-engine` (JVM-only, used by both IntelliJ directly and the sidecar for VS Code); host wiring is mirrored between `WebviewPanel.java`/`PRToolWindowFactory.java` and `vscode-extension/src/extension.ts`.

AI review/chat generation (Claude CLI, Copilot SDK), prompt building, and review-JSON parsing have
one implementation in `review-engine`, shared by IntelliJ (in-process via `IntellijClaudeService`)
and VS Code (over the sidecar's `reviews/generate`/`reviews/chat`/`reviews/cancel` JSON-RPC methods).
Do not reintroduce a duplicate CLI-spawning implementation in `vscode-extension/src/claude.ts` or
`copilot.ts` — those files should stay limited to prompt-building helpers still needed client-side
(`buildFocusedChatPrompt`), binary-availability preflight, and Copilot model discovery (no sidecar
RPC endpoint for that yet).

When logic changes in one host, update the parallel file in the other host:

GitHub authentication, HTTP, query, repository detection, diff, and review mutation behavior has
one implementation in `github-engine`. Do not add host-local GitHub behavior or fallback transport;
mirror only the IntelliJ direct adapter and VS Code sidecar RPC wiring when capabilities change.

| Changed file | Must also update |
|---|---|
| `review-engine/ClaudeService.java` prompt constants (`REVIEW_INSTRUCTIONS`, `CHAT_PERSONA`) | `vscode-extension/src/claude.ts` same constants |
| `review-engine/ClaudeService.java` `buildPrompt`/`buildChatPrompt`/`buildFocusedChatPrompt` | `vscode-extension/src/claude.ts` same functions |
| `review-engine/ClaudeService.java` `reviewPR`/`chat`/stream-json parsing/max-turns resume | `sidecar/review/ReviewSessionService.java` call sites (no separate VS Code implementation — it routes through the sidecar) |
| `review-engine/GitWorktreeService.java` worktree create/remove/find-root logic | `vscode-extension/src/worktree.ts` matching functions |
| `WebviewPanel.resolvePrClaudeService`/worktree lifecycle | `vscode-extension/src/extension.ts` `resolveWorkingDir`/`clearWorktree` |
| `PRNotificationService` poll/source-labeling/merge logic | `vscode-extension/src/notifications.ts` + `extension.ts` `PRNotificationPoller.poll` |
| `review-engine/BinaryLocator.java` | `vscode-extension/src/claude.ts` + `vscode-extension/src/copilot.ts` binary-probing candidates |
| `review-engine/CopilotService.java` SDK session setup, stream events, effort normalization | `sidecar/review/ReviewSessionService.java` call sites |
| `review-engine/CopilotModelDiscovery.java` model probing / `PluginSettingsComponent` model combo | `vscode-extension/src/copilot.ts` `listModels`/`filterModelIds` + `extension.ts` `selectCopilotModel` command |
| `PluginSettingsComponent` settings UI (provider-aware model selector, effort, base URL) | `vscode-extension/src/settings.ts` + `settingsView.ts` settings webview |
| `review-engine/CopilotService.DEFAULT_REASONING_EFFORT` | `vscode-extension/src/copilot.ts` |
| `webview/src/bridge/types.ts` message schemas | `WebviewPanel.java` and `vscode-extension/src/extension.ts` handlers |
| `PluginSettings` adding new setting | `vscode-extension/package.json` config contribution + `vscode-extension/src/extension.ts` reader |
| `github-engine` public capability/result changes | `IntellijGitHubService.java`, `sidecar/StdioJsonRpcServer.java`, and `vscode-extension/src/sidecar.ts` wiring |
| `sidecar/StdioJsonRpcServer.java` `reviews/*` methods or notification shapes | `vscode-extension/src/sidecar.ts` `generateReview`/`chatReview`/`cancelReview` and notification dispatch |

## Testing conventions

Every code change must include tests for new/modified non-UI logic.

Checklist before completing a coding task:

1. Identify every changed non-UI method (service, utility, model, static helper).
2. Widen private methods to package-private only when needed for test access (never public).
3. Add tests for happy path, edge cases, and error paths.
4. Run required test suites and verify pass.

Test framework and location rules:

- Core tests: `core/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ (same convention as `intellij-plugin`).
- IntelliJ plugin tests: `intellij-plugin/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ.
- `review-engine`/`github-engine`/`sidecar` tests: `<module>/src/test/java/com/jinloes/prpilot/` using JUnit 5 + AssertJ (same convention as `intellij-plugin`).
- Group tests by `@Nested` classes named after the method under test.
- For temp directories, use `@BeforeEach`/`@AfterEach` + `Files.createTempDirectory`.
- Do not write tests to `~/.pr-pilot`; use temp dirs.

## Required verification commands

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew check
./gradlew :core:test :review-engine:test :intellij-plugin:unitTest
```

```bash
(cd webview && npm run lint)
(cd webview && npx tsc --noEmit)
(cd vscode-extension && npm run lint)
(cd vscode-extension && npx tsc --noEmit)
(cd vscode-extension && npm run test:unit)
```

## Coding rules

- Prefer Apache Commons helpers over hand-rolled equivalents (`CollectionUtils`, `StringUtils`, `Strings.CS`, `StringEscapeUtils`).
- `core`'s shared model classes are plain Java (JavaBean getters/setters). Don't reintroduce Kotlin, kotlinx.serialization, or jackson-module-kotlin for these classes — Jackson (plain bean introspection) is the only runtime JSON serializer used against them.
- In `intellij-plugin`, Jackson is allowed for webview bridge deserialization.
- IntelliJ threading: background work on pooled threads, UI updates on EDT via `invokeLater()`.
- Follow Google Java Style (Spotless-enforced), avoid FQNs in method bodies, keep imports explicit.
- Use comments only for non-obvious "why", not to restate code.
- No `Co-Authored-By` trailers in commit messages.
- Do not add `eslint-disable` comments without a `--` explanation.

## Scope reminder

- Keep this file short and operational; put architecture and version details in `ARCHITECTURE.md`.
