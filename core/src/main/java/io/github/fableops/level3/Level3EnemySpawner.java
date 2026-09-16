package io.github.fableops.level3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.math.Rectangle;

import io.github.fableops.SwarmController;

// Host/debug only: spawns Level 3's Brawl and Hacker constructs. Kept separate from Level3Screen
// so spawn zones, enemy types, spawn limits and spawn timing are all in one configurable place -
// add another SpawnZone entry to grow this into real multi-wave encounters without touching
// Level3Screen or the two SwarmControllers it owns
public class Level3EnemySpawner {

    public enum EnemyKind { BRAWL, HACKER }

    private static final class SpawnZone {
        final EnemyKind kind;
        final Rectangle bounds;

        SpawnZone(EnemyKind kind, Rectangle bounds) {
            this.kind = kind;
            this.bounds = bounds;
        }
    }

    private static final float FIRST_WAVE_DELAY = 6f;
    private static final float WAVE_INTERVAL = 6f;
    private static final int WAVE_SIZE = 1; // enemies of each kind spawned per player per wave
    private static final int MAX_PER_KIND_PER_PLAYER = 6;
    // Shrinks each zone so a spawn point - and SwarmController's own ring search around it, which
    // only avoids walls - can't reach past the zone edge toward the player spawn or objective zones.
    // On this map those sit well outside the arena, so this is a safety margin, not the only guard
    private static final float ZONE_MARGIN = 100f;

    private final List<SpawnZone> zones = new ArrayList<>();
    private final SwarmController brawlSwarm;
    private final SwarmController hackerSwarm;
    private final Random random = new Random();
    private float waveTimer = FIRST_WAVE_DELAY;

    public Level3EnemySpawner(Level3Map world, SwarmController brawlSwarm, SwarmController hackerSwarm) {
        this.brawlSwarm = brawlSwarm;
        this.hackerSwarm = hackerSwarm;
        // Yellow on Layer 1 of the PSD is the spawnable paint. With two pockets painted the two
        // kinds take one each; with one they share it. Add more SpawnZone entries, or paint more
        // yellow further down the road, to spread future waves out
        Rectangle painted = world.getSpawnZone();
        zones.add(new SpawnZone(EnemyKind.BRAWL, painted));
        zones.add(new SpawnZone(EnemyKind.HACKER, painted));
    }

    // Host/debug only - the caller is expected to gate this like every other spawn call in the project
    public void update(float delta, Level3Map world) {
        waveTimer -= delta;
        if (waveTimer > 0f) return;
        waveTimer = WAVE_INTERVAL;
        spawnWave(1, world);
        spawnWave(2, world);
    }

    private void spawnWave(int side, Level3Map world) {
        for (int i = 0; i < zones.size(); i++) {
            SpawnZone zone = zones.get(i);
            SwarmController swarm = (zone.kind == EnemyKind.BRAWL) ? brawlSwarm : hackerSwarm;
            int room = MAX_PER_KIND_PER_PLAYER - swarm.count(side);
            int count = Math.min(WAVE_SIZE, room);
            for (int n = 0; n < count; n++) {
                float[] anchor = randomAnchor(zone.bounds);
                swarm.spawnAround(side, anchor[0], anchor[1], 1, world);
            }
        }
    }

    // A point well inside the zone, so the ring search around it stays inside the zone
    private float[] randomAnchor(Rectangle zone) {
        float marginX = Math.min(ZONE_MARGIN, zone.width / 2f - 1f);
        float marginY = Math.min(ZONE_MARGIN, zone.height / 2f - 1f);
        float x = zone.x + marginX + random.nextFloat() * Math.max(0f, zone.width - 2 * marginX);
        float y = zone.y + marginY + random.nextFloat() * Math.max(0f, zone.height - 2 * marginY);
        return new float[]{x, y};
    }
}
