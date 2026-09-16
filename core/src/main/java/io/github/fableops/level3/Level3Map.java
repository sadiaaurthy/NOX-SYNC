package io.github.fableops.level3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.fableops.Collidable;
import io.github.fableops.Player;
import io.github.fableops.world.CollisionMask;

// Everything comes from Layer 1 of Level3Map.psd, exported to Level3Mapcollision.png:
// 000000 void/wall, 000FFF the walkable road, FFFF00 the spawn pockets (one per player),
// FF00FF the strip that starts the boss fight once both players stand on it.
// Layer 2 of the same PSD marks the tower top, cut out to Level3Overhang.png and drawn after
// the players so the road behind the tower reads as being behind it
public class Level3Map implements Collidable {

    private static final byte CLASS_SPAWN = 2;
    private static final byte CLASS_BOSS = 3;

    //                                     void   road   spawn  boss
    private static final int[][] PALETTE = {
        {0, 0, 0}, {0, 15, 255}, {255, 255, 0}, {255, 0, 255}
    };

    // Nothing is sealed off in Level 3, so only the void blocks movement. Extend this the way
    // Level1Map does (separate tables + collides() branching) if the boss fight has to lock the road
    private static final boolean[] WALKABLE = {false, true, true, true};

    // The city art is drawn from much further off than Level 1 or 2, so a fixed 100-unit operator
    // read as tall as the pines at 1.64. The camera always shows 720 world units, so only this
    // scale decides how big the map looks against them. Judged against the trees, street lamps and
    // crossing stripes. Collision reads worldW/worldH, not raw pixels, so it stays exact
    private static final float WORLD_SCALE = 3.0f;

    // Top-left of the tower top in the PSD, in image pixels (Y down). This is the bounding box of
    // the paint on Layer 2; re-exporting the overhang prints these numbers
    private static final float OVERHANG_PX_X = 878f;
    private static final float OVERHANG_PX_Y = 79f;

    private final Texture background;
    private final Texture overhang;
    private final CollisionMask mask;
    private final float worldW;
    private final float worldH;
    // Only the topmost patch of yellow is a real spawn; anything painted lower is left over
    private final Rectangle spawnZone;
    private final Rectangle bossZone;
    private final Rectangle overhangRect;

    public Level3Map() {
        background = new Texture(Gdx.files.internal("Level3Map.png"));
        overhang = new Texture(Gdx.files.internal("Level3Overhang.png"));
        worldW = background.getWidth() * WORLD_SCALE;
        worldH = background.getHeight() * WORLD_SCALE;
        mask = new CollisionMask("Level3Mapcollision.png", worldW, worldH, PALETTE);

        List<Rectangle>[] zones = mask.findZones();
        List<Rectangle> painted = new ArrayList<>(zones[CLASS_SPAWN]);
        if (painted.isEmpty()) {
            throw new IllegalStateException("Level3Mapcollision.png has no spawn painted in "
                + mask.hexOf(CLASS_SPAWN) + ". Re-export Layer 1 of Level3Map.psd.");
        }
        // Highest on the map wins, so stray yellow further down never steals a player
        painted.sort(Comparator.comparingDouble(r -> -(r.y + r.height)));
        spawnZone = painted.get(0);
        if (painted.size() > 1) {
            Gdx.app.log("Level3Map", "Found " + painted.size() + " patches of spawn paint ("
                + mask.hexOf(CLASS_SPAWN) + "); using the topmost and ignoring the rest.");
        }
        if (zones[CLASS_BOSS].isEmpty()) {
            throw new IllegalStateException("Level3Mapcollision.png has no boss trigger painted in "
                + mask.hexOf(CLASS_BOSS) + ". Re-export Layer 1 of Level3Map.psd.");
        }
        bossZone = zones[CLASS_BOSS].get(0);

        // Y flips: the PSD measures down from the top, the world measures up from the bottom
        overhangRect = new Rectangle(
            OVERHANG_PX_X * WORLD_SCALE,
            worldH - (OVERHANG_PX_Y + overhang.getHeight()) * WORLD_SCALE,
            overhang.getWidth() * WORLD_SCALE,
            overhang.getHeight() * WORLD_SCALE);
    }

    // Both operators start on the same patch of yellow, one on each side of it, the way Level 2 does
    public void placeAtSpawn(Player player, boolean leftmost) {
        Rectangle zone = spawnZone;
        float w = player.colliderWidth();
        float h = player.colliderHeight();
        float middleY = zone.y + zone.height / 2f;
        int firstX = (int) (zone.x - w);
        int lastX = (int) (zone.x + zone.width);
        int step = leftmost ? 1 : -1;
        boolean found = false;
        float spotX = 0f, spotY = 0f;

        for (int x = leftmost ? firstX : lastX; !found && x >= firstX && x <= lastX; x += step) {
            float bestDy = Float.MAX_VALUE;
            for (int y = (int) (zone.y - h); y <= (int) (zone.y + zone.height); y++) {
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
                + "a player fits in. Paint more spawn or road around it.");
        }
        player.placeAt(spotX, spotY);
    }

    public Rectangle getSpawnZone() { return spawnZone; }

    public Rectangle getBossZone() { return bossZone; }

    // Both operators have to be standing on the strip, so neither can start the fight alone
    public boolean bothOnBossTrigger(Player p1, Player p2) {
        return p1.colliderOverlaps(bossZone) && p2.colliderOverlaps(bossZone);
    }

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

    // Call inside the same batch pass as the players, after they are drawn. The tower top then
    // covers anyone standing on the road behind it, which is what sells the depth
    public void renderOverhang(SpriteBatch batch) {
        batch.draw(overhang, overhangRect.x, overhangRect.y, overhangRect.width, overhangRect.height);
    }

    // The trigger strip is only painted in the mask, so it needs a marker in the world to be findable
    public void renderOverlays(ShapeRenderer shape, OrthographicCamera camera, boolean bossStarted) {
        if (bossStarted) return;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.setProjectionMatrix(camera.combined);

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(1f, 0.16f, 0.43f, 0.22f);
        shape.rect(bossZone.x, bossZone.y, bossZone.width, bossZone.height);
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(1f, 0.16f, 0.43f, 0.9f);
        shape.rect(bossZone.x, bossZone.y, bossZone.width, bossZone.height);
        shape.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // F1 debug overlay
    public void renderDebugCollision(SpriteBatch batch, OrthographicCamera camera) {
        mask.renderDebug(batch, camera);
    }

    public void dispose() {
        background.dispose();
        overhang.dispose();
        mask.dispose();
    }
}
