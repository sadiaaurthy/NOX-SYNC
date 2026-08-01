# CLAUDE.md — Project Guide for Claude Code

This file is read automatically by Claude Code whenever it starts in this repository. Its purpose is to give you (Claude Code) the context you need to make correct decisions on this project without asking the user to re-explain the basics every session.

---

## Project Overview

**FableOps: Synchronized Survival** is a 2-player cooperative LAN-based survival game built in Java + libGDX for the CSE 4402 Visual Programming Lab course at Islamic University of Technology (IUT).

- Two players connect over a **local network (LAN)** — one hosts (uses their local IP), one joins as client.
- The game is played on **two separate laptops**, not split-screen.
- The visual style is **neon noir / futuristic cyberpunk**.
- Networking is **pure Java sockets** (no Kryonet, no Netty, no Photon). Host is authoritative for shared state.
- The project is a course deliverable; the grading emphasis is on clean object-oriented design, working multiplayer, and demonstrable gameplay — not AAA polish.

## Team

- **Aurthy (Sadia)** — repo owner
- **Mahim** — collaborator (works on `origin/Mahim` branch)
- **Fabian** — collaborator (works on `origin/Fabian` branch)

Both collaborator branches are merged into `main` when features are ready. Use `git merge --allow-unrelated-histories` only for the historical one-time merge issue that has already been resolved; for new merges, standard merges/PRs are correct.

## Tech Stack (locked — do not swap without asking)

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Game framework | libGDX (core + lwjgl3 desktop launcher) |
| Build | Gradle (wrapper in repo, use `./gradlew` / `gradlew.bat`) |
| Networking | Pure Java sockets (`java.net.Socket`, `java.net.ServerSocket`) |
| Serialization | JSON via Jackson OR simple line-delimited text — Jackson is preferred |
| Physics | libGDX Box2D (for collision and pressure-plate sensors) |
| Maps | Tiled `.tmx` files loaded via libGDX TmxMapLoader |
| IDE | VS Code (primary), IntelliJ IDEA acceptable |
| Version control | Git + GitHub (`sadiaaurthy/NOX-SYNC`) |

**Do not introduce:** Kryonet, Netty, Spring Boot (was previously considered, now dropped), Photon, Unity Networking, non-Java languages. If a task seems to need a new library, propose it in chat first — do not add it silently.

## Repository Layout

```
NOX-SYNC/
├── core/              ← shared game logic (screens, entities, networking, mechanics)
├── lwjgl3/            ← desktop launcher (LWJGL3-based)
├── assets/            ← sprites, maps, sounds, fonts
├── gradle/            ← Gradle wrapper files
├── build.gradle       ← root build config
├── settings.gradle    ← module declarations
├── gradle.properties
├── gradlew, gradlew.bat
├── .gitignore, .gitattributes, .editorconfig
└── CLAUDE.md          ← this file
```

## Game Design Summary

### Level 1 — Reactor Decoding Chamber
Three co-op puzzle stages on a reactor control interface. Wrong answers raise a **Reactor Instability Meter** and spawn enemy swarms. Full meter = level fails.

- **Stage 1 — Binary Conversion & Alternating Placement**: Random Odd and Even binary numbers are shown; Player A converts the Odd, Player B converts the Even; digits are entered alternating (Odd 1st → Even 1st → Odd 2nd → Even 2nd).
- **Stage 2 — Split Info & Conditional Ordering**: Player A sees positions + conditions (e.g. "Position 1: value > 5"); Player B sees the available values (e.g. `7, 2, 3`). They communicate and Player A places values into positions that satisfy the conditions.
- **Stage 3 — Distributed Equation Puzzle**: Each player sees only half of a linked equation system. They must communicate to solve it. **Player A enters Values 1 & 2; Player B enters Values 3 & 4** — entering the correct answer on the wrong terminal counts as a mistake.

### Level 2 — Unstable Core Maze
Dark maze with ambient enemy swarms. Players collect low/medium loot freely. Picking up the **Unstable Core** starts a shared Meltdown Timer, applies team-wide slow + damage-taken debuffs to BOTH players, and unlocks high-value loot (each high-value pickup spawns an elite swarm — push-your-luck). Core must be placed on an **Altar** (removes debuffs but timer continues). Then both players separate to **two pressure plates** and hold them simultaneously under continuous enemy attacks until the Level 3 door opens.

### Level 3
Unlocked at the end of Level 2. Design not yet fixed; do not implement Level 3 without an explicit spec.

## Coding Conventions

- **Package structure:** `com.fableops.<layer>.<feature>` — e.g. `com.fableops.core.network.HostServer`, `com.fableops.core.level.level1.BinaryPuzzleStage`.
- **Class per file**, `PascalCase` for classes, `camelCase` for methods and fields, `UPPER_SNAKE_CASE` for constants.
- **No static mutable state** except a single `GameContext` singleton passed through screens.
- **Never use `System.out.println` for game logic** — use libGDX's `Gdx.app.log(TAG, message)` so logs are consistent across platforms.
- **Networking messages are typed classes** (e.g. `PlayerMovedMessage`, `PuzzleAnswerMessage`) serialized as JSON. Do not use raw strings with position-based parsing.
- **Threading rule (critical):** libGDX runs on a single render thread. Never mutate game state from the network thread. Push incoming network messages onto a `ConcurrentLinkedQueue` and drain it inside the screen's `render()` method.

## Networking Model (host-authoritative)

- The **host** runs `ServerSocket` on a chosen port (default `54555`).
- The **client** connects via the host's LAN IP (e.g. `192.168.0.42`).
- The host is authoritative for:
  - Reactor Instability Meter
  - Meltdown Timer
  - Boss/enemy HP
  - Puzzle completion state
  - Pressure plate activation state
- Each client is authoritative for its own player's movement inputs; the host validates and broadcasts to the other.
- Both clients render locally from state broadcasts.

## Common Tasks and How to Handle Them

**When asked to add a new feature:**
1. Identify which module it lives in (`core`, `lwjgl3`, or `assets`).
2. Check whether a matching class already exists — extend it rather than creating a parallel one.
3. If the feature crosses the network boundary, add a message DTO before wiring the logic.
4. Follow existing code style in the file you are editing (indentation, brace style, etc.) even if it differs from your default.

**When asked to fix a bug:**
1. Reproduce the failure path in your head from the code before proposing a fix.
2. Prefer minimal, targeted patches — do not refactor surrounding code unless explicitly asked.
3. If the fix needs a test, add it under `core/src/test/java/` matching the source package.

**When asked to commit and push:**
1. Show `git status` first.
2. Group related changes into one commit with a clear message: `feat(level1): implement binary conversion puzzle stage 1` (Conventional Commits style).
3. Never commit `.class` files, `build/` output, or IDE-generated files — the `.gitignore` covers these but double-check.
4. Push to the current branch. Do NOT push directly to `main` unless the user says so explicitly — prefer feature branches merged via PR.

**When the user's request is ambiguous:**
- Ask ONE clarifying question rather than guessing. Aurthy prefers accuracy over speed; a five-second question saves an hour of undoing wrong work.

## Do Not Do

- Do not create new top-level Gradle modules without asking.
- Do not add dependencies to `build.gradle` without listing them in the reply.
- Do not delete files that look like teammate work (files last touched on `origin/Mahim` or `origin/Fabian` branches).
- Do not silently rewrite the README, `settings.gradle`, or `.gitignore`.
- Do not implement Level 3.
- Do not use `System.exit()`, `Thread.sleep()` on the render thread, or blocking I/O on the render thread.

## When You Are Genuinely Unsure

Say so plainly and ask. This project is graded on accuracy of implementation, not on how fast you can finish a task.
