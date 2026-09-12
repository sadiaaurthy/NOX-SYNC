package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import io.github.fableops.Collidable;

/**
 * Level 2's map — a single illustrated background (Level2Map.png), with collision
 * driven entirely by a separate pixel mask (Level2_Walkable_Mask.png, via
 * MaskCollisionManager) instead of hand-traced rectangles, since the art is an
 * irregular maze with no regular grid to trace like Level1Map's floor plan.
 *
 * The map is intentionally asymmetric and not mirrored — see MaskCollisionManager's
 * class doc for the mask's color -> zone rules.
 */
public class Level2Map implements Collidable {

    private final Texture background;
    private final MaskCollisionManager collisionManager;
    private final float worldWidth;
    private final float worldHeight;

    private final float[] spawnP1;
    private final float[] spawnP2;

    public Level2Map() {
        background = new Texture(Gdx.files.internal("Level2Map.png"));
        collisionManager = new MaskCollisionManager("Level2_Walkable_Mask.png");
        worldWidth = collisionManager.getWidth();
        worldHeight = collisionManager.getHeight();

        // Anchors traced by eye off the mask art (cyan blob on the left = P1 side,
        // magenta blob on the right = P2 side), spiral-searched onto the nearest
        // actually-walkable pixel so an imprecise trace can never spawn a player inside
        // a wall. Expect to nudge these after playtesting, same caveat Level1Map's own
        // hand-traced coordinates carry.
        spawnP1 = findWalkableWorldPoint(150, 220, 400);
        spawnP2 = findWalkableWorldPoint(1150, 250, 400);
    }

    public float[] getSpawnP1() { return spawnP1; }
    public float[] getSpawnP2() { return spawnP2; }

    /** Converts an approximate image-pixel anchor into a guaranteed-walkable world point. */
    public float[] findWalkableWorldPoint(int anchorImageX, int anchorImageY, int maxRadius) {
        int[] imagePoint = collisionManager.findNearestWalkable(anchorImageX, anchorImageY, maxRadius);
        return new float[]{imagePoint[0], worldHeight - imagePoint[1]};
    }

    @Override
    public boolean collides(float x, float y, float w, float h, int playerSide) {
        // Cyan/magenta mark each player's usual path through the asymmetric map, not a
        // hard per-side lock the way Level1's gates are — both players can walk either
        // color, which is what lets them split up and reunite as the design calls for.
        return collisionManager.collidesBox(x, y, w, h);
    }

    @Override
    public float getWorldWidth() { return worldWidth; }

    @Override
    public float getWorldHeight() { return worldHeight; }

    public void render(SpriteBatch batch, OrthographicCamera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(background, 0, 0, worldWidth, worldHeight);
        batch.end();
    }

    /** F1 debug overlay — see MaskCollisionManager.renderDebugOverlay(). */
    public void renderDebugCollision(SpriteBatch batch, OrthographicCamera camera) {
        collisionManager.renderDebugOverlay(batch, camera, worldWidth, worldHeight);
    }

    public void dispose() {
        background.dispose();
        collisionManager.dispose();
    }
}
