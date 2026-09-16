package io.github.fableops.level2.loot;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import io.github.fableops.Player;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level2.CoreObject;
import io.github.fableops.level2.Gun;

// Every loot drop placed in the maze. Low and medium tiers can be picked up any time; the high tier
// only unlocks while the core is being carried, which is the push-your-luck part of the level
public class LootField {

    private static final float MARKER_SIZE = 60f;

    private final Texture gunIcon = new Texture(Gdx.files.internal("LootWeapon.png"));
    private final Texture ammoCacheIcon = new Texture(Gdx.files.internal("LootAmmoCache.png"));
    private final Texture medKitIcon = new Texture(Gdx.files.internal("LootMedkit.png"));
    private final Texture shieldCellIcon = new Texture(Gdx.files.internal("LootShieldCell.png"));
    private final Texture premiumShieldIcon = new Texture(Gdx.files.internal("LootPremiumShield.png"));

    private final List<LootDrop> drops = new ArrayList<>();
    private final LootDrop gunDrop;
    private float glowTime = 0f;

    // Both machines build the same list, the ids are what travels over the network
    public LootField() {
        int id = 0;
        // Not shareable, so the gun stays with whoever picked it up and both machines agree who shoots
        gunDrop = new LootDrop(id++, LootTier.MEDIUM, 700f, 724f, new InventoryItem("Sidearm",
            "Fires where you face. Hold attack to shoot, R reloads.", gunIcon, 0f, false), 0);
        drops.add(gunDrop);
        drops.add(new LootDrop(id++, LootTier.LOW, 200f, 830f, new InventoryItem("Ammo Cache",
            "Rounds for the sidearm.", ammoCacheIcon, 0f, false), Gun.CACHE_ROUNDS));
        drops.add(item(id++, LootTier.MEDIUM, 350f, 564f, "Med kit",
            "Patches you up. Doesn't need the core.", medKitIcon, 35f));
        drops.add(item(id++, LootTier.MEDIUM, 950f, 564f, "Shield Cell",
            "A temporary shield charge.", shieldCellIcon, 0f));
        drops.add(item(id++, LootTier.HIGH, 1100f, 400f, "Rare Plating",
            "Rare armour. Only appears while the core is carried.", premiumShieldIcon, 0f));
    }

    // Low and medium loot can be handed over through the shared slot, high-value gear stays personal
    private static LootDrop item(int id, LootTier tier, float x, float y, String name, String description,
                                 Texture icon, float healAmount) {
        InventoryItem inventoryItem = new InventoryItem(name, description, icon, healAmount, tier != LootTier.HIGH);
        return new LootDrop(id, tier, x, y, inventoryItem, 0);
    }

    public void update(float delta) {
        glowTime += delta;
    }

    private static boolean unlocked(LootDrop drop, CoreObject.State coreState) {
        return drop.getTier() != LootTier.HIGH || coreState == CoreObject.State.CARRIED;
    }

    // Drops are spaced out, so at most one is ever in reach at a time
    public LootDrop findReachablePickup(Player player, CoreObject.State coreState) {
        for (int i = 0; i < drops.size(); i++) {
            LootDrop drop = drops.get(i);
            if (drop.canReach(player) && unlocked(drop, coreState)) return drop;
        }
        return null;
    }

    // What G does here right now, or null. A reachable but still-locked high-value drop
    // gets its own message instead of staying silent
    public String prompt(Player player, CoreObject.State coreState) {
        for (int i = 0; i < drops.size(); i++) {
            LootDrop drop = drops.get(i);
            if (!drop.canReach(player)) continue;
            return unlocked(drop, coreState) ? drop.getPrompt() : "High-value cache - carry the Core to unlock";
        }
        return null;
    }

    // Called on the host (right after it accepts the pickup) and on the client (on the message it gets
    // back), so the same thing lands in the same place on both machines
    public LootDrop applyPickup(int lootId, int playerId, PlayerInventories inventories) {
        for (int i = 0; i < drops.size(); i++) {
            LootDrop drop = drops.get(i);
            if (drop.getId() != lootId || drop.isCollected()) continue;
            drop.setCollected(true);
            // Ammo goes into the gun, not the inventory
            if (drop.getAmmo() == 0) inventories.forPlayer(playerId).add(drop.getItem());
            return drop;
        }
        return null;
    }

    public boolean isGun(LootDrop drop) {
        return drop == gunDrop;
    }

    // Everything goes back into the maze when the level restarts
    public void reset() {
        for (int i = 0; i < drops.size(); i++) drops.get(i).setCollected(false);
    }

    // Call inside a SpriteBatch pass
    public void render(SpriteBatch batch, CoreObject.State coreState) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(glowTime * 3f);
        for (int i = 0; i < drops.size(); i++) {
            LootDrop drop = drops.get(i);
            if (drop.isCollected()) continue;
            boolean locked = !unlocked(drop, coreState);
            float alpha = locked ? 0.35f : 0.85f + pulse * 0.15f;
            float size = MARKER_SIZE + (locked ? 0f : pulse * 4f);
            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(drop.getItem().getIcon(), drop.centreX() - size / 2f, drop.centreY() - size / 2f, size, size);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    public void dispose() {
        gunIcon.dispose();
        ammoCacheIcon.dispose();
        medKitIcon.dispose();
        shieldCellIcon.dispose();
        premiumShieldIcon.dispose();
    }
}
