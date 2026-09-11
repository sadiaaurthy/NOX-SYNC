package io.github.fableops.inventory;

import com.badlogic.gdx.graphics.Color;

/**
 * One carryable thing. Immutable — an item's identity never changes once created, only
 * which slot holds it, so instances can be shared and compared safely.
 *
 * There is deliberately no category field. The inventory is universal: weapons, armour,
 * consumables and quest items all share one grid with no tabs or sub-groups, so a
 * category would be data nothing reads.
 *
 * {@code accent} is the colour the slot icon is drawn in. It is the only visual an item
 * carries — there is no item art yet, so the grid identifies things by colour and the
 * detail strip names the selected one.
 */
public final class InventoryItem {

    private final String name;
    private final String description;
    private final Color accent;
    private final float healAmount;

    /** A plain item — carried, but nothing happens when it is used. */
    public InventoryItem(String name, String description, Color accent) {
        this(name, description, accent, 0f);
    }

    /**
     * @param healAmount health restored when this item is used, or 0 for an inert item.
     *                   Healing exists only as an item effect — players never recover on
     *                   their own — so this field is the entire healing system.
     */
    public InventoryItem(String name, String description, Color accent, float healAmount) {
        this.name = name;
        this.description = description;
        this.accent = accent;
        this.healAmount = healAmount;
    }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public Color getAccent() { return accent; }

    public float getHealAmount() { return healAmount; }

    /** Whether using this item does anything — drives the USE prompt in the panel. */
    public boolean isConsumable() { return healAmount > 0f; }
}
