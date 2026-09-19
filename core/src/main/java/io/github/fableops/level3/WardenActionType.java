package io.github.fableops.level3;

// The Warden's own turn actions (STORY.md's example list). Level3Controller rolls one of these
// each WARDEN_TURN and resolves its effect; this enum only carries the flavour verb for the log
public enum WardenActionType {

    DEPLOY_DRONE("deploys a defense drone"),
    ACTIVATE_TURRET("brings a security turret online"),
    RAISE_CONTAINMENT_BARRIERS("raises containment barriers"),
    DEFENSIVE_SCAN("runs a defensive scan, recalibrating"),
    INCREASE_CONTAINMENT_WARNING("raises the containment warning");

    private final String flavorVerb;

    WardenActionType(String flavorVerb) {
        this.flavorVerb = flavorVerb;
    }

    public String flavorVerb() { return flavorVerb; }
}
