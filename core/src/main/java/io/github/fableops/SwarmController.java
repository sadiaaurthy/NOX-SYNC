package io.github.fableops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
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
    private static final float INITIAL_SPAWN_DELAY_SECONDS = 0.15f;
    private static final float SPAWN_INTERVAL_SECONDS = 0.35f;

    private final List<Enemy> enemiesP1 = new ArrayList<>();
    private final List<Enemy> enemiesP2 = new ArrayList<>();
    private int mistakesP1 = 0;
    private int mistakesP2 = 0;
    private final Random random = new Random();

    // FIFO of enemies still waiting to appear, so a wave arrives one at a time instead
    // of all in the same frame. spawnCountdown is only meaningful while this is non-empty;
    // it's left untouched (and unread) whenever the queue is idle.
    private final Queue<PendingSpawn> pendingSpawns = new ArrayDeque<>();
    private float spawnCountdown = 0f;

    /** One enemy still waiting to be released into enemiesP1/enemiesP2. */
    private static final class PendingSpawn {
        final int side;
        final float spawnX;
        final float spawnY;

        PendingSpawn(int side, float spawnX, float spawnY) {
            this.side = side;
            this.spawnX = spawnX;
            this.spawnY = spawnY;
        }
    }

    /**
     * Queues a wave in the offending player's own room, scattered around their position.
     * Each candidate point is checked against the world so enemies never land outside the
     * walkable floor or inside a wall; if every attempt fails, it falls back to spawning
     * right on the player (always valid, since they're standing there). Enemies are queued
     * rather than created immediately — update() releases them one at a time so a wave
     * doesn't all appear on the same frame.
     */
    public void spawnWave(int offendingPlayerId, float aroundX, float aroundY, Collidable world) {
        int mistakeCount = (offendingPlayerId == 1) ? ++mistakesP1 : ++mistakesP2;
        int spawnCount = Math.min(1 + mistakeCount, MAX_SPAWN_PER_WAVE);
        boolean queueWasEmpty = pendingSpawns.isEmpty();

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
            pendingSpawns.offer(new PendingSpawn(offendingPlayerId, spawnX, spawnY));
        }

        // A wave already in flight keeps its own countdown running — appending more
        // enemies to the tail doesn't reset or shorten the wait for the next release.
        if (queueWasEmpty) {
            spawnCountdown = INITIAL_SPAWN_DELAY_SECONDS;
        }
    }

    public void update(float delta, Player player1, Player player2, Collidable world) {
        updateSide(enemiesP1, delta, player1, world);
        updateSide(enemiesP2, delta, player2, world);
        releaseDuePendingSpawn(delta);
    }

    /**
     * Drains the staggered-spawn queue by at most one enemy per call — an "if", never a
     * "while", so a long frame stall can't dump several overdue enemies into the world at
     * once. A pending enemy is just coordinates until this releases it; it's never
     * chased/collided/drawn before that.
     */
    private void releaseDuePendingSpawn(float delta) {
        if (pendingSpawns.isEmpty()) return; // idle — nothing to count down, nothing to release

        spawnCountdown -= delta;
        if (spawnCountdown > 0f) return;

        PendingSpawn next = pendingSpawns.poll();
        List<Enemy> list = (next.side == 1) ? enemiesP1 : enemiesP2;
        list.add(new Enemy(next.spawnX, next.spawnY, next.side));

        spawnCountdown = SPAWN_INTERVAL_SECONDS;
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
        pendingSpawns.clear();
        spawnCountdown = 0f;
    }
}