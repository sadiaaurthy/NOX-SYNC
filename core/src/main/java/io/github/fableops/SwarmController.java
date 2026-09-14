package io.github.fableops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

// Both players' swarms. Enemies only disappear when killed or on a restart
// Indexed loops because for-each creates an Iterator every frame
public class SwarmController {

    private static final int MAX_SPAWN_PER_WAVE = 6;
    private static final float INITIAL_SPAWN_DELAY_SECONDS = 0.15f;
    private static final float SPAWN_INTERVAL_SECONDS = 0.35f;

    private static final float TAU = (float) (Math.PI * 2.0);
    // So enemies never spawn right on top of the player
    private static final float MIN_SPAWN_RADIUS = 170f;
    private static final float SPAWN_RING_STEP = 60f;
    private static final int SPAWN_RINGS = 8; // reaches ~590 units out
    private static final int SAMPLES_PER_RING = 12;

    private static final float SEPARATION_STRENGTH = 40f;

    private final SpriteBounds bounds;
    // Closer than this and enemies get pushed apart
    private final float separationDistance;

    private final List<Enemy> enemiesP1 = new ArrayList<>();
    private final List<Enemy> enemiesP2 = new ArrayList<>();
    private int mistakesP1 = 0;
    private int mistakesP2 = 0;
    private final Random random = new Random();
    // Reused by findSpawnPoint()
    private final float[] spawnProbe = new float[2];

    // Enemies come out one at a time instead of all at once
    private final Queue<PendingSpawn> pendingSpawns = new ArrayDeque<>();
    private float spawnCountdown = 0f;

    private static final class PendingSpawn {
        final int side;
        final float x;
        final float y;

        PendingSpawn(int side, float x, float y) {
            this.side = side;
            this.x = x;
            this.y = y;
        }
    }

    public SwarmController(EnemySprites sprites) {
        bounds = sprites.bounds;
        separationDistance = bounds.bodyW * 0.8f;
    }

    // One more enemy for each mistake, up to 6. If there's no free spot that enemy is skipped
    public void spawnWave(int offendingPlayerId, float aroundX, float aroundY, Collidable world) {
        int mistakeCount = (offendingPlayerId == 1) ? ++mistakesP1 : ++mistakesP2;
        int spawnCount = Math.min(1 + mistakeCount, MAX_SPAWN_PER_WAVE);
        // Don't reset the timer of a wave that's already coming
        if (pendingSpawns.isEmpty()) spawnCountdown = INITIAL_SPAWN_DELAY_SECONDS;

        for (int i = 0; i < spawnCount; i++) {
            if (findSpawnPoint(aroundX, aroundY, offendingPlayerId, world)) {
                pendingSpawns.offer(new PendingSpawn(offendingPlayerId, spawnProbe[0], spawnProbe[1]));
            }
        }
    }

    // Tries rings around the player, closest first. The result goes into spawnProbe
    private boolean findSpawnPoint(float aroundX, float aroundY, int side, Collidable world) {
        for (int ring = 0; ring < SPAWN_RINGS; ring++) {
            float radius = MIN_SPAWN_RADIUS + ring * SPAWN_RING_STEP;
            float offset = random.nextFloat() * TAU;
            for (int i = 0; i < SAMPLES_PER_RING; i++) {
                float angle = offset + i * (TAU / SAMPLES_PER_RING);
                float tryX = aroundX + (float) Math.cos(angle) * radius;
                float tryY = aroundY + (float) Math.sin(angle) * radius;
                if (!world.collides(tryX, tryY, bounds.footW, bounds.footH, side)) {
                    spawnProbe[0] = tryX;
                    spawnProbe[1] = tryY;
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

    // At most one enemy per frame
    private void releaseDuePendingSpawn(float delta) {
        if (pendingSpawns.isEmpty()) return;
        spawnCountdown -= delta;
        if (spawnCountdown > 0f) return;

        PendingSpawn next = pendingSpawns.poll();
        List<Enemy> list = (next.side == 1) ? enemiesP1 : enemiesP2;
        list.add(new Enemy(bounds, next.x, next.y, next.side));
        spawnCountdown = SPAWN_INTERVAL_SECONDS;
    }

    private void updateSide(List<Enemy> list, float delta, Player target, Collidable world) {
        for (int i = 0; i < list.size(); i++) {
            list.get(i).update(delta, target, world);
        }
        separate(list, delta, world);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).isFinished()) list.remove(i);
        }
    }

    // O(n^2), but there are only a few enemies per side
    private void separate(List<Enemy> list, float delta, Collidable world) {
        float minDistSq = separationDistance * separationDistance;
        for (int i = 0; i < list.size(); i++) {
            Enemy a = list.get(i);
            if (!a.isActive()) continue;
            for (int j = i + 1; j < list.size(); j++) {
                Enemy b = list.get(j);
                if (!b.isActive()) continue;

                float dx = b.x - a.x;
                float dy = b.y - a.y;
                float distSq = dx * dx + dy * dy;
                if (distSq >= minDistSq) continue;

                // Two enemies on exactly the same point have no direction to push along.
                float dist = (float) Math.sqrt(distSq);
                float nx = (dist < 0.001f) ? 1f : dx / dist;
                float ny = (dist < 0.001f) ? 0f : dy / dist;
                float push = SEPARATION_STRENGTH * delta;
                a.nudge(-nx * push, -ny * push, world);
                b.nudge(nx * push, ny * push, world);
            }
        }
    }

    public void attackNearest(int side, float x, float y, float range, int damage) {
        List<Enemy> list = (side == 1) ? enemiesP1 : enemiesP2;
        Enemy nearest = null;
        float nearestDistSq = range * range;
        for (int i = 0; i < list.size(); i++) {
            Enemy e = list.get(i);
            if (!e.isActive()) continue;
            float dx = e.centreX() - x;
            float dy = e.centreY() - y;
            float distSq = dx * dx + dy * dy;
            if (distSq <= nearestDistSq) {
                nearest = e;
                nearestDistSq = distSq;
            }
        }
        if (nearest != null) nearest.takeDamage(damage);
    }

    public boolean isTouchingAny(int side, Player player) {
        List<Enemy> list = (side == 1) ? enemiesP1 : enemiesP2;
        for (int i = 0; i < list.size(); i++) {
            Enemy e = list.get(i);
            if (e.isActive() && e.touches(player)) return true;
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
