# FableOps: Synchronized Survival

A 2-player cooperative LAN top-down survival game built with Java 21, libGDX, and JavaFX.

Developed for the CSE 4402: Visual Programming Lab course at the Islamic University of Technology (IUT).

---

## Presentation Video

* **Video Demonstration:** [https://youtu.be/o09a2ESUpiE]
* **GitHub Repository:** https://github.com/sadiaaurthy/NOX-SYNC

---

## Project Overview

FableOps is a 2-player cooperative game played over a local area network. Two players connect as a stabilization team sent into Meridian Deep-Core Station, an underground research facility that went dark after its prototype clean-energy reactor (the Unstable Core) became unstable. The station's defense AI, the Warden, locked down the facility after safety overrides were bypassed, mistaking the operators for intruders.

The game requires two players to coordinate in real time:
* **The Breaker (Kade):** Melee-focused operator who clears obstacles and fights drones up close.
* **The Listener (Wren):** Technical operator who interacts with terminals and provides ranged fire support.

### SDG Alignment
The project aligns with two United Nations Sustainable Development Goals:
* **SDG 7 (Affordable and Clean Energy):** The plot focuses on containing a prototype clean-energy reactor to prevent environmental contamination and protect research investments.
* **SDG 9 (Industry, Innovation, and Infrastructure):** The game's scenario deals with the failure of critical infrastructure caused by bypassing safety protocols under time pressure. Progress requires following dual-operator safety protocols.

---

## Technologies Used

* **Java 21:** Primary programming language.
* **libGDX (1.13.1):** Core game engine handling 2D rendering, sprites, camera management, and game loops.
* **LWJGL3:** Desktop backend for window creation and OpenGL context handling.
* **JavaFX 21:** Used for the main menu launcher, operator selection screen, and scenario dialogue windows.
* **Java Sockets (`java.net`):** Custom TCP socket architecture for local multiplayer synchronization.
* **Gradle:** Multi-project build automation (`launcher`, `core`, `lwjgl3`).

---

## Implementation Details

### 1. Hybrid JavaFX and libGDX Architecture
The project combines JavaFX and libGDX to balance UI layout flexibility with fast 2D rendering:
* **Launcher (`launcher` module):** Uses JavaFX with FXML and CSS to manage the main menu, host/join controls, character selection, and pre-level story dialogs.
* **Game Window (`core` and `lwjgl3` modules):** Runs libGDX on top of LWJGL3, capped at 30 FPS to limit hardware load during split-screen rendering.
* **Pre-warmed Context:** Starting an LWJGL3 window takes roughly 440ms. The launcher initializes the libGDX window in a background thread while players are still in the menu. When the match starts, the JavaFX stage hides and the game window displays immediately with no loading pause.

### 2. Dual-Socket Networking
The multiplayer runs on an authoritative host model over two separate TCP sockets. It is tested and functional across separate devices—both over a local Wi-Fi network and across different Wi-Fi networks using Radmin VPN:
* **Movement Stream (Port 9090):** The client polls keyboard inputs and sends a `PlayerInput` packet to the host. The host updates player coordinates, runs collision checks, and broadcasts a `WorldState` packet back at 60 Hz.
* **Event Channel (Port 9091):** Runs a line-based protocol (`TYPE|body`) for discrete game events like terminal inputs, door states, health sync, and loot pickups. A message queue parks incoming packets if they arrive while a new level screen is still loading, preventing dropped events.
* **Radmin VPN:** It allows the players connect using IP address in two different wi-fi.
* **Role Negotiation:** If both players try to select the same role at the same time, the host takes priority and the client automatically switches to the other operator, avoiding a network deadlock.

### 3. Split-Screen Viewports and Cameras
* The game renders split-screen locally on both machines using `SplitScreen.java`.
* Each half of the screen has its own `OrthographicCamera` tracking its respective player.
* Camera positions are clamped to map boundaries so viewports never scroll outside the playable area.

### 4. Collision and Pathfinding
* **Collision Masks:** Obstacles are defined by dedicated collision bitmap images (`Level1Mapcollision.png`, `Level2Mapcollision.png`). The game samples pixel alpha to block illegal moves.
* **Separated Hitboxes:** Feet hitboxes check wall collisions, while taller body hitboxes handle enemy contact damage and melee weapon reach.
* **Drone Steering:** Swarm enemies follow the nearest player. If a drone's progress drops below 35% of its speed for 0.35 seconds, it enters a 0.55-second detour state to slide around the wall.

### 5. Combat and Ballistics
* **Melee:** Basic swings deal 15 damage in a 120-unit forward radius, playing through an 8-frame attack animation.
* **Sidearm:** Fires up to 520 units. Targets are filtered within a 35-degree forward cone (`AIM_COS = 0.82f`). The shot traces line-of-sight against the collision mask in 12-unit steps so bullets cannot pass through walls. The weapon uses a 12-round magazine, 24 spare rounds, and a 1.5-second reload cooldown.

### 6. Cooperative Mechanics
* **Level 1 Terminals:** Three stages (binary conversion, symbol cipher, cross-dependent formula). Incorrect submissions increase the Alert Meter by 15 points and spawn enemy waves. Players must stand on two separate pressure plates at the same time to open the exit door.
* **Level 2 Core Transport:** Carrying the reactor core increases enemy wave frequency from 8 seconds to 4 seconds and increases wave sizes. High-tier gear caches remain locked until the core is being carried.
* **Shared Inventory:** Players have a 5x5 inventory grid and a single shared slot. Putting an item in the shared slot allows the other player to take it, enabling sharing of medkits and ammo mid-combat.

---

## Controls

| Action | Host / Player 1 | Client / Player 2 | Debug Mode (Single PC) |
|---|---|---|---|
| Movement | W, A, S, D | W, A, S, D | P1: WASD / P2: Arrow Keys |
| Melee Attack | Left Mouse Button | Right Mouse Button | P1: Left Click / P2: Right Click |
| Shoot Sidearm | Hold Attack | Hold Attack | P1: Left Click / P2: Right Click |
| Reload Weapon | R | R | P1: R / P2: Right Ctrl |
| Interact (Terminals / Core) | E | E | E |
| Pick Up Loot | G | G | G |
| Toggle Inventory | 1 | 2 | P1: 1 / P2: 2 |
| Navigate Inventory | W, A, S, D | W, A, S, D | Movement Keys |
| Toggle Collision Overlay | F1 | F1 | F1 |
| Cycle UI Scale | F2 | F2 | F2 |
| Exit / Cancel | ESC | ESC | ESC |

---

## How to Build and Run

### Requirements
* JDK 21 installed and configured on PATH.

### Option 1: VS Code (Recommended)
Press `Ctrl+Shift+B`. 

This executes the default build task configured in `.vscode/tasks.json` (`Build FableOps` followed by `Run FableOps`). It runs `./gradlew launcher:installDist` and starts the installed application binary directly, ensuring Gradle does not consume background memory or CPU while playing.

### Option 2: Terminal

**Windows:**
```powershell
.\gradlew.bat launcher:installDist
.\launcher\build\install\launcher\bin\launcher.bat
```

**Linux / macOS:**
```bash
./gradlew launcher:installDist
./launcher/build/install/launcher/bin/launcher
```

### Connection Options
Multiplayer works across separate PCs using either local LAN or Radmin VPN:
* **LAN Play:** The host selects `H` on the main menu to display their local IP address. The client inputs that IP address and selects `J`.
* **Radmin VPN (Different Wi-Fi / Remote):** Both players connect to the same network in Radmin VPN. The host selects `H` to find their Radmin IP address. The client inputs the host's Radmin IP and selects `J` to connect.
* **Local Play (Same PC):** Run the launch command twice. Host on the first window (`H`), then select `L` on the second window to connect through `127.0.0.1`.
* **Debug Mode:** Press `D` on the launcher to play both characters on a single screen with split keyboard controls.
