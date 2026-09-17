package io.github.fableops.level3;

import io.github.fableops.Role;

// Turn actions offered during PLAYER_TURN. Each operator sees their own role's three actions
// (Role.BREAKER / Role.LISTENER, see STORY.md), plus USE_ITEM when they're carrying something
// usable from Level 2. Level3Controller decides each action's effect; this enum only carries the
// menu text and the flavour verb used to build the round's log line
public enum PlayerActionType {

    BREAKER_DAMAGE_DEFENSES(Role.BREAKER, "Damage Defenses",
        "forces the housing off one of the Warden's defense units"),
    BREAKER_DISABLE_DRONE(Role.BREAKER, "Disable Drone",
        "grabs for the nearest drone's kill switch"),
    BREAKER_INTERACT_MECHANISM(Role.BREAKER, "Force Mechanism",
        "wrenches at the containment override by hand"),

    LISTENER_SCAN_WARDEN(Role.LISTENER, "Scan Warden",
        "reads the Warden's active processes"),
    LISTENER_REDUCE_SUBROUTINE(Role.LISTENER, "Reduce Subroutine",
        "throttles a hostile subroutine mid-cycle"),
    LISTENER_RECOVER_LOGS(Role.LISTENER, "Recover Logs",
        "pulls at what the station buried"),

    // role is null: whichever operator is carrying a usable item sees this as their fourth option
    USE_ITEM(null, "Use Item", "reaches for what they carried");

    private final Role role;
    private final String label;
    private final String flavorVerb;

    PlayerActionType(Role role, String label, String flavorVerb) {
        this.role = role;
        this.label = label;
        this.flavorVerb = flavorVerb;
    }

    public Role role() { return role; }

    public String label() { return label; }

    public String flavorVerb() { return flavorVerb; }

    // The three fixed actions for one role, in menu order
    public static PlayerActionType[] optionsFor(Role role) {
        return (role == Role.BREAKER)
            ? new PlayerActionType[]{BREAKER_DAMAGE_DEFENSES, BREAKER_DISABLE_DRONE, BREAKER_INTERACT_MECHANISM}
            : new PlayerActionType[]{LISTENER_SCAN_WARDEN, LISTENER_REDUCE_SUBROUTINE, LISTENER_RECOVER_LOGS};
    }
}
