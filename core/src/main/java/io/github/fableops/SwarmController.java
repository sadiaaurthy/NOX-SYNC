package io.github.fableops;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.fableops.Collidable;
import io.github.fableops.Player;

/**
 * Tracks and updates both players' enemy swarms. Enemies never auto-despawn on
 * puzzle progress — only reset() (level restart) or being killed clears them,
 * matching the spec ("enemies persist until killed").
 */
public class SwarmController {
    private static final int MAX_SPAWN_PER_WAVE = 6;

    private final List<Enemy> enemiesP1 = new ArrayList<>();
    private final List<Enemy> enemiesP2 = new ArrayList<>();
    private int mistakesP1 = 0;
    private int mistakesP2 = 0;
    private final Random random = new Random();

    /**
     * Spawns a wave in the offending player's own room, scattered around their position.
     * Each candidate point is checked against the world so enemies never land outside the
     * walkable floor or inside a wall; if every attempt fails, it falls back to spawning
     * right on the player (always valid, since they're standing there).
     */
    public void spawnWave(int offendingPlayerId, float aroundX, float aroundY, Collidable world) {
        List<Enemy> list = (offendingPlayerId == 1) ? enemiesP1 : enemiesP2;
        int mistakeCount = (offendingPlayerId == 1) ? ++mistakesP1 : ++mistakesP2;
        int spawnCount = Math.min(1 + mistakeCount, MAX_SPAWN_PER_WAVE);

        for (int i = 0; i < spawnCount; i++) {
            float spawnX = aroundX, spawnY = aroundY;
            for (int attempt = 0; attempt < 8; attempt++) {
                float angle = random.nextFloat() * (float) Math.PI * 2f;
                float radius = 150f + random.nextFloat() * 150f;
                float tryX = aroundX + (float) Math.cos(angle) * radius;
                float tryY = aroundY + (float) Math.sin(angle) * radius;
                if (!world.collides(tryX, tryY, Enemy.SIZE, Enemy.SIZE, offendingPlayerId)) {
                    spawnX = tryX;
                    spawnY = tryY;
                    break;
                }
            }
            list.add(new Enemy(spawnX, spawnY, offendingPlayerId));
        }
    }

    public void update(float delta, Player player1, Player player2, Collidable world) {
        updateSide(enemiesP1, delta, player1, world);
        updateSide(enemiesP2, delta, player2, world);
    }

    /** Advances one side and clears out enemies whose death animation has played out. */
    private void updateSide(List<Enemy> list, float delta, Player target, Collidable world) {
        for (Enemy e : list) e.update(delta, target, world);
        list.removeIf(Enemy::isFinished);
    }

    /** Attacks the nearest living enemy on the given side within range. */
    public boolean attackNearest(int side, float x, float y, float range, int damage) {
        List<Enemy> list = (side == 1) ? enemiesP1 : enemiesP2;
        Enemy nearest = null;
        float nearestDist = Float.MAX_VALUE;

        for (Enemy e : list) {
            if (!e.isActive()) continue; // already dying — don't waste a hit on a corpse
            float dx = (e.x + Enemy.SIZE / 2f) - x;
            float dy = (e.y + Enemy.SIZE / 2f) - y;
            float dist = dx * dx + dy * dy;
            if (dist <= range * range && dist < nearestDist) {
                nearest = e;
                nearestDist = dist;
            }
        }

        if (nearest == null) return false;
        // Removal now waits for the death animation to finish (see updateSide).
        nearest.takeDamage(damage);
        return true;
    }

    /** True if an enemy on the given side is overlapping the given box (for contact damage). */
    public boolean isTouchingAny(int side, float x, float y, float w, float h) {
        List<Enemy> list = (side == 1) ? enemiesP1 : enemiesP2;
        for (Enemy e : list) {
            if (!e.isActive()) continue; // a corpse shouldn't keep damaging the player
            if (x < e.x + Enemy.SIZE && x + w > e.x && y < e.y + Enemy.SIZE && y + h > e.y) {
                return true;
            }
        }
        return false;
    }

    public List<Enemy> getEnemiesP1() { return enemiesP1; }
    public List<Enemy> getEnemiesP2() { return enemiesP2; }

    public void reset() {
        enemiesP1.clear();
        enemiesP2.clear();
        mistakesP1 = 0;
        mistakesP2 = 0;
    }
}