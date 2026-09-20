package io.github.fableops.level3;

// A short defensive response inside the existing Warden turn. This is intentionally separate
// from PlayerActionType: normal player actions remain owned by TurnManager, while reactions only
// answer the single enemy attack that is currently telegraphed.
public enum ReactionType {
    NONE("Take Hit"),
    SIDEARM("Sidearm"),
    SHIELD("Shield"),
    MEDKIT("Medkit");

    private final String label;

    ReactionType(String label) {
        this.label = label;
    }

    public String label() { return label; }
}
