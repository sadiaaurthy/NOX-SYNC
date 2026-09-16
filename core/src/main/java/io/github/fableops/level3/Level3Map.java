package io.github.fableops.level3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import java.util.List;

import io.github.fableops.Collidable;
import io.github.fableops.Player;
import io.github.fableops.world.CollisionMask;

// Everything comes from the mask Level3Mapcollision.png:
// 000000 void/wall, 0000FF floor, FF00FF player spawn, FFFF00 the Warden's directive core
// (objective - not wired to any mechanic yet, a clean hook for the boss trigger),
// FFFFFF the Warden arena (where Level 3's fight happens)
public class Level3Map implements Collidable {

    private static final byte CLASS_SPAWN = 2;
    private static final byte CLASS_OBJECTIVE = 3;
    private static final byte CLASS_ARENA = 4;

    //                                           void   floor  spawn  objective arena
    private static final int[][] PALETTE = {
        {0, 0, 0}, {0, 0, 255}, {255, 0, 255}, {255, 255, 0}, {255, 255, 255}
    };

    // No wing-locking or gate-state mechanic yet (unlike Level 1/2), so everything painted is
    // walkable and only the void blocks movement. Extend this the way Level1Map/Level2Map do
    // (separate tables + collides() branching) once the Warden fight needs to seal the arena
    private static final boolean[] WALKABLE = {false, true, true, true, true};

    // Level3Map.png was authored at a smaller apparent in-game scale than Level2Map.png, even
    // though both PNGs happen to share the same raw pixel resolution - a straight 1:1 pixel-to-
    // world-unit mapping (what Level2Map uses) makes players/enemies (fixed world-unit size)
    // look oversized against it. Level1Map already has this same kind of gap between its raw
    // art and its world units (WORLD_W/WORLD_H are hardcoded, not derived from the PNG's own
    // pixel size); this applies the same fix the same way - a uniform scale, so nothing distorts
    // and collision (which reads worldW/worldH, not raw pixels) stays exact
    private static final float WORLD_SCALE = 1.64f;

    private final Texture background;
    private final CollisionMask mask;
    private final float worldW;
    private final float worldH;
    private final Rectangle spawnZone;
    private final Rectangle objectiveZone;
    private final Rectangle arenaZone;

    public Level3Map() {
        background = new Texture(Gdx.files.internal("Level3Map.png"));
        worldW = background.getWidth() * WORLD_SCALE;
        worldH = background.getHeight() * WORLD_SCALE;
        mask = new CollisionMask("Level3Mapcollision.png", worldW, worldH, PALETTE);

        List<Rectangle>[] zones = mask.findZones();
        spawnZone = singleZone(zones, CLASS_SPAWN, "player spawn");
        objectiveZone = singleZone(zones, CLASS_OBJECTIVE, "Warden directive core");
        arenaZone = singleZone(zones, CLASS_ARENA, "Warden arena");
    }

    private Rectangle singleZone(List<Rectangle>[] zones, byte cls, String what) {
        List<Rectangle> found = zones[cls];
        if (found.isEmpty()) {
            throw new IllegalStateException("Level3Mapcollision.png has no " + what + " painted in "
                + mask.hexOf(cls) + ". Re-export the mask layer of Level3Map.psd at 100% opacity.");
        }
        if (found.size() > 1) {
            Gdx.app.error("Level3Map", "Expected one " + what + " zone (" + mask.hexOf(cls)
                + "), found " + found.size() + "; using the first.");
        }
        return found.get(0);
    }

    // P1 gets the leftmost free spot on the spawn paint, P2 the rightmost. Same scan Level2Map uses
    public void placeAtSpawn(Player player, boolean leftmost) {
        float w = player.colliderWidth();
        float h = player.colliderHeight();
        float middleY = spawnZone.y + spawnZone.height / 2f;
        int firstX = (int) (spawnZone.x - w);
        int lastX = (int) (spawnZone.x + spawnZone.width);
        int step = leftmost ? 1 : -1;
        boolean found = false;
        float spotX = 0f, spotY = 0f;

        for (int x = leftmost ? firstX : lastX; !found && x >= firstX && x <= lastX; x += step) {
            float bestDy = Float.MAX_VALUE;
            for (int y = (int) (spawnZone.y - h); y <= (int) (spawnZone.y + spawnZone.height); y++) {
                if (mask.classAt(x + w / 2f, y + h / 2f) != CLASS_SPAWN) continue;
                if (mask.blocksBox(x, y, w, h, WALKABLE)) continue;
                float dy = Math.abs(y + h / 2f - middleY);
                if (dy < bestDy) {
                    bestDy = dy;
                    spotX = x;
                    spotY = y;
                    found = true;
                }
            }
        }
        if (!found) {
            throw new IllegalStateException("The spawn painted in Level3Mapcollision.png has no spot "
                + "a player fits in. Paint more spawn or floor around it.");
        }
        player.placeAt(spotX, spotY);
    }

    public Rectangle getSpawnZone() { return spawnZone; }

    // Extension point: nothing reads this yet, it's where the Warden's boss trigger will hook in
    public Rectangle getObjectiveZone() { return objectiveZone; }

    public Rectangle getArenaZone() { return arenaZone; }

    @Override
    public boolean collides(float x, float y, float w, float h, int playerSide) {
        return mask.blocksBox(x, y, w, h, WALKABLE);
    }

    @Override
    public float getWorldWidth() { return worldW; }

    @Override
    public float getWorldHeight() { return worldH; }

    public void render(SpriteBatch batch, OrthographicCamera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(background, 0, 0, worldW, worldH);
        batch.end();
    }

    // Highlights the objective and arena so both are visible before any real boss mechanic exists
    public void renderOverlays(ShapeRenderer shape, OrthographicCamera camera) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.setProjectionMatrix(camera.combined);

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(1f, 0.16f, 0.43f, 0.16f);
        shape.rect(arenaZone.x, arenaZone.y, arenaZone.width, arenaZone.height);
        shape.setColor(1f, 0.85f, 0.2f, 0.22f);
        shape.rect(objectiveZone.x, objectiveZone.y, objectiveZone.width, objectiveZone.height);
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(1f, 0.16f, 0.43f, 1f);
        shape.rect(arenaZone.x, arenaZone.y, arenaZone.width, arenaZone.height);
        shape.setColor(1f, 0.85f, 0.2f, 1f);
        shape.rect(objectiveZone.x, objectiveZone.y, objectiveZone.width, objectiveZone.height);
        shape.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // F1 debug overlay
    public void renderDebugCollision(SpriteBatch batch, OrthographicCamera camera) {
        mask.renderDebug(batch, camera);
    }

    public void dispose() {
        background.dispose();
        mask.dispose();
    }
}
