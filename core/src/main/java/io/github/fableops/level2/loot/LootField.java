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
    // A full loot table needs more well-spaced floor than a 1536x1024 maze has, so placement
    // relaxes the spacing rule in steps instead of giving up and stacking everything on fixed
    // points. 70 is just over MARKER_SIZE, so even the tightest pass never overlaps two markers
    private static final float[] SEPARATION_STEPS = {MIN_LOOT_SEPARATION, 110f, 70f};
    // Only if the map has no legal loot floor at all, which would be a broken collision mask
    private static final float[] FALLBACK_POSITION = {700f, 724f};

    // The loot table. Counts live here so the mix can be retuned without touching placement
    private static final int SIDEARM_COUNT = 2;
    private static final int AMMO_CACHE_COUNT = 2;
    private static final int MED_KIT_COUNT = 4;      // two each for a two-operator team
    private static final int SHIELD_CELL_COUNT = 4;
    private static final int RARE_PLATING_COUNT = 2;
    // One charge takes down one turret, and an encounter rolls 2-4 of them. Three covers the
    // common case; the Sidearm reaction is the backstop when a run rolls four
    private static final int TNT_COUNT = 3;

    private final Texture gunIcon = new Texture(Gdx.files.internal("LootWeapon.png"));
    private final Texture ammoCacheIcon = new Texture(Gdx.files.internal("LootAmmoCache.png"));
    private final Texture medKitIcon = new Texture(Gdx.files.internal("LootMedkit.png"));
    private final Texture shieldCellIcon = new Texture(Gdx.files.internal("LootShieldCell.png"));
    private final Texture premiumShieldIcon = new Texture(Gdx.files.internal("LootPremiumShield.png"));
    private final Texture tntIcon = new Texture(Gdx.files.internal("LootTNT.png"));

    private final List<LootDrop> drops = new ArrayList<>();
    private float glowTime = 0f;

    // Both machines build the same list from the same seed, so the ids and positions that
    // travel over the network (pickups reference ids only) line up on both sides
    public LootField(Level2Map world, long seed) {
        Random rng = new Random(seed);
        List<float[]> placed = new ArrayList<>();

        int id = 0;

        // Ids are handed out in this order and both machines walk the same list, so the order of
        // these loops is part of the wire contract - appending a new kind is safe, reordering is not
        for (int i = 0; i < SIDEARM_COUNT; i++) {
            float[] spot = randomSpot(world, rng, placed);
            // Weapon ownership follows whichever personal inventory currently holds this item.
            drops.add(new LootDrop(id++, LootTier.MEDIUM, spot[0], spot[1], new InventoryItem("Sidearm",
                "Fires where you face. Hold attack to shoot, R reloads.", gunIcon, 0f, true), 0));
        }

        for (int i = 0; i < AMMO_CACHE_COUNT; i++) {
            float[] spot = randomSpot(world, rng, placed);
            drops.add(new LootDrop(id++, LootTier.LOW, spot[0], spot[1], new InventoryItem("Ammo Cache",
                "Rounds for the sidearm.", ammoCacheIcon, 0f, true), Gun.CACHE_ROUNDS));
        }

        for (int i = 0; i < MED_KIT_COUNT; i++) {
            float[] spot = randomSpot(world, rng, placed);
            drops.add(item(id++, LootTier.MEDIUM, spot[0], spot[1], "Medkit",
                "Patches you up. Doesn't need the core.", medKitIcon, 35f));
        }

        for (int i = 0; i < SHIELD_CELL_COUNT; i++) {
            float[] spot = randomSpot(world, rng, placed);
            drops.add(item(id++, LootTier.MEDIUM, spot[0], spot[1], "Shield Cell",
                "A temporary shield charge.", shieldCellIcon, 0f));
        }

        for (int i = 0; i < RARE_PLATING_COUNT; i++) {
            float[] spot = randomSpot(world, rng, placed);
            drops.add(item(id++, LootTier.HIGH, spot[0], spot[1], "Rare Plating",
                "Rare armour. Only appears while the core is carried.", premiumShieldIcon, 0f));
        }

        for (int i = 0; i < TNT_COUNT; i++) {
            float[] spot = randomSpot(world, rng, placed);
            drops.add(item(id++, LootTier.HIGH, spot[0], spot[1], "TNT",
                "Demolition charge. Takes down one turret once the drones are grounded.",
                tntIcon, 0f));
        }
    }

    // Tries random points until Level2Map's own collision mask says one is legal floor, clear of
    // every objective zone, and far enough from loot already placed this session. Each step down
    // SEPARATION_STEPS is a fresh set of attempts at a looser spacing, so early drops spread out
    // properly and later ones still land on real randomised floor instead of a fixed point.
    // Both machines run this identically from the same seed, so the draws stay in lockstep
    private static float[] randomSpot(Level2Map world, Random rng, List<float[]> placed) {
        for (float separation : SEPARATION_STEPS) {
            float[] spot = tryPlace(world, rng, placed, separation);
            if (spot != null) {
                placed.add(spot);
                return spot;
            }
        }
        float[] fallback = {FALLBACK_POSITION[0], FALLBACK_POSITION[1]};
        placed.add(fallback);
        return fallback;
    }

    private static float[] tryPlace(Level2Map world, Random rng, List<float[]> placed, float separation) {
        float minX = WORLD_EDGE_MARGIN;
        float maxX = world.getWorldWidth() - WORLD_EDGE_MARGIN;
        float minY = WORLD_EDGE_MARGIN;
        float maxY = world.getWorldHeight() - WORLD_EDGE_MARGIN;

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            float x = minX + rng.nextFloat() * (maxX - minX);
            float y = minY + rng.nextFloat() * (maxY - minY);
            if (!world.isLootSpot(x, y, MARKER_SIZE, KEY_AREA_MARGIN)) continue;
            if (tooCloseToPlaced(placed, x, y, separation)) continue;
            return new float[]{x, y};
        }
        return null;
    }

    private static boolean tooCloseToPlaced(List<float[]> placed, float x, float y, float separation) {
        float minDistSq = separation * separation;
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

    // Matched by name rather than by reference, so every Sidearm drop grants the weapon
    public boolean isGun(LootDrop drop) {
        return "Sidearm".equalsIgnoreCase(drop.getItem().getName());
    }

    // Item definitions remain available after pickup so later levels can render a category preview
    // even when the current player owns zero copies. The returned item is definition data only;
    // callers must not add it to an inventory.
    public InventoryItem itemDefinition(String itemName) {
        for (LootDrop drop : drops) {
            InventoryItem item = drop.getItem();
            if (itemName.equalsIgnoreCase(item.getName())) return item;
        }
        return null;
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
        tntIcon.dispose();
    }
}
