package io.github.fableops.level3;

// The Warden's five narrative states (STORY.md). No health, no "defeated" - only a directive that
// eventually resolves. Stability crossing a threshold advances DEFENSE_ACTIVE -> MEMORY_RECOVERY ->
// DIRECTIVE_CONFLICT; the shared dual-authorization meter (tracked only during DIRECTIVE_CONFLICT)
// then advances DIRECTIVE_CONFLICT -> DUAL_AUTHORIZATION -> STAND_DOWN
public enum WardenState {
    DEFENSE_ACTIVE,
    MEMORY_RECOVERY,
    DIRECTIVE_CONFLICT,
    DUAL_AUTHORIZATION,
    STAND_DOWN;

    // Only the final state shows the calm blue core; every earlier state reads as a threat
    public boolean isStoodDown() {
        return this == STAND_DOWN;
    }
}
