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

## Host parity obligations

### The capability rule

**Engine capabilities are defined once and exposed everywhere.** The full capability surface of each
engine is declared as an interface:

- `github-engine`: `com.jinloes.prpilot.engine.GitHubEngineApi`
- `review-engine`: `com.jinloes.prpilot.engine.ReviewEngineApi`

Each interface carries an `RPC_METHODS` map from Java method name to JSON-RPC wire name.
`sidecar/StdioJsonRpcServer` must register a handler for every entry.

This is **enforced by `EngineCapabilityCoverageTest`** in the sidecar module, which fails the build if
a capability is declared without a wire name, exposed without being declared, or declared without
being registered. Do not maintain this mapping by hand — add the method to the interface, add its
`RPC_METHODS` entry, and register the handler; the test tells you if you missed a step.

### What parity does and does not require

**Required:** every capability is reachable by every host. A capability lives in `core`,
`github-engine`, or `review-engine` — never in a host. Do not add host-local GitHub behavior,
fallback transport, or a second CLI-spawning review implementation in
`vscode-extension/src/claude.ts` or `copilot.ts`.

**Not required:** that every host *consumes* every capability on the same day. A host may lag in
surfacing a capability in its UI. What is never acceptable is a host working around a missing
capability by re-implementing it locally.

**But record the lag as a lag.** The coverage test proves a capability is *reachable*, not that any
host *calls* it. Phase 1's four context capabilities sat exposed-but-uncalled in VS Code for months
while the plan read `✅ DONE`, so every VS Code review shipped with empty CI/commits/issue/profile
prompt sections. When you ship a capability a host does not yet consume, say so explicitly in the
plan and the PR description — do not mark the work complete.

If a genuine platform constraint makes a capability impossible in one host, document the gap and the
reason in `ARCHITECTURE.md` "Key design decisions" and call it out in the PR description.

### VS Code support floor

VS Code is a first-class host, not a port. Concretely:

- Every engine capability is exposed over the sidecar (test-enforced above).
- Any user-facing feature built on engine capabilities must have a VS Code path in the same change,
  or a documented gap. UI polish may differ; access to functionality may not.
- The sidecar is the only VS Code transport. No direct `gh`/CLI invocation from the extension.

### Remaining hand-mirrored logic

These are **not** covered by the coverage test because they are genuinely duplicated implementations
or per-host wiring. Treat this list as tech debt, not as a pattern to extend — do not add rows
without justification.

| Changed file | Must also update |
|---|---|
| `review-engine/ClaudeService.java` `CHAT_PERSONA` | `vscode-extension/src/claude.ts` same constant |
| `review-engine/ClaudeService.java` `buildFocusedChatPrompt` | `vscode-extension/src/claude.ts` same function |
| `review-engine/GitWorktreeService.java` worktree create/remove/find-root logic | `vscode-extension/src/worktree.ts` matching functions |
| `WebviewPanel.resolvePrClaudeService`/worktree lifecycle | `vscode-extension/src/extension.ts` `resolveWorkingDir`/`clearWorktree` |
| `PRNotificationService` poll/source-labeling/merge logic | `vscode-extension/src/notifications.ts` + `extension.ts` `PRNotificationPoller.poll` |
| `review-engine/BinaryLocator.java` | `vscode-extension/src/claude.ts` + `vscode-extension/src/copilot.ts` binary-probing candidates |
| `review-engine/RepoGuidelinesReader.java` glob/scan/read logic | `vscode-extension/src/guidelines.ts` same functions (`globToRegex`, `resolvePaths`, `readRepoGuidelines`) |
| `review-engine/CopilotModelDiscovery.java` model probing / `PluginSettingsComponent` model combo | `vscode-extension/src/copilot.ts` `listModels`/`filterModelIds` + `extension.ts` `selectCopilotModel` command |
| `PluginSettingsComponent` settings UI (provider-aware model selector, effort, base URL) | `vscode-extension/src/settings.ts` + `settingsView.ts` settings webview |
| `review-engine/CopilotService.DEFAULT_REASONING_EFFORT` | `vscode-extension/src/copilot.ts` |
| `webview/src/bridge/types.ts` message schemas | `WebviewPanel.java` and `vscode-extension/src/extension.ts` handlers |
| `PluginSettings` adding new setting | `vscode-extension/package.json` config contribution + `vscode-extension/src/extension.ts` reader |
| Any new wire method or notification shape | `vscode-extension/src/sidecar.ts` client wiring (the test enforces the *server* side only) |

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
