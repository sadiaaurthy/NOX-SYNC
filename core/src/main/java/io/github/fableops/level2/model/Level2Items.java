package io.github.fableops.level2.model;

import com.badlogic.gdx.graphics.Color;

import io.github.fableops.inventory.InventoryItem;

/**
 * Level 2's item catalogue. Kept in the level2 package rather than shared, because these
 * are content, not mechanism — the inventory itself and the act of consuming an item are
 * level-agnostic and live in {@code io.github.fableops.inventory}.
 *
 * Health rules, which start here and do not apply to Level 1:
 *
 * Players never recover health on their own. There is no regeneration, no recovery on
 * clearing a wave, and no top-up between objectives — {@link io.github.fableops.Player#heal}
 * is reachable only by consuming an item, and {@link #healthPack()} is the only item in the
 * game that carries a heal value. Damage taken in Level 2 is therefore permanent until a
 * pack is spent, which is what makes carrying one a real decision against the Core and the
 * loot competing for the same slots.
 *
 * Level 1 deliberately grants no packs at all: its failure state is the Alert Meter, not
 * attrition, so healing there would only blunt the enemy waves that punish a wrong answer.
 */
public final class Level2Items {

    /** Restores a third of a full bar — meaningful, but never a full reset. */
    public static final float HEALTH_PACK_RESTORE = 34f;

    private static final Color ORANGE = new Color(1f, 0.541f, 0.239f, 1f);
    private static final Color CYAN = new Color(0f, 0.90f, 1f, 1f);
    private static final Color MAGENTA = new Color(1f, 0.16f, 0.43f, 1f);

    public static InventoryItem healthPack() {
        return new InventoryItem("Med Patch",
            "Restores health. The only way to recover — single use.",
            ORANGE, HEALTH_PACK_RESTORE);
    }

    /**
     * The Unstable Core. Inert as an item: picking it up is what starts the meltdown timer
     * and applies the shared debuff, so it carries no heal value and nothing happens if a
     * player tries to use it from the panel.
     */
    public static InventoryItem unstableCore() {
        return new InventoryItem("Unstable Core",
            "Warm to the touch. Slows you both. The timer is already running.",
            MAGENTA);
    }

    /** One half of a Synergy Pair — worthless until the other player holds its twin. */
    public static InventoryItem synergyHalf(String name, String description) {
        return new InventoryItem(name, description, CYAN);
    }

    private Level2Items() {}
}
