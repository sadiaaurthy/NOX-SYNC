# FableOps — Narrative Design & Progression

This document defines the narrative architecture for FableOps: world premise, character roles, in-game text delivery, and exact scenario beat copy. It is grounded directly in the codebase mechanics (`StoryBeat.java`, `Role.java`, `Level1Screen.java`, `Level2Screen.java`, `StoryGate.java`) to keep writing consistent across development.

---

## 1. Premise

**Meridian Deep-Core Station** was constructed to house the **Unstable Core** — a prototype zero-emission reactor designed as a scalable breakthrough for clean energy generation (UN SDG 7). Six months prior to the events of the game, the facility went dark during initial power synchronization. The core remains trapped in an unstable state. Total containment failure will vent high-energy contamination across the sector, terminating decades of clean-energy research and public trust.

Following an earlier industrial incident, facility safety regulations mandate **dual-operator authorization** for all critical reactor subsystems. No single technician possesses the cryptographic keys or physical access to manipulate the containment ring alone. Two specialists are deployed to re-establish containment: a *Stabilization Team*, not an assault squad.

The station’s automated defense construct, **the Warden**, continues running its base directive: protect core integrity against unauthorized tampering. When facility management bypassed safety interlocks under deadline pressure to force synchronization, the Warden classified the manual override as industrial sabotage and triggered a facility-wide security purge. Every automated drone encountered by the operators is the Warden executing its baseline protocol against the very personnel meant to maintain it.

---

## 2. Narrative Framing: The Sci-Fi Fable

The narrative is structured as a technical fable recounted in post-incident engineering academies — a cautionary case study taught to prospective operators before they receive field clearance around high-output infrastructure (UN SDG 9, Target 9.4). The narrator is not an external storyteller, but an institutional training voice analyzing what occurs when safety margins are compromised for expediency.

Fables identify participants by their operational function:
- **Kade** is addressed as **the Breaker** (melee specialist, vanguard).
- **Wren** is addressed as **the Listener** (systems analyst, hacker).
- **The Warden** serves as the institutional antagonist — a rule-bound defense system operating on obsolete lockouts.

This distinction keeps personal identities tied to system UI and comms, while scenario banners frame their actions through the functional lens of the cautionary tale.

---

## 3. Cast & Roles

| Character | Call Sign (`Role.java`) | Archetype / Fable Epithet | Spritesheet | Operational Function |
|---|---|---|---|---|
| **Kade** | `KADE` | `THE BREAKER` | `brawlspritesheet` | Point defense, crowd control, physical gate holding. Clears drone swarms by hand. |
| **Wren** | `WREN` | `THE LISTENER` | `hackerspritesheet` | Data decryption, terminal overrides, precision fire. Interfaces directly with station machine code. |
| **The Warden** | — | `THE WARDEN` | — | Station automated defense intelligence. Enforces security lockdowns until safety interlocks are verified. |

- In the launcher selection UI and networking payloads, operators select `BREAKER` or `LISTENER`.
- In telemetry and player HUD cards, they appear as **KADE** and **WREN**.
- In the scenario framing banners, the narrator refers to them by function: **the Breaker** and **the Listener**.

---

## 4. The Two Narrative Registers

Text in FableOps is written in two strictly separated registers. They are never combined within the same UI element:

1. **Fable Voice**
   - **Form:** Third-person omniscient, reflective past tense, measured sci-fi diction.
   - **Usage:** Pre-level scenario intro windows (`story.fxml`), level transition banners, core pickup/socketing beats, mission complete debrief.
   - **Rule:** No archaic fantasy vocabulary (*thee*, *hark*, *behold*). The mythic cadence comes from procedural weight and industrial scale, not fantasy tropes.

2. **Ops Voice**
   - **Form:** Clipped, direct, present-tense technical shorthand.
   - **Usage:** HUD objective lines, countdown timers, terminal puzzle prompts (`CodePopupUI`), item descriptions (`InventoryItem`), error messages.
   - **Rule:** Readouts use concrete telemetry, subsystem tags, and explicit operational instructions.

---

## 5. Scenario Progression & Banner Copy

Pre-level story scenes run through the JavaFX launcher interface (`story.fxml`, driven by `StoryController.java` and `StoryBeat.java`). Gameplay pauses behind the scenario modal until both players confirm readiness via `StoryGate` (`Enter` key).

### Level 1: Access Ring

#### Background & Mechanics
- **Emergency Blackout:** The station power grid is offline. Until the central reactor is unlocked, the floor is cast in darkness (`darknessMask`), restricting visibility to a moving circular spotlight centered on each operator.
- **Split-Wing Terminals:** Kade (West Wing) and Wren (East Wing) must independently access and clear three sequential terminal stages:
  1. *Stage 1:* Corrupted binary handshake validation.
  2. *Stage 2:* Decryption of the station’s final telemetry transmission.
  3. *Stage 3:* Cross-dependent logic equation requiring reciprocal input.
- **Alert Meter & Drone Incursion:** Incorrect terminal inputs increment the facility Alert Meter by +15 and immediately trigger automated drone deployments targeted directly at the offending operator's coordinates. Reaching 100% alert triggers total facility purge (mission failure).
- **Emergency Override:** Pressing `K` three times forces an emergency bypass over the puzzle stages, narratively mirroring the exact deadline shortcuts that precipitated the station's collapse.
- **Dual Pressure Plates:** Clearing all three stages restores full grid power, lifts the darkness mask, and opens the central security bulkheads. Opening the sector exit gate requires both Kade and Wren to occupy their respective pressure plates (`pressurePlateP1`, `pressurePlateP2`) simultaneously.

#### Scenario Modal: Game Start (`StoryBeat.START`)
```text
HEADING: MAIN SCENARIO #1 — ACCESS RING
NARRATION:
They tell it still, in the academies that train the next watch: of the two who were sent where one alone could not go, into a station that had stopped answering, to reach a light that had stopped behaving like light.

[Scenario Data]
Category:        Main
Difficulty:      C
Clear Condition: Solve the split terminals together, hold both pressure plates, then walk through the exit gate side by side.
Time Limit:      None
Compensation:    Access to the reactor floor
Failure:         The alert meter maxes out, or an operator falls.
```

#### Mid-Level Event: Exit Bulkhead Opened (`world.openExitGate()`)
- **Register:** Fable Voice (Notched In-Game Banner)
- **Tag:** `// THE FABLE CONTINUES`
- **Title:** `THE GATE REMEMBERS YOU`
- **Body:** The station trusts no one hand alone with what it guards. But it has watched two now, working as one — and the old doors, built for exactly this, begin to open.
- **Instruction (Ops Voice):** *Proceed together to the reactor floor.*

---

### Level 2: Unstable Core Maze

#### Background & Mechanics
- **Debris Field & Previous Casualties:** The containment floor contains discarded supply caches and field equipment dropped by earlier recovery teams whose credentials expired in the dark.
- **Core Transport & Radiation Countdown:** Lifting the `UnstableCore` transitions its state from `ON_PEDESTAL` to `CARRIED`. This triggers an unyielding 45-second meltdown timer (`CORE_TIME_LIMIT = 45f`). Both operators take ambient thermal damage if the core is not seated before the timer expires.
- **Aggressive Drone Incursions:** While the core is carried, the Warden intensifies its suppression protocols, reducing drone wave spawn intervals from 8 seconds to 5 seconds (`WAVE_INTERVAL_CORE_TAKEN = 5f`).
- **High-Risk Salvage:** Tier-3 Rare Plating caches (`LootPremiumShield.png`) scattered across the maze remain locked until the core is actively in transit, forcing the team to balance direct extraction against opportunistic supply recovery.
- **Combat & Mutual Support:** A laser sidearm (`Gun.java`, 12-round magazine, 24 spare capacity, 30 kinetic damage) and field inventory handoffs allow operators to swap medkits and shield cells dynamically during retreats.
- **Stabilization Socket:** Depositing the core into the receiving cradle changes its state to `IN_SOCKET`, arresting the countdown, stabilizing regional radiation, and unsealing the transit shaft to Level 3.

#### Scenario Modal: Level 2 Start (`StoryBeat.LEVEL_2`)
```text
HEADING: MAIN SCENARIO #2 — UNSTABLE CORE MAZE
NARRATION:
Others came before them and did not leave. Their tools remain, keyed to hands that will not return — and something down here is still guarding a promise it no longer remembers making.

[Scenario Data]
Category:        Main
Difficulty:      B
Clear Condition: Carry the Unstable Core to the reactor socket, then leave through the exit together.
Time Limit:      45 Seconds (Active Transport)
Compensation:    Gear the last team left behind. Rare caches open only while the core is carried.
Failure:         Meltdown timer expiration, or an operator falls.
```

#### Mid-Level Event: Core Lifted (`CoreObject.State.CARRIED`)
- **Register:** Fable Voice (Notched In-Game Banner)
- **Tag:** `// THE FABLE TURNS`
- **Title:** `THE LAST SEAL BREAKS`
- **Body:** The moment the core leaves its cradle, the station stops pretending to sleep. A clock that has waited six months starts counting again — and it is not counting kindly.
- **Instruction (Ops Voice):** *Seat the core before containment collapses. Evade incoming interceptors.*

#### Mid-Level Event: Core Seated (`CoreObject.State.IN_SOCKET`)
- **Register:** Fable Voice (Notched In-Game Banner)
- **Tag:** `// THE FABLE TURNS`
- **Title:** `IT REMEMBERS ITS NAME`
- **Body:** Containment holds. But something alone in the dark for six months has just felt the one hand it was built to answer to — and it is done mistaking them for the danger.
- **Instruction (Ops Voice):** *Core stabilized. Exit sector via the primary lift.*

---

### Level 3: The Warden (Planned Final Encounter)

#### Background & Mechanics
- **Encounter Philosophy:** The final confrontation against the Warden is structured around directive resolution rather than brute attrition.
- **Loadout Synergy:** Gear, ammunition, and defensive plating preserved during Level 2 dictate operational endurance.
- **Cooperative Resolution:** While Kade parries drone deployments and anchors kinetic defense lines, Wren executes cryptographic handshakes to feed genuine stabilization credentials into the AI's core logic.
- **Victory State:** The Warden is not obliterated; its lockdown directive is satisfied. The AI recognizes the dual-operator signatures, verifies thermal stability, and revokes the facility purge order.

#### Scenario Modal: Level 3 Start (`StoryBeat.LEVEL_3`)
```text
HEADING: MAIN SCENARIO #3 — THE WARDEN
NARRATION:
It was never told to hate them. Only to protect — a directive followed so faithfully, for so long, it forgot what it was protecting them for. They did not come to end it. They came to remind it.

[Scenario Data]
Category:        Main
Difficulty:      A
Clear Condition: Stand the Warden down. Fight with what you carried.
Time Limit:      None
Compensation:    Full facility restoration
Failure:         Both operators fall.
```

---

### Ending: Mission Cleared

#### Trigger: Warden Stand-Down (`StoryBeat.ENDING`)
```text
HEADING: MAIN SCENARIO CLEARED — A LIGHT BEHAVES AGAIN
NARRATION:
The core steadies. The Warden stands down, its directive finally, quietly, fulfilled. Above, the grid accepts a clean signal it has waited a generation for — and a new fable begins, of two who did not rush, and so did not fail.

[Scenario Data]
Result:          Cleared
Containment:     Restored
Compensation:    A clean signal for the grid
Transmission:    Sent. Mission complete.
```

---

## 6. Real-World Alignment (United Nations SDGs)

FableOps integrates real-world infrastructure and energy challenges directly into its core conflict:

1. **SDG 7 — Affordable and Clean Energy**
   - The Unstable Core represents prototype generation technology capable of high-output zero-emission power.
   - The central crisis demonstrates that transitioning to clean energy sources requires rigorous containment, deliberate operational pacing, and verified handling protocols.

2. **SDG 9 — Industry, Innovation and Infrastructure (Target 9.4)**
   - The catastrophe at Meridian Deep-Core Station was provoked by bypassing safety interlocks under schedule pressure.
   - Victory requires adherence to verified safety procedures (dual-key validation, reciprocal terminal inputs, paired pressure plate verification), demonstrating that resilient infrastructure relies on procedural discipline over expedient shortcuts.

3. **SDG 13 — Climate Action**
   - The high stakes of containment failure reflect the environmental risks inherent in managing volatile clean-tech prototypes during regional transitions.

---

## 7. Engine Integration & Technical Implementation

The narrative text is mapped to existing game modules without introducing artificial systems:

- **Scenario Overlays:** Managed via `StoryController.java` loading `story.fxml` and `story.css`. Text blocks and row matrices are populated directly from `StoryBeat.java`.
- **Synchronization Gate:** `StoryGate.java` intercepts state changes between scenes. When a story beat triggers, `StoryReadyMessage` synchronizes client and host confirmations before rendering game viewports.
- **Terminal Logic & Narrative Logs:** The 3-stage puzzle interface in `Level1Screen.java` uses `CodePopupUI.java` to deliver ops-voice telemetry readouts and cipher text.
- **Tactical In-Game Alerts:** Mid-level triggers (e.g., exit gate opening, core extraction) invoke notched split-screen banners using `Hud.java` drawing routines, previously shared with the failure alert HUD.
- **Item Telemetry:** Equipment names and attributes defined in `InventoryItem.java` and `LootField.java` follow standard ops-voice styling (e.g., `Rare Plating`, `Field Medkit`, `Laser Sidearm`).
