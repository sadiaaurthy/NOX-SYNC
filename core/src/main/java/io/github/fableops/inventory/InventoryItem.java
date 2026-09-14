package io.github.fableops.inventory;

import com.badlogic.gdx.graphics.Texture;

// The icon texture belongs to whatever created the item
public final class InventoryItem {

    private final String name;
    private final String description;
    private final Texture icon;
    private final float healAmount;
    private final boolean shareable;

    // shareable = can be put in the shared slot
    public InventoryItem(String name, String description, Texture icon, float healAmount, boolean shareable) {
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.healAmount = healAmount;
        this.shareable = shareable;
    }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public Texture getIcon() { return icon; }

    public float getHealAmount() { return healAmount; }

    public boolean isShareable() { return shareable; }

    public boolean isConsumable() { return healAmount > 0f; }
}
