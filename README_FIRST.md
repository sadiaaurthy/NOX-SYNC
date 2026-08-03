# NOX-SYNC — Fabian Level 1 Bug-Fix Runbook

## What this kit is

This kit is a controlled workflow for fixing the Level 1 bugs reported by Fabian without damaging `main`.

Do **not** edit `main` directly. Start from the latest `Fabian` branch, create a separate repair branch, let Claude Code inspect the local files, then build and test after each small change.

Recommended branch:

```text
fix/fabian-level1-bugs
```

---

# 1. Audit verdict

## Bug 1 — Enemy sprite pixels bleed into neighboring frames

**Verdict: Real.**

`EnemySprites.java` assumes the enemy PNGs are a perfectly uniform 8 × 4 grid. The actual image dimensions are 1774 × 887, which do not divide evenly into 8 columns and 4 rows. The existing `Math.round(...)` slicing can place frame boundaries on the wrong pixel and include neighboring content.

Required repair:

- Use deterministic integer boundaries: `col * width / columns` and `(col + 1) * width / columns`.
- Use `TextureFilter.Nearest`.
- Use `TextureWrap.ClampToEdge`.
- Add a small safe inset only where visual inspection confirms it does not crop the death effect.
- Add validation/logging for unexpected sheet dimensions.
- Test all four walk directions and both death directions.

## Bug 2 — Enemy kill/damage sprite sheet does not load properly

**Verdict: Partly real.**

The file exists and the code loads the correct filename, so this is not simply a missing-file failure. The fragile slicing and incomplete network state make the animation appear broken or absent.

Required repair:

- Fix slicing.
- Add visible enemy hit feedback.
- Keep dead enemies long enough for the full death animation.
- Synchronize enemy animation state to LAN clients.

## Bug 3 — Player death only shows a popup; level does not end or return to the main menu

**Verdict: Real.**

The result popup is presentation-only. Closing it does not reliably navigate back to `LobbyScreen`. The game can remain frozen on Level 1.

Required repair:

- Add one central `returnToLobby()` path.
- Make popup dismissal and window close call that path.
- Stop/close host or client sessions.
- Dispose Level 1 resources exactly once.
- Make the in-game fallback panel return to the lobby when Enter is pressed.
- Keep restart as a separate key, preferably `R`, rather than overloading Enter.

## Bug 4 — Enemies in a wave spawn simultaneously

**Verdict: Real.**

`SwarmController.spawnWave(...)` immediately creates every enemy inside one loop and one frame.

Required repair:

- Queue pending spawns.
- Suggested first delay: 0.15 seconds.
- Suggested interval: 0.30–0.40 seconds.
- Preserve wave sizes: 2, 3, 4, 5, 6.
- Clear pending spawns on restart.

## Bug 5 — Attack controls

**Verdict: Correct, but mode-dependent.**

- Debug/local split-screen:
  - Left player: `F`
  - Right player: `Right Shift`
- Separate LAN computers:
  - Each local player uses `F` on their own computer.

Do not “fix” LAN Player 2 to Right Shift. That would be a regression.

## Bug 6 — It is unclear whether enemies die or whether the death sprite renders

**Verdict: Real in LAN; partially implemented in host/debug.**

The authoritative enemy object has health, a dying flag, a death timer and death-frame rendering. However, the LAN message currently carries only enemy positions, so the client cannot know whether an enemy is walking, hurt, dying or facing left/right.

Required repair:

- Extend `EnemyStateMessage` with:
  - x
  - y
  - facing direction
  - walk state time
  - dying flag
  - death time
  - horizontal facing for death row
- Keep deserialization backward compatible where practical.
- Render remote enemies from the received state.
- Add a brief hit flash or other obvious feedback.
- Confirm an enemy with 30 HP dies after two 15-damage attacks.

## Bug 7 — Player attack, hurt and death visuals are missing

**Verdict: Real.**

`Player.java` currently contains walk animation only. Taking damage changes a number; attacking does not trigger a player animation state.

Required repair:

- Add player visual states:
  - IDLE/WALK
  - ATTACK
  - HURT
  - DEAD
- If proper player attack/hurt/death sheets are unavailable, first implement a clear procedural fallback:
  - short attack lunge/scale
  - red hurt flash
  - death rotation/fade
- Keep this fallback until approved art sheets are added.
- Trigger the attack visual even when no enemy is in range.
- LAN health synchronization must trigger hurt/death feedback rather than directly overwriting health silently.

## Bug 8 — Wrong-answer popup stays open and must be closed manually

**Verdict: Real.**

The host/debug wrong-answer callback spawns enemies but does not close both terminal UIs. A `WrongAnswerMessage` class exists, but the current flow does not consistently broadcast and handle it.

Required repair:

- On the authoritative host:
  - close local terminal UI
  - broadcast `WrongAnswerMessage(offendingPlayerId)`
  - update alert meter
  - queue the enemy wave
- On the LAN client:
  - handle `WRONG_ANSWER`
  - close the terminal immediately
- In debug mode:
  - close both terminal UIs in the same frame
- Input focus must return to gameplay immediately.

## Bug 9 — Restart state is incomplete

**Verdict: Real and important.**

The current restart path resets several objects but does not visibly reset every persistent Level 1 flag and gate.

Required repair:

- Reset:
  - `level1Complete`
  - `reactorUnlocked`
  - both entrance gates
  - exit gate
  - pressure-plate booleans
  - player health and visual state
  - enemy lists and pending spawn queues
  - attack cooldowns
  - mission-failure flags
  - result-presented flag
  - popups and terminal focus
  - alert meter
  - player positions and cameras
  - remote enemy state
- Add `Level1Map.resetGates()` or an equivalent single map reset method.
- Use one shared `resetRuntimeState()` method instead of three slightly different restart blocks.

---

# 2. Safe branch workflow in VS Code

Open the NOX-SYNC project folder in VS Code. Open **Terminal → New Terminal**.

Run these commands one at a time:

```powershell
git status
git remote -v
git fetch origin
git switch Fabian
git pull --ff-only origin Fabian
git switch -c fix/fabian-level1-bugs
git status
```

Expected final line:

```text
On branch fix/fabian-level1-bugs
```

Create a clean checkpoint before Claude edits anything:

```powershell
git tag before-fabian-level1-fix
```

Do not continue if `git status` shows unexpected modified files. Back them up or commit them first.

---

# 3. Use Claude Code in VS Code

## Extension route

1. Press `Ctrl + Shift + X`.
2. Search for **Claude Code**.
3. Install the official Anthropic extension.
4. Open any project file.
5. Click the Spark icon in the editor or Activity Bar.
6. Sign in.
7. Open this repository folder, not an individual Java file.

## Terminal route

In PowerShell:

```powershell
irm https://claude.ai/install.ps1 | iex
claude --version
claude
```

Start Claude from the repository root—the folder containing `gradlew.bat`.

---

# 4. Give Claude the task safely

Do not ask Claude to fix every issue blindly in one edit. Use the prompts in `CLAUDE_CODE_PROMPTS.md` in order.

At the beginning, tell Claude:

```text
Read CLAUDE.md and BUG_AUDIT.md first. Do not edit anything yet. Inspect the Fabian-derived code and report the exact files and methods involved. Then wait for approval.
```

After Claude gives its plan, proceed task by task.

For every task, require Claude to:

1. Show the intended files.
2. Make the smallest coherent change.
3. Run the compile/build commands.
4. Show `git diff --check`.
5. Summarize exactly what changed.
6. Stop before committing until the result is reviewed.

---

# 5. Build commands

Run from the repository root:

```powershell
.\gradlew.bat clean
.\gradlew.bat core:compileJava
.\gradlew.bat launcher:compileJava
.\gradlew.bat lwjgl3:compileJava
.\gradlew.bat test
```

Then run the JavaFX launcher:

```powershell
.\gradlew.bat launcher:run
```

For direct game launch:

```powershell
.\gradlew.bat lwjgl3:run
```

---

# 6. Commit sequence

Use separate commits so a bad fix can be reverted without losing everything.

```powershell
git add core/src/main/java/io/github/fableops/EnemySprites.java
git commit -m "fix: slice enemy sprite sheets without pixel bleeding"
```

```powershell
git add core/src/main/java/io/github/fableops/SwarmController.java
git commit -m "fix: stagger enemy wave spawns"
```

```powershell
git add core/src/main/java/io/github/fableops/Enemy.java
git add core/src/main/java/io/github/fableops/level1/network/EnemyStateMessage.java
git add core/src/main/java/io/github/fableops/level1/Level1Screen.java
git commit -m "fix: synchronize enemy hit and death animation"
```

```powershell
git add core/src/main/java/io/github/fableops/Player.java
git add core/src/main/java/io/github/fableops/level1/Level1Screen.java
git commit -m "feat: add player attack hurt and death feedback"
```

```powershell
git add core/src/main/java/io/github/fableops/level1/controller/Level1Controller.java
git add core/src/main/java/io/github/fableops/level1/Level1Screen.java
git commit -m "fix: close both terminals after a wrong answer"
```

```powershell
git add core/src/main/java/io/github/fableops/level1/Level1Map.java
git add core/src/main/java/io/github/fableops/level1/Level1Screen.java
git add core/src/main/java/io/github/fableops/Main.java
git add launcher
git commit -m "fix: reset level state and return to lobby after results"
```

The exact file list may change after Claude inspects the current local checkout. Do not stage unrelated files.

---

# 7. Push without touching main

```powershell
git status
git log --oneline --decorate -10
git push -u origin fix/fabian-level1-bugs
```

Open a pull request:

```text
fix/fabian-level1-bugs  →  Fabian
```

Test that PR branch first. After Fabian approves and the Level 1 test matrix passes, merge Fabian into main through another PR.

Never force-push `main`.
