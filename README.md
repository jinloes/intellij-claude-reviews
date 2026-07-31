# PR Pilot

PR Pilot is a dual-host code review assistant for GitHub pull requests:

- IntelliJ plugin (`intellij-plugin`)
- VS Code extension (`vscode-extension`)

It helps you discover PRs, generate AI-assisted reviews (Claude or Copilot), edit comments, and submit review drafts.

## What it does

- Lists pull requests by scope (current repo, review-requested, assigned, authored)
- Generates structured code review feedback with inline comments
- Supports chat over PR context (title/body, diff, generated review)
- Saves and restores pending review drafts
- Uses temporary git worktrees for accurate PR-branch context
- Supports background PR notifications in both hosts

## Repository layout

- `core/` - Plain Java 17 shared models and diff parser used by both hosts
- `github-engine/` - Plain Java 17 GitHub/repository/review engine shared by both hosts
- `intellij-plugin/` - IntelliJ host integration
- `sidecar/` - Thin stdio JSON-RPC process adapter used by the VS Code extension
- `vscode-extension/` - VS Code host integration
- `webview/` - Shared React webview UI
- `.github/workflows/release.yml` - Tag-driven release workflow for both plugin artifacts
- `AGENTS.md` - Agent workflow, test requirements, and parity rules
- `ARCHITECTURE.md` - Architecture details and design constraints

## Requirements

- Java 17+ (for Gradle builds and at VS Code extension runtime)
- Node.js 20+ (Node 20.17+ recommended by extension engines)
- npm
- GitHub CLI (`gh`) authenticated (`gh auth login`)
- Optional for runtime review providers:
  - Claude CLI (`claude`)
  - GitHub Copilot CLI (`copilot`)

The IntelliJ plugin calls `github-engine` directly in the IDE JVM. The VS Code extension bundles
the sidecar JAR and launches it with `java`; it checks protocol compatibility and required
capabilities during activation. If Java 17+ is unavailable, PR Pilot reports an actionable setup
error instead of falling back to a separate TypeScript GitHub implementation.

## Local development

### Build IntelliJ plugin

```bash
./gradlew :intellij-plugin:buildPlugin
```

Output:

- `intellij-plugin/build/distributions/*.zip`

### Build webview assets

```bash
cd webview
npm ci
npm run build
```

### Build/test VS Code extension

```bash
cd vscode-extension
npm ci
npm run build
npm run test:unit
```

### Package VS Code extension (`.vsix`)

This builds and stages the Java sidecar and shared webview assets before packaging.

```bash
./gradlew :sidecar:bootJar

cd webview
npm ci
npm run build

cd ../vscode-extension
npm ci
npm run package:vsix
```

## Verification commands

These are the repository's required checks from `AGENTS.md`:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew check
./gradlew :core:test :intellij-plugin:unitTest
```

```bash
(cd webview && npm run lint)
(cd webview && npx tsc --noEmit)
(cd vscode-extension && npm run lint)
(cd vscode-extension && npx tsc --noEmit)
(cd vscode-extension && npm run test:unit)
```

## CI and release workflow

Continuous integration runs via `.github/workflows/ci.yml` on pushes to `main`, pull requests, and manual dispatch.

CI checks:

- Gradle Spotless + JVM checks (`spotlessCheck`, `check`, `:core:test`, `:intellij-plugin:unitTest`)
- Webview lint/typecheck/build
- VS Code extension lint/typecheck/unit tests/build
- Java 17 sidecar protocol smoke test and packaged `.vsix` JAR assertion

Releases are tag-driven via `.github/workflows/release.yml`.

What the workflow does:

1. Sets up Java 17 and Node 20
2. Builds IntelliJ plugin artifact
3. Builds and packages VS Code extension artifact
4. Creates a GitHub Release and uploads both artifacts

### RC vs final release tags

- RC / prerelease: `vX.Y.Z-rc.N` (example: `v1.4.0-rc.1`)
- Final release: `vX.Y.Z` (example: `v1.4.0`)

The workflow marks tags containing `-rc.` as GitHub prereleases automatically.

### Typical release commands

```bash
git tag v1.4.0-rc.1
git push origin v1.4.0-rc.1

# later, final
git tag v1.4.0
git push origin v1.4.0
```

## Cross-host parity rule

User-facing behavior must stay aligned between IntelliJ and VS Code. If you update host-specific logic in one, update the corresponding implementation in the other (see mapping table in `AGENTS.md`).

## Notes

- GitHub authentication and API behavior are shared in `github-engine`; hosts never receive or persist GitHub tokens.
- Review-provider CLI setup remains host-specific, while prompt and review semantics stay aligned.
- For deeper architecture details and persistence files, see `ARCHITECTURE.md`.

