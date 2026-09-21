package io.github.fableops.level3;

// A short defensive response inside the existing Warden turn. This is intentionally separate
// from PlayerActionType: normal player actions remain owned by TurnManager, while reactions only
// answer the single enemy attack that is currently telegraphed.
public enum ReactionType {
    NONE("Take Hit"),
    SIDEARM("Sidearm"),
    SHIELD("Shield"),
    MEDKIT("Medkit"),
    // Appended so the ordinals on the event channel stay stable. Answers a turret alert only: it
    // spends nothing, it prepares the operator (which releases the turret) and the charge itself is
    // thrown afterwards with Y, as the Use TNT turn action.
    TNT("TNT");

    private final String label;

    ReactionType(String label) {
        this.label = label;
    }

    public String label() { return label; }
}
