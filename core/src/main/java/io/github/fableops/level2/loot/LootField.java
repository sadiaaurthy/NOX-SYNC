package io.github.fableops.level2.loot;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.ArrayList;
import java.util.List;

import io.github.fableops.Player;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level2.CoreObject;

// Every loot drop placed in the maze (design doc: "Loot System"). Low/medium tiers go straight
// into whichever player reaches them; high tier only unlocks while the core is being carried,
// which is what creates the push-your-luck decision described in the doc.
// Icons are flat-colour placeholder PNGs, one per tier, until real loot art exists
public class LootField {

    private static final float MARKER_SIZE = 60f;

   /*  private static final Color LOW_COLOR = new Color(0.65f, 0.68f, 0.72f, 1f);
    private static final Color MEDIUM_COLOR = new Color(0.25f, 0.55f, 0.95f, 1f);
    private static final Color HIGH_COLOR = new Color(0.95f, 0.75f, 0.15f, 1f); */

    private final Texture weaponIcon = new Texture(Gdx.files.internal("LootWeapon.png"));
    private final Texture ammoCacheIcon = new Texture(Gdx.files.internal("LootAmmoCache.png"));
    private final Texture medKitIcon = new Texture(Gdx.files.internal("LootMedkit.png"));
    private final Texture shieldCellIcon = new Texture(Gdx.files.internal("LootShieldCell.png"));
    private final Texture premiumShieldIcon = new Texture(Gdx.files.internal("LootPremiumShield.png"));


    private final List<LootDrop> drops = new ArrayList<>();
    private float glowTime = 0f;

    public LootField() {
        int id = 0;
        // Low/medium: scattered through the outer rooms, safe to grab whenever
      /*   drops.add(drop(id++, LootTier.LOW, 200f, 844f, "Ammo Cache",
            "Restocks the basics. Doesn't need the core.", lowIcon, 0f));
        drops.add(drop(id++, LootTier.MEDIUM, 350f, 564f, "Medkit",
            "Patches you up. Doesn't need the core.", mediumIcon, 35f));
        drops.add(drop(id++, LootTier.MEDIUM, 700f, 724f, "Weapon",
    "A basic weapon upgrade.", weaponIcon, 0f));
        drops.add(drop(id++, LootTier.MEDIUM, 950f, 564f, "Shield Cell",
            "A temporary shield charge.", mediumIcon, 0f));
        // High value: deeper toward the reactor, locked out until the core leaves its pedestal
        drops.add(drop(id++, LootTier.HIGH, 1000f, 324f, "Rare Plating",
            "Rare armour. Only appears while the core is carried.", highIcon, 0f)); */

    drops.add(drop(id++, LootTier.MEDIUM, 700f, 724f, "Weapon","A basic weapon upgrade.", weaponIcon, 0f));
    drops.add(drop(id++, LootTier.LOW, 200f, 830f, "Ammo Cache","A ammo cache.", ammoCacheIcon, 0f));
    drops.add(drop(id++, LootTier.MEDIUM, 350f, 564f, "Med kit", "Patches you up. Doesn't need the core.", medKitIcon, 35f));
    drops.add(drop(id++, LootTier.MEDIUM, 950f, 564f, "Shield Cell","A temporary shield charge.", shieldCellIcon, 0f));
    drops.add(drop(id++, LootTier.HIGH, 1100f, 400f, "Rare Plating", "Rare armour. Only appears while the core is carried.", premiumShieldIcon, 0f));
    }

    private static LootDrop drop(int id, LootTier tier, float x, float y, String name, String description,
                                 Texture icon, float healAmount) {
        // High-value gear is personal; low/medium loot can be handed off through the shared slot
        boolean shareable = tier != LootTier.HIGH;
        InventoryItem item = new InventoryItem(name, description, icon, healAmount, shareable);
        return new LootDrop(id, tier, x, y, item);
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

    // What E does here right now, or null. A reachable but still-locked high-value drop
    // gets its own message instead of staying silent
    public String prompt(Player player, CoreObject.State coreState) {
        for (int i = 0; i < drops.size(); i++) {
            LootDrop drop = drops.get(i);
            if (!drop.canReach(player)) continue;
            if (!unlocked(drop, coreState)) return "High-value cache - carry the Core to unlock";
                        return "Press G to take " + drop.getItem().getName();
        }
        return null;
    }

    // Called on both the host (right after it accepts the pickup) and the client (on the network
    // message it gets back), so the item lands in the same player's inventory on both machines
    public void applyPickup(int lootId, int playerId, PlayerInventories inventories) {
        for (int i = 0; i < drops.size(); i++) {
            LootDrop drop = drops.get(i);
            if (drop.getId() == lootId && !drop.isCollected()) {
                drop.setCollected(true);
                inventories.forPlayer(playerId).add(drop.getItem());
                return;
            }
        }
    }

    // Call inside a filled ShapeRenderer pass with blending on
        // Call inside its own SpriteBatch begin/end pass
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
        weaponIcon.dispose();
        ammoCacheIcon.dispose();
        medKitIcon.dispose();
        shieldCellIcon.dispose();
        premiumShieldIcon.dispose();
    }
}
