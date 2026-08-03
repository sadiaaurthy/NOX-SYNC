# CLAUDE_CODE_PROMPTS.md

Use these prompts in order. Do not paste all tasks at once.

---

## Prompt 0 — Inspection only

```text
Read CLAUDE.md, BUG_AUDIT.md and TEST_MATRIX.md. Inspect the current branch and confirm that it is fix/fabian-level1-bugs. Do not modify files yet.

Trace these flows with exact file and method names:
1. wrong puzzle answer → alert update → popup close → enemy spawn
2. enemy spawn → movement → hit → dying → removal
3. host enemy state → serialized message → client render
4. player attack input → damage → visual feedback
5. player death/alert failure → result UI → lobby navigation
6. restart → every Level 1 state field and gate

Compare your findings with BUG_AUDIT.md. Identify any mismatch. Then propose a small-commit repair plan and wait.
```

---

## Prompt 1 — Enemy sprite bleed and asset validation

```text
Fix only the enemy sprite-sheet slicing and filtering defect.

Inspect:
- core/src/main/java/io/github/fableops/EnemySprites.java
- assets/enenmyswarmspritesheet.png
- assets/enemyswarmkillanddestroyspritesheet.png

Requirements:
- Do not assume 1774x887 divides evenly into 8x4.
- Use deterministic integer boundaries based on col*width/columns and row*height/rows.
- Use Nearest filtering and ClampToEdge.
- Add clear validation/logging for missing or unexpected sheet layouts.
- Keep walk rows 0-3 and death rows 2-3.
- Do not crop the visible death effect.
- Do not change world collision or enemy draw alignment.
- Add a small focused test or diagnostic where practical.
- Compile core, launcher and lwjgl3.
- Run git diff --check.
- Show the diff summary and stop. Do not commit.
```

Manual review after the edit:

```powershell
git diff -- core/src/main/java/io/github/fableops/EnemySprites.java
```

---

## Prompt 2 — Stagger wave spawning

```text
Fix only simultaneous enemy spawning.

Inspect SwarmController and every call site of spawnWave.

Requirements:
- spawnWave must queue enemies rather than add the whole wave in one frame.
- First spawn delay about 0.15 seconds.
- Interval between enemies 0.35 seconds, defined as a named constant.
- Preserve wave sizes 2,3,4,5,6.
- Preserve the offending player's side.
- Preserve collision-safe spawn-position selection.
- update(delta, ...) must release due pending spawns.
- reset() must clear active enemies, pending enemies and mistake counters.
- Do not block the render thread and do not use Thread.sleep.
- Add unit-testable logic where practical.
- Compile all Java modules and run git diff --check.
- Stop before commit.
```

---

## Prompt 3 — Enemy hit/death clarity and LAN synchronization

```text
Fix enemy hit and death feedback across host, debug and LAN client.

Inspect:
- Enemy.java
- SwarmController.java
- level1/network/EnemyStateMessage.java
- Level1Screen.java
- network message parsing/dispatch code

Requirements:
- Keep host authoritative.
- EnemyStateMessage must carry enough render state for the client:
  x, y, facing direction, walk state time, dying flag, death time and death-facing side.
- Do not independently simulate enemy death on the client.
- Make deserialization tolerate an older x,y-only entry if reasonably possible.
- Remote enemies must render death frames, not a fixed walk frame.
- Add a brief visible hit flash or equivalent clear hit feedback.
- Dying enemies must stop chasing and damaging.
- An enemy has 30 HP and must enter dying after two 15-damage hits.
- It must remain visible until the full death animation duration completes.
- Compile all modules and run git diff --check.
- Explain the message format before and after.
- Stop before commit.
```

---

## Prompt 4 — Player attack, hurt and death feedback

```text
Add player combat visual states without changing combat balance.

Inspect Player.java and Level1Screen input/combat code.

Requirements:
- Add visual states or timers for ATTACK, HURT and DEAD.
- Trigger attack feedback on every valid attack key press, even when no enemy is in range.
- Debug controls remain P1=F and P2=Right Shift.
- A LAN player's local attack key remains F.
- Hurt feedback must trigger when health decreases.
- Death feedback must trigger at zero health.
- If no approved attack/hurt/death sprite sheets exist, implement a clean procedural fallback:
  short directional attack lunge, brief red hurt flash, death rotation/fade.
- Do not pretend that walk sheets are attack sheets.
- Add syncHealth(newHealth) or equivalent so network updates trigger state transitions.
- resetCombatState() or equivalent must restore health and all visual timers.
- Restore SpriteBatch color/transform state after drawing.
- Compile all modules and run git diff --check.
- Stop before commit.
```

---

## Prompt 5 — Close both terminal popups after wrong answer

```text
Fix wrong-answer UI synchronization.

Trace Level1Controller, Level1Screen, WrongAnswerMessage and the client message switch.

Requirements:
- On any wrong submission, gameplay terminal UI must close immediately for both players.
- Host broadcasts WrongAnswerMessage with offendingPlayerId.
- Client handles WRONG_ANSWER and closes its local popup.
- Debug mode closes popupP1 and popupP2 in the same frame.
- Focus returns to gameplay without manual Escape.
- Alert Meter still increases exactly once by 15.
- Enemy wave is queued only once, on the authoritative host/debug simulation.
- A LAN client must not spawn its own authoritative duplicate wave.
- Avoid double-processing a locally submitted wrong answer.
- Compile all modules and run git diff --check.
- Stop before commit.
```

---

## Prompt 6 — Mission failure and return to lobby

```text
Fix the mission-failure/result navigation architecture.

Inspect:
- Main.java
- LobbyScreen.java
- Level1Screen.java
- ResultsScreen.java
- launcher ResultsWindow.java
- SessionLauncher and host/client lifecycle code

Requirements:
- Create one safe returnToLobby path owned by Main or a small navigation bridge.
- Closing or dismissing the JavaFX results window must return to LobbyScreen.
- The in-game results fallback must also return to LobbyScreen.
- Do not leave Level1Screen frozen behind the popup.
- Close host/client sockets and background threads.
- Dispose the old screen/resources exactly once.
- Do not call libGDX screen mutation from the JavaFX thread; post it to the libGDX application thread.
- On mission failure, Enter returns to lobby.
- Keep restart available separately as R for host/debug.
- A client must not be able to command an unauthorized authoritative restart.
- The result UI must say what Enter and R do.
- Compile all modules and run git diff --check.
- Stop before commit.
```

---

## Prompt 7 — Complete restart/reset

```text
Centralize and complete the Level 1 reset path.

Inspect every mutable Level1Screen, Level1Map, Player, SwarmController and controller state field.

Requirements:
- Add one resetRuntimeState method used by host, debug and client reset handling where appropriate.
- Reset level1Complete, reactorUnlocked, entrance gates, exit gate, pressure plates, popup state, focus, player positions/cameras, player health/visual states, enemies, pending spawns, attack cooldowns, alert meter, mission-failure flags, result-presented flags and remote enemy state.
- Add Level1Map.resetGates() or a single equivalent map reset method.
- Ensure a restart after the reactor was unlocked returns to a true fresh Stage 1.
- Do not create duplicate controller listeners or duplicate network threads.
- Compile all modules and run git diff --check.
- Stop before commit.
```

---

## Prompt 8 — Final review

```text
Perform a final regression review without making speculative redesigns.

1. Run:
   .\gradlew.bat clean
   .\gradlew.bat test
   .\gradlew.bat core:compileJava
   .\gradlew.bat launcher:compileJava
   .\gradlew.bat lwjgl3:compileJava
   git diff --check
   git status --short

2. Review every changed file for:
   duplicate damage or alert events,
   client-side enemy authority,
   stale reset fields,
   thread-unsafe JavaFX/libGDX calls,
   double disposal,
   sprite batch color not restored,
   incompatible message parsing,
   changed controls.

3. Map every item in TEST_MATRIX.md to either:
   PASS by automated check,
   READY FOR MANUAL TEST,
   or BLOCKED with exact reason.

Do not claim manual gameplay passed unless it was actually run and observed.
```
