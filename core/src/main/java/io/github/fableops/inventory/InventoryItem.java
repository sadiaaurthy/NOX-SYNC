package io.github.fableops.inventory;

import com.badlogic.gdx.graphics.Color;

// No item art yet, so accent is the colour of the slot icon
public final class InventoryItem {

    private final String name;
    private final String description;
    private final Color accent;
    private final float healAmount;

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

    public boolean isConsumable() { return healAmount > 0f; }
}
