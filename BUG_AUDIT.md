# BUG_AUDIT.md

## Scope

Audited areas:

- Fabian branch Level 1 architecture
- Main repository structure
- Enemy sprites and animation loading
- Enemy lifecycle and swarm spawning
- Player combat visuals
- Wrong-answer UI synchronization
- LAN enemy-state synchronization
- Mission failure, result presentation and lobby navigation
- Restart/reset completeness
- Level 1 design notes from the supplied Claude discussion PDF

## Ground truth that must remain unchanged

- Level 1 contains three puzzle stages.
- Wrong submissions add 15 Alert Meter points.
- Enemy waves begin with 2 enemies and grow to a maximum of 6.
- Player HP is 100.
- Enemy contact damage is 10 per second.
- Player attack damage is 15.
- Attack range is 120 units.
- Attack cooldown is 0.35 seconds.
- Debug P1 attacks with F.
- Debug P2 attacks with Right Shift.
- A LAN client attacks with F on its own computer.
- Enemies remain until killed or restart.
- Both pressure plates complete Level 1.

## Risk ranking

### P0 — release blockers

1. Mission failure does not complete navigation back to the lobby.
2. Wrong-answer terminal UI remains open while enemies attack.
3. Restart leaves persistent gate/completion state behind.
4. LAN clients cannot receive enemy death animation state.

### P1 — major gameplay defects

5. Wave enemies appear simultaneously.
6. Player attack/hurt/death feedback is missing.
7. Enemy sprite slicing can bleed neighboring pixels.

### P2 — polish/diagnostics

8. Enemy hit feedback is weak.
9. Sprite-sheet assumptions are not validated.
10. There is no automated regression test around reset and network serialization.

## Architectural direction

Use the host as the sole gameplay authority, but replicate enough render state for a client to display the same event. Do not run independent client-side enemy simulation.

Use one Level 1 reset method. Multiple restart blocks are how state such as `level1Complete`, gates and cooldowns become inconsistent.

Use one navigation path to the lobby. The JavaFX popup, in-game result panel and window-close action must all call the same path.

## Definition of done

A bug is not fixed merely because the project compiles. It is fixed only after the matching test in `TEST_MATRIX.md` passes in:

1. Debug mode.
2. Host mode with no client, where supported.
3. Two-computer LAN mode for network-sensitive behavior.
