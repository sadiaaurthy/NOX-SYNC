package io.github.fableops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;


/**
 * Tracks and updates both players' enemy swarms. Enemies never auto-despawn on
 * puzzle progress — only reset() (level restart) or being killed clears them,
 * matching the spec ("enemies persist until killed").
 */
public class SwarmController {
    private static final int MAX_SPAWN_PER_WAVE = 6;
    private static final float INITIAL_SPAWN_DELAY_SECONDS = 0.15f;
    private static final float SPAWN_INTERVAL_SECONDS = 0.35f;

    private static final float TAU = (float) (Math.PI * 2.0);
    /** Far enough out that a wave never appears already touching the player. */
    private static final float MIN_SPAWN_RADIUS = 170f;
    private static final float SPAWN_RING_STEP = 60f;
    private static final int SPAWN_RINGS = 8;      // reaches ~590 units out
    private static final int SAMPLES_PER_RING = 12;
    /** Reused by findSpawnPoint() so queueing a wave allocates nothing. */
    private final float[] spawnProbe = new float[2];

    /** Enemies this close to each other get pushed apart so a swarm doesn't stack into one body. */
    private static final float SEPARATION_DISTANCE = Enemy.SIZE * 0.8f;
    private static final float SEPARATION_STRENGTH = 40f;

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
     *
     * Placement searches outward in rings rather than firing random shots into a fixed
     * 150-300 band: in a corridor most of that band is solid wall, so the old eight
     * attempts routinely all failed and fell back to spawning the enemy *directly on the
     * player* — which meant an instant, unavoidable hit from a body already inside them.
     * Rings guarantee the nearest legal standing room is found instead, and the innermost
     * ring starts far enough out that a wave never materialises on top of anyone.
     *
     * Enemies are queued rather than created immediately — update() releases them one at a
     * time so a wave doesn't all appear on the same frame.
     */
    public void spawnWave(int offendingPlayerId, float aroundX, float aroundY, Collidable world) {
        int mistakeCount = (offendingPlayerId == 1) ? ++mistakesP1 : ++mistakesP2;
        int spawnCount = Math.min(1 + mistakeCount, MAX_SPAWN_PER_WAVE);
        boolean queueWasEmpty = pendingSpawns.isEmpty();

        for (int i = 0; i < spawnCount; i++) {
            if (findSpawnPoint(aroundX, aroundY, offendingPlayerId, world, spawnProbe)) {
                pendingSpawns.offer(new PendingSpawn(offendingPlayerId, spawnProbe[0], spawnProbe[1]));
            }
            // No legal spot anywhere in range: drop this enemy rather than spawn it inside
            // a wall or inside the player. A smaller wave is a far smaller problem than one
            // that cannot be fought or escaped.
        }

        // A wave already in flight keeps its own countdown running — appending more
        // enemies to the tail doesn't reset or shorten the wait for the next release.
        if (queueWasEmpty) {
            spawnCountdown = INITIAL_SPAWN_DELAY_SECONDS;
        }
    }

    /**
     * Finds the nearest walkable spot around a point, searching outward ring by ring.
     * Each ring is sampled at evenly spaced angles from a random offset, so the result is
     * varied between waves without leaving gaps the way pure random sampling does.
     *
     * @param out receives x,y on success — reused, so it must be read before the next call
     * @return false when no ring held a legal spot, which means don't spawn at all
     */
    private boolean findSpawnPoint(float aroundX, float aroundY, int side, Collidable world, float[] out) {
        for (int ring = 0; ring < SPAWN_RINGS; ring++) {
            float radius = MIN_SPAWN_RADIUS + ring * SPAWN_RING_STEP;
            float offset = random.nextFloat() * TAU;
            for (int i = 0; i < SAMPLES_PER_RING; i++) {
                float angle = offset + i * (TAU / SAMPLES_PER_RING);
                float tryX = aroundX + (float) Math.cos(angle) * radius;
                float tryY = aroundY + (float) Math.sin(angle) * radius;
                if (!world.collides(tryX, tryY, Enemy.SIZE, Enemy.SIZE, side)) {
                    out[0] = tryX;
                    out[1] = tryY;
                    return true;
                }
            }
        }
        return false;
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
        separate(list, delta, world);
        list.removeIf(Enemy::isFinished);
    }

    /**
     * Pushes overlapping enemies apart.
     *
     * Every enemy steers at the same target with the same speed, so without this they
     * converge onto one point and the whole wave renders as a single smeared sprite that
     * the player can kill with one swing. O(n^2), which is fine at these counts — a wave
     * caps at 6 and the whole side rarely exceeds a couple of dozen.
     */
    private void separate(List<Enemy> list, float delta, Collidable world) {
        for (int i = 0; i < list.size(); i++) {
            Enemy a = list.get(i);
            if (!a.isActive()) continue;
            for (int j = i + 1; j < list.size(); j++) {
                Enemy b = list.get(j);
                if (!b.isActive()) continue;

                float dx = b.x - a.x;
                float dy = b.y - a.y;
                float distSq = dx * dx + dy * dy;
                if (distSq >= SEPARATION_DISTANCE * SEPARATION_DISTANCE) continue;

                // Exactly coincident (two spawns landing on the same point) has no
                // direction to push along, so nudge along a fixed axis to break the tie.
                float dist = (float) Math.sqrt(distSq);
                float nx, ny;
                if (dist < 0.001f) {
                    nx = 1f;
                    ny = 0f;
                } else {
                    nx = dx / dist;
                    ny = dy / dist;
                }
                float push = SEPARATION_STRENGTH * delta;
                a.nudge(-nx * push, -ny * push, world);
                b.nudge(nx * push, ny * push, world);
            }
        }
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