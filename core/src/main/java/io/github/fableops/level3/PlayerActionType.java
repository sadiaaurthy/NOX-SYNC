package io.github.fableops.level3;

import io.github.fableops.Role;

// Turn actions offered during PLAYER_TURN. Each operator sees their own role's actions
// (Role.BREAKER / Role.LISTENER, see STORY.md), plus USE_ITEM when they're carrying something
// usable from Level 2. Level3Controller decides each action's effect; this enum only carries the
// menu text and the flavour verb used to build the round's log line
public enum PlayerActionType {

    BREAKER_PHYSICAL_STRIKE(Role.BREAKER, "Physical Strike",
        "strikes the active defense housing"),
    BREAKER_WEAPON_ATTACK(Role.BREAKER, "Sidearm Shot",
        "fires the recovered sidearm at the active defense"),
    BREAKER_DISABLE_DRONE(Role.BREAKER, "Disable Drone",
        "grabs for the nearest drone's kill switch"),
    BREAKER_REPAIR_MECHANISM(Role.BREAKER, "Stabilize Mechanism",
        "reseats the containment mechanism by hand"),
    BREAKER_PROTECT_LISTENER(Role.BREAKER, "Protect",
        "moves between the Listener and the station's defenses"),
    BREAKER_SHIELD_DEFENSE(Role.BREAKER, "Shield Defense",
        "raises the recovered shield against the next response"),

    LISTENER_SCAN_WARDEN(Role.LISTENER, "Scan Warden",
        "reads the Warden's active processes"),
    LISTENER_REDUCE_SUBROUTINE(Role.LISTENER, "Reduce Hostility",
        "throttles a hostile subroutine mid-cycle"),
    LISTENER_RECOVER_LOGS(Role.LISTENER, "Recover Memory",
        "pulls at what the station buried"),
    LISTENER_AUTHORIZATION_ATTEMPT(Role.LISTENER, "Restore Authorization",
        "presents both operator signatures to the Warden"),
    LISTENER_SUPPORT_BREAKER(Role.LISTENER, "Support Breaker",
        "routes targeting data to the Breaker's next action"),

    USE_MEDKIT(null, "Use Medkit", "uses recovered medical supplies"),

    // Kept for event-channel compatibility with clients from the first Level 3 pass.
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

    // The fixed actions for one role, in menu order
    public static PlayerActionType[] optionsFor(Role role) {
        return (role == Role.BREAKER)
            ? new PlayerActionType[]{BREAKER_PHYSICAL_STRIKE, BREAKER_DISABLE_DRONE,
                BREAKER_REPAIR_MECHANISM, BREAKER_PROTECT_LISTENER}
            : new PlayerActionType[]{LISTENER_SCAN_WARDEN, LISTENER_REDUCE_SUBROUTINE,
                LISTENER_RECOVER_LOGS, LISTENER_AUTHORIZATION_ATTEMPT, LISTENER_SUPPORT_BREAKER};
    }

    public static int indexFor(Role role, PlayerActionType action) {
        if (action == USE_ITEM || action == USE_MEDKIT) return optionsFor(role).length;
        PlayerActionType[] options = optionsFor(role);
        for (int i = 0; i < options.length; i++) {
            if (options[i] == action) return i;
        }
        return 0;
    }
}
