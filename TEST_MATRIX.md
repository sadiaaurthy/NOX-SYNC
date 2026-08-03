# TEST_MATRIX.md

Record PASS/FAIL and evidence for each row.

## A. Build and startup

| ID | Mode | Test | Expected |
|---|---|---|---|
| A1 | Build | `clean test` | BUILD SUCCESSFUL |
| A2 | Build | compile core, launcher, lwjgl3 | All successful |
| A3 | Launcher | `launcher:run` | JavaFX launcher opens |
| A4 | Direct | `lwjgl3:run` | Game opens without missing-asset error |

## B. Controls

| ID | Mode | Test | Expected |
|---|---|---|---|
| B1 | Debug | Press F as left player | P1 attack visual appears; nearby P1 enemy takes one hit |
| B2 | Debug | Press Right Shift as right player | P2 attack visual appears; nearby P2 enemy takes one hit |
| B3 | LAN client | Press F | Client sends P2 attack; host applies it |
| B4 | LAN client | Press Right Shift | No requirement for attack on separate client computer |

## C. Wrong answer and popups

| ID | Mode | Test | Expected |
|---|---|---|---|
| C1 | Debug | Both terminals open; submit wrong digit | Both close immediately |
| C2 | LAN | Client submits wrong digit | Client and host terminal UI close |
| C3 | LAN | Host submits wrong digit | Client and host terminal UI close |
| C4 | Any | One wrong submission | Alert rises exactly 15, not 30 |
| C5 | Any | Wrong submission | Only offending side gets wave |

## D. Staggered wave

| ID | Test | Expected |
|---|---|---|
| D1 | First mistake | 2 enemies appear with visible interval |
| D2 | Second mistake | 3 enemies appear with interval |
| D3 | Five or more mistakes | Wave caps at 6 |
| D4 | Restart during queued wave | No pending enemy appears after reset |
| D5 | Spawn placement | No enemy starts inside a wall |

## E. Enemy rendering and death

| ID | Mode | Test | Expected |
|---|---|---|---|
| E1 | Debug | Observe all walk directions | No neighboring-frame pixels |
| E2 | Debug | Hit enemy once | Clear hit feedback; enemy still alive |
| E3 | Debug | Hit same enemy twice | Enemy enters death state |
| E4 | Debug | Watch death | Full death animation, then removal |
| E5 | LAN host | Kill enemy | Host sees death |
| E6 | LAN client | Host kills enemy | Client sees matching death, not instant disappearance/fixed walk frame |
| E7 | Any | Touch dying enemy | No further contact damage |

## F. Player feedback and failure

| ID | Mode | Test | Expected |
|---|---|---|---|
| F1 | Debug | Enemy damages P1 | P1 hurt feedback |
| F2 | Debug | Enemy damages P2 | P2 hurt feedback |
| F3 | LAN | Remote health falls | Correct client hurt feedback |
| F4 | Any | Health reaches zero | Death feedback and mission failure |
| F5 | Any | Alert reaches 100 | Mission failure exactly once |
| F6 | Launcher | Dismiss result popup | Return to lobby |
| F7 | Launcher | Close popup using X | Return to lobby |
| F8 | In-game fallback | Press Enter | Return to lobby |
| F9 | Host/debug failure | Press R | Fresh Level 1 restart |

## G. Complete reset

| ID | Setup before reset | Expected after reset |
|---|---|---|
| G1 | Reactor unlocked | Entrance gates closed |
| G2 | Exit opened | Exit gate closed |
| G3 | `level1Complete=true` | false |
| G4 | Players on plates | Plate flags false |
| G5 | Players damaged/dead | Health 100; visual state clean |
| G6 | Active/dying enemies | All removed |
| G7 | Pending spawn queue | Empty |
| G8 | Attack on cooldown | Cooldown zero |
| G9 | Popup open | Closed |
| G10 | Stage 2 or 3 | Stage 1 |
| G11 | Result already shown | Result can show correctly on a later failure/completion |
| G12 | Remote enemy cache populated | Empty |

## H. Regression

| ID | Test | Expected |
|---|---|---|
| H1 | Complete Stage 1 correctly | Advances to Stage 2 |
| H2 | Complete Stage 2 correctly | Advances to Stage 3 |
| H3 | Complete Stage 3 | Reactor unlocks |
| H4 | Both pressure plates held | Level 1 completes |
| H5 | Collision overlay F1 | Still works |
| H6 | Debug K skip | Still works only in debug |
| H7 | Host/client movement | No regression |
| H8 | Repeated enter/exit | No duplicate socket threads or texture-disposal crash |
