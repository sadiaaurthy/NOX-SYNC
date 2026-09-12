package io.github.fableops.level2;

/**
 * Anything a player can walk up to and press E on. CoreObject is the first
 * implementation; future objectives (the altar, pressure plates, loot) can reuse the
 * same range-check/prompt/interact shape instead of each screen hand-rolling its own.
 */
public interface Interactable {
    boolean isInRange(float playerX, float playerY, float playerSize);

    /** Empty string means "in range but nothing to do right now" (e.g. not your Core to drop). */
    String getInteractionPrompt(int playerId);

    void onInteract(int playerId);
}
