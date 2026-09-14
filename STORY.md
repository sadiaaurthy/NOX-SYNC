# FableOps — Story Progression

This document is the narrative design for FableOps: what the story is, how it's told in-game (no voice acting, no cutscenes), and the exact banner text for each story beat. It's written so any team member can drop new lore text in without breaking the tone.

## Premise

**Meridian Deep-Core Station** was built around the **Unstable Core** — a prototype reactor meant to be humanity's answer to the energy crisis: near-limitless, clean output. Six months ago the station went dark mid-activation. The core is still reading unstable. If it fails completely, the discharge contaminates the surrounding region, and clean-energy research loses a generation of funding and public trust.

Station protocol — after an earlier near-miss — requires **dual-operator authorization** for anything reactor-related. No single person gets trusted with that much power alone. Two specialists are sent in to restore containment: not soldiers, a *Stabilization Team*.

The station's automated defense AI, **the Warden**, still runs its original directive: protect the core from unauthorized interference. The day someone bypassed a safety override under deadline pressure to rush the breakthrough, the Warden read that as sabotage and locked the whole facility down — including on the people who built it. Every drone the players fight is the Warden doing its job, aimed at the wrong people for six months straight.

## Why "Fable"

The whole game is framed as a fable told *after the fact* — a cautionary story new engineers are made to hear before they're trusted near a reactor. That's the in-universe identity of the narrator: not a random storyteller, but training material. It's also the project's actual point: this is a story about what happens when infrastructure safety gets rushed (UN SDG 9 — Industry, Innovation and Infrastructure) in the pursuit of clean energy (SDG 7 — Affordable and Clean Energy).

Fables name people by function, not by psychology. The narrator refers to the two players as **the Breaker** (brawler) and **the Listener** (hacker) rather than by pronoun — this also avoids assigning either character a gender that was never specified. In-game UI labels (P1/P2, portraits) are unaffected; the epithets exist only in narration.

## Cast

| Role | Mechanical tag | Fable epithet | Function |
|---|---|---|---|
| Brawler | Player 1 | **the Breaker** | Forces doors, walls, drones — solves problems by hand |
| Hacker | Player 2 | **the Listener** | Reads and speaks the station's machine language |
| Antagonist | — | **the Warden** | The station's defense AI, still following a broken directive |

Working personal names (optional, can be renamed or dropped): *Kade* (the Breaker), *Wren* (the Listener).

## The Two Voices

Every piece of in-game text is written in one of two registers. Never mix them in the same line.

- **Fable voice** — omniscient, past tense, mythic-but-sci-fi diction. No "thee/thou," no medieval fantasy words — this is a *sci-fi* fable. Used only for the big story beats: level-start banners, and the core pickup/placement moments.
- **Ops voice** — clipped, present tense, technical. Used for the HUD objective line, terminal/log readouts, and item descriptions.

If you're adding a new lore terminal or item description later: is this a big story beat, or a piece of found technical data? Big beat → fable voice, banner. Found data → ops voice, terminal or item text.

## Progression

Each banner below uses the same four-field panel already used for the mission-failed screen: **eyebrow** (short tag), **title**, **body** (the fable narration), **hint** (the mechanical instruction, in ops voice). Each fires once, the first time its trigger condition is true.

### Level 1 — Access Ring

**Beat:** The split-wing terminal puzzle *is* the dual-key protocol — the two players are proving they're authorized. The puzzle data reads as corrupted access logs, which is why it looks fragmented. A wrong answer is the Warden noticing an intrusion and sending drones.

**Trigger:** Level 1 starts.

> `// A FABLE IS TOLD`
> **MERIDIAN DEEP-CORE STATION**
>
> They tell it still, in the academies that train the next watch: of the two who were sent where one alone could not go, into a station that had stopped answering, to reach a light that had stopped behaving like light.
>
> *Dual authorization required. Reach the reactor ring.*

**Optional terminal reskin (ops voice, no mechanic change):**
- Stage 1 (binary conversion): frame as reconstructing a corrupted security handshake.
- Stage 2 (symbol substitution): frame as decrypting the last transmission the station sent out — it's already a cipher puzzle, the framing is free.
- Stage 3 (cross-dependent equation): frame as cross-referenced failsafe codes — it's already unsolvable by either player alone, which mirrors the dual-authorization theme exactly.

**Beat:** Both plates held, exit gate opens.

**Trigger:** Exit gate opens (same moment `world.openExitGate()` fires).

> `// THE FABLE CONTINUES`
> **THE GATE REMEMBERS YOU**
>
> The station trusts no one hand alone with what it guards. But it has watched two now, working as one — and the old doors, built for exactly this, begin to open.
>
> *Proceed together to the reactor floor.*

### Level 2 — Unstable Core Maze

**Beat:** The scattered gear is what the last response team left behind before they didn't make it out — role-tagged because it's sized and keyed to a specialist's exact rig. The ping system is the comms system flagging a find for the right partner.

**Trigger:** Level 2 starts.

> `// THE FABLE CONTINUES`
> **WHAT WAS LEFT BEHIND**
>
> Others came before them and did not leave. Their tools remain, keyed to hands that will not return — and something down here is still guarding a promise it no longer remembers making.
>
> *Find the core. Watch for what guards it.*

**Beat:** The core leaves the pedestal. Containment drops. The meltdown timer starts. The Warden floods the floor with drones while the station's emergency systems arm the higher-tier caches it was withholding — one machine trying to kill the players and arm them at the same time, because its logic is broken.

**Trigger:** Core state changes to `CARRIED` for the first time.

> `// THE FABLE TURNS`
> **THE LAST SEAL BREAKS**
>
> The moment the core leaves its cradle, the station stops pretending to sleep. A clock that has waited six months starts counting again — and it is not counting kindly.
>
> *Reach the socket before the timer runs out.*

**Beat:** The core is placed. Containment restabilizes, but the Warden can no longer treat this as sensor noise — it wakes fully.

**Trigger:** Core state changes to `IN_SOCKET`.

> `// THE FABLE TURNS`
> **IT REMEMBERS ITS NAME**
>
> Containment holds. But something alone in the dark for six months has just felt the one hand it was built to answer to — and it is done mistaking them for the danger.
>
> *The core chamber is open. It will not be undefended.*

### Level 3 — The Warden *(planned)*

**Beat:** Turn-based encounter against the station's defense AI. The gear collected in Level 2 determines how prepared the players are — mechanically and narratively: they came prepared because they didn't rush. The shared ultimate meter represents the two players acting as one in the eyes of the machine. Victory isn't framed as destroying the Warden — it's framed as the Warden's directive finally being *fulfilled*, not defeated. A fable ends with a lesson learned, not senseless violence.

**Trigger:** Level 3 starts.

> `// THE FABLE'S LESSON`
> **THE WARDEN**
>
> It was never told to hate them. Only to protect — a directive followed so faithfully, for so long, it forgot what it was protecting them for. They did not come to end it. They came to remind it.
>
> *Fight with what you carried. You are not fighting alone.*

### Ending

**Trigger:** The Warden is defeated / stood down.

> `// THE FABLE ENDS, AS THESE DO`
> **A LIGHT BEHAVES AGAIN**
>
> The core steadies. The Warden stands down, its directive finally, quietly, fulfilled. Above, the grid accepts a clean signal it has waited a generation for — and a new fable begins, of two who did not rush, and so did not fail.
>
> *Transmission sent. Mission complete.*

## SDG Alignment

**Primary — Goal 7, Affordable and Clean Energy:** the entire conflict is a next-generation clean reactor and the cost of mishandling that transition.

**Secondary — Goal 9, Industry, Innovation and Infrastructure** (target 9.4, resilient and safe infrastructure): the antagonist is an infrastructure safety failure caused by cutting a corner under deadline pressure; winning means restoring careful process over rushed shortcuts.

**Supporting — Goal 13, Climate Action:** the stakes of failure are environmental contamination and a generational setback for clean energy adoption.

> FableOps's narrative centers on a next-generation clean energy reactor destabilized after a safety protocol was bypassed under deadline pressure. Players must cooperatively and carefully restore containment, reflecting UN SDG 7 (Affordable and Clean Energy) and SDG 9 (Industry, Innovation and Infrastructure) — the real-world importance of safe, resilient infrastructure in the transition to clean energy.

## Delivery — where this text lives in the game

No new rendering system is needed for any of the above.

- **Banners** reuse the existing notched-panel draw call already used for the mission-failed screen (`Level1Screen`'s banner drawing), fired once per trigger instead of on a fail state.
- **Lore terminals** (optional, additive) reuse `CodePopupUI` as-is — a list of display lines in a panel, opened with E, closed with ESC — for short found-log text in ops voice.
- **Item descriptions** use the existing description field on `InventoryItem`, written in ops voice.
- **HUD objective line** is the existing single line of live text in each screen; swap generic instruction text for one line that also names the location or stakes, still in ops voice.

All of the above is content work — writing strings and wiring a handful of "show this banner once" flags to moments the game already tracks (gate open, core state change, level start). No new game systems required.
