# CLAUDE.md — NOX-SYNC repair guardrails

## Working branch

Work only on:

```text
fix/fabian-level1-bugs
```

Never commit directly to `main` or `Fabian`.

## Required behavior

- Preserve the three Level 1 puzzle stages.
- Preserve the current map and terminal positions.
- Preserve host authority.
- Preserve combat constants unless a task explicitly changes them.
- Preserve controls:
  - Debug P1: F
  - Debug P2: Right Shift
  - LAN local player: F
- Preserve enemy wave sizes 2 → 3 → 4 → 5 → 6.
- Enemies must persist until death or restart.

## Engineering rules

- Inspect before editing.
- Prefer small coherent changes.
- Do not replace the networking architecture.
- Do not add a second enemy simulation on clients.
- Do not silently swallow asset-loading failures.
- Do not directly set remote health if doing so bypasses hurt/death transitions; provide a synchronization method.
- Centralize restart logic.
- Centralize return-to-lobby navigation.
- Dispose textures, sessions and screens exactly once.
- Keep serialization backward compatible when reasonably possible.
- Do not modify generated Gradle files.
- Do not delete assets.
- Do not rename existing controls without explicit approval.

## Validation after every task

Run:

```powershell
.\gradlew.bat core:compileJava
.\gradlew.bat launcher:compileJava
.\gradlew.bat lwjgl3:compileJava
git diff --check
git status --short
```

When all tasks are complete:

```powershell
.\gradlew.bat clean test
```

## Response format after editing

Report:

1. Root cause.
2. Files changed.
3. Behavioral change.
4. Build/test commands run.
5. Exact results.
6. Remaining manual tests.
7. Any uncertainty.
