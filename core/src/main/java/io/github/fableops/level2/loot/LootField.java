package io.github.fableops.level2.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import io.github.fableops.Player;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level2.CoreObject;
import io.github.fableops.level2.Gun;
import io.github.fableops.level2.Level2Map;

// Every loot drop placed in the maze. Low and medium tiers can be picked up any time; the high tier
// only unlocks while the core is being carried, which is the push-your-luck part of the level
public class LootField {

    private static final float MARKER_SIZE = 60f;

    // Randomised layout: same seed on host and client, via Level2StartMessage, so both
    // machines place loot identically without either one dictating to the other over the network
    private static final float WORLD_EDGE_MARGIN = 80f; // stay off the outer map edge
    private static final float KEY_AREA_MARGIN = 90f; // stay clear of spawn/core/socket/exit
    private static final float MIN_LOOT_SEPARATION = 180f; // loot items don't cluster together
    private static final int MAX_PLACEMENT_ATTEMPTS = 200;
    // Used only if 200 random tries somehow all fail to find a legal spot (extremely unlikely
    // on this map) - keeps the level completable instead of throwing
    private static final float[][] FALLBACK_POSITIONS = {
        {700f, 724f}, {200f, 830f}, {350f, 564f}, {950f, 564f}, {1100f, 400f}
    };

    private final Texture gunIcon = new Texture(Gdx.files.internal("LootWeapon.png"));
    private final Texture ammoCacheIcon = new Texture(Gdx.files.internal("LootAmmoCache.png"));
    private final Texture medKitIcon = new Texture(Gdx.files.internal("LootMedkit.png"));
    private final Texture shieldCellIcon = new Texture(Gdx.files.internal("LootShieldCell.png"));
    private final Texture premiumShieldIcon = new Texture(Gdx.files.internal("LootPremiumShield.png"));

    private final List<LootDrop> drops = new ArrayList<>();
    private final LootDrop gunDrop;
    private float glowTime = 0f;

    // Both machines build the same list from the same seed, so the ids and positions that
    // travel over the network (pickups reference ids only) line up on both sides
    public LootField(Level2Map world, long seed) {
        Random rng = new Random(seed);
        List<float[]> placed = new ArrayList<>();

        float[] p0 = randomSpot(world, rng, placed, FALLBACK_POSITIONS[0]);
        // Weapon ownership follows whichever personal inventory currently holds this item.
        gunDrop = new LootDrop(0, LootTier.MEDIUM, p0[0], p0[1], new InventoryItem("Sidearm",
            "Fires where you face. Hold attack to shoot, R reloads.", gunIcon, 0f, true), 0);
        drops.add(gunDrop);

        float[] p1 = randomSpot(world, rng, placed, FALLBACK_POSITIONS[1]);
        drops.add(new LootDrop(1, LootTier.LOW, p1[0], p1[1], new InventoryItem("Ammo Cache",
            "Rounds for the sidearm.", ammoCacheIcon, 0f, true), Gun.CACHE_ROUNDS));

        float[] p2 = randomSpot(world, rng, placed, FALLBACK_POSITIONS[2]);
        drops.add(item(2, LootTier.MEDIUM, p2[0], p2[1], "Med kit",
            "Patches you up. Doesn't need the core.", medKitIcon, 35f));

        float[] p3 = randomSpot(world, rng, placed, FALLBACK_POSITIONS[3]);
        drops.add(item(3, LootTier.MEDIUM, p3[0], p3[1], "Shield Cell",
            "A temporary shield charge.", shieldCellIcon, 0f));

        float[] p4 = randomSpot(world, rng, placed, FALLBACK_POSITIONS[4]);
        drops.add(item(4, LootTier.HIGH, p4[0], p4[1], "Rare Plating",
            "Rare armour. Only appears while the core is carried.", premiumShieldIcon, 0f));
    }

    // Tries random points until Level2Map's own collision mask says one is legal floor, clear of
    // every objective zone, and far enough from loot already placed this session
    private static float[] randomSpot(Level2Map world, Random rng, List<float[]> placed, float[] fallback) {
        float minX = WORLD_EDGE_MARGIN;
        float maxX = world.getWorldWidth() - WORLD_EDGE_MARGIN;
        float minY = WORLD_EDGE_MARGIN;
        float maxY = world.getWorldHeight() - WORLD_EDGE_MARGIN;

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            float x = minX + rng.nextFloat() * (maxX - minX);
            float y = minY + rng.nextFloat() * (maxY - minY);
            if (!world.isLootSpot(x, y, MARKER_SIZE, KEY_AREA_MARGIN)) continue;
            if (tooCloseToPlaced(placed, x, y)) continue;
            float[] spot = {x, y};
            placed.add(spot);
            return spot;
        }
        placed.add(fallback);
        return fallback;
    }

    private static boolean tooCloseToPlaced(List<float[]> placed, float x, float y) {
        float minDistSq = MIN_LOOT_SEPARATION * MIN_LOOT_SEPARATION;
        for (float[] p : placed) {
            float dx = p[0] - x;
            float dy = p[1] - y;
            if (dx * dx + dy * dy < minDistSq) return true;
        }
        return false;
    }

    // Loot equipment is shareable; objective items such as the Unstable Core opt out explicitly.
    private static LootDrop item(int id, LootTier tier, float x, float y, String name, String description,
                                 Texture icon, float healAmount) {
        InventoryItem inventoryItem = new InventoryItem(name, description, icon, healAmount, true);
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
