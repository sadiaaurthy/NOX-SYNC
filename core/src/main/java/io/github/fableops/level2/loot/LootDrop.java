package io.github.fableops.level2.loot;

import com.badlogic.gdx.math.Rectangle;

import io.github.fableops.Player;
import io.github.fableops.inventory.InventoryItem;

// One placed pickup. Reach uses the same zone check as the core pedestal and the socket
public class LootDrop {

    private static final float SIZE = 24f;

    private final int id;
    private final LootTier tier;
    private final Rectangle zone;
    private final InventoryItem item;
    // Rounds for the gun. 0 means the item goes into the inventory instead
    private final int ammo;
    private final String prompt;
    private boolean collected = false;

    public LootDrop(int id, LootTier tier, float centreX, float centreY, InventoryItem item, int ammo) {
        this.id = id;
        this.tier = tier;
        this.zone = new Rectangle(centreX - SIZE / 2f, centreY - SIZE / 2f, SIZE, SIZE);
        this.item = item;
        this.ammo = ammo;
        this.prompt = "Press G to take " + item.getName() + (ammo > 0 ? " (+" + ammo + " rounds)" : "");
    }

    public int getId() { return id; }

    public LootTier getTier() { return tier; }

    public InventoryItem getItem() { return item; }

    public int getAmmo() { return ammo; }

    // Built once, the HUD asks for it every frame
    public String getPrompt() { return prompt; }

    public boolean isCollected() { return collected; }

    public void setCollected(boolean collected) { this.collected = collected; }

    public boolean canReach(Player player) {
        return !collected && player.canReach(zone);
    }

    public float centreX() { return zone.x + zone.width / 2f; }

    public float centreY() { return zone.y + zone.height / 2f; }
}
