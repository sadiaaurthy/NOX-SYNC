package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/**
 * Collision driven by a Photoshop-painted pixel mask instead of hand-traced rectangles
 * (Level 2's art has no regular grid to trace against, unlike Level1Map). The mask is
 * kept as a CPU-side Pixmap for sampling — it is never itself the render texture, only
 * an optional debug overlay copy is ever uploaded to the GPU.
 *
 * Mask color -> zone rules (per the Level 2 design spec):
 *   BLACK   = blocked, players cannot move there
 *   CYAN    = walkable, Player A's usual path
 *   MAGENTA = walkable, Player B's usual path
 *   YELLOW  = walkable, special interaction/objective zone
 * Both players are allowed onto both cyan and magenta ground — see Level2Map's collides()
 * for why the color isn't used as a hard per-player lock the way Level1's gates are.
 */
public class MaskCollisionManager {

    private static final int COLOR_TOLERANCE = 40;

    public enum ZoneType { BLOCKED, CYAN_PATH, MAGENTA_PATH, OBJECTIVE }

    private final Pixmap mask;
    private final int width;
    private final int height;

    // Lazily built GPU copy for the F1 debug overlay only — the Pixmap above stays the
    // single source of truth for collision sampling.
    private Texture debugOverlayTexture;

    public MaskCollisionManager(String maskFile) {
        mask = new Pixmap(Gdx.files.internal(maskFile));
        width = mask.getWidth();
        height = mask.getHeight();
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /** World space is Y-up, image space is Y-down — same convention Level1Map uses. */
    private int worldToImageX(float worldX) { return (int) worldX; }
    private int worldToImageY(float worldY) { return height - 1 - (int) worldY; }

    public ZoneType zoneAt(float worldX, float worldY) {
        return classify(worldToImageX(worldX), worldToImageY(worldY));
    }

    public boolean isWalkable(float worldX, float worldY) {
        return zoneAt(worldX, worldY) != ZoneType.BLOCKED;
    }

    /**
     * Samples the box's four corners plus its center — matches the granularity
     * Level1Map's rectangle-based collides() effectively gives, cheap enough to run
     * twice per axis for every player and enemy each frame.
     */
    public boolean collidesBox(float x, float y, float w, float h) {
        return !isWalkable(x, y)
            || !isWalkable(x + w, y)
            || !isWalkable(x, y + h)
            || !isWalkable(x + w, y + h)
            || !isWalkable(x + w / 2f, y + h / 2f);
    }

    private ZoneType classify(int px, int py) {
        if (px < 0 || py < 0 || px >= width || py >= height) return ZoneType.BLOCKED;

        int rgba = mask.getPixel(px, py); // RGBA8888, per Pixmap#getPixel's contract
        int r = (rgba >>> 24) & 0xFF;
        int g = (rgba >>> 16) & 0xFF;
        int b = (rgba >>> 8) & 0xFF;
        int a = rgba & 0xFF;
        if (a < 10) return ZoneType.BLOCKED; // fully transparent = outside the painted footprint

        if (closeTo(r, g, b, 255, 255, 0)) return ZoneType.OBJECTIVE;
        if (closeTo(r, g, b, 0, 255, 255)) return ZoneType.CYAN_PATH;
        if (closeTo(r, g, b, 255, 0, 255)) return ZoneType.MAGENTA_PATH;
        return ZoneType.BLOCKED; // black, or anything unrecognized, defaults to blocked
    }

    private static boolean closeTo(int r, int g, int b, int tr, int tg, int tb) {
        return Math.abs(r - tr) <= COLOR_TOLERANCE
            && Math.abs(g - tg) <= COLOR_TOLERANCE
            && Math.abs(b - tb) <= COLOR_TOLERANCE;
    }

    /**
     * Spirals outward in image space from (anchorX, anchorY) until it finds a walkable
     * pixel, so a coordinate that's only approximately traced off the art (spawn points,
     * the Core's starting spot) can never land inside a wall. Falls back to the anchor
     * itself if nothing walkable turns up within maxRadius.
     */
    public int[] findNearestWalkable(int anchorX, int anchorY, int maxRadius) {
        if (classify(anchorX, anchorY) != ZoneType.BLOCKED) return new int[]{anchorX, anchorY};

        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue; // ring only
                    int px = anchorX + dx, py = anchorY + dy;
                    if (classify(px, py) != ZoneType.BLOCKED) return new int[]{px, py};
                }
            }
        }
        return new int[]{anchorX, anchorY};
    }

    /** F1 debug aid — tints the raw mask over the art so collision can be checked against it. */
    public void renderDebugOverlay(SpriteBatch batch, OrthographicCamera camera, float worldWidth, float worldHeight) {
        if (debugOverlayTexture == null) {
            debugOverlayTexture = new Texture(mask);
        }
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.setColor(1f, 1f, 1f, 0.45f);
        batch.draw(debugOverlayTexture, 0, 0, worldWidth, worldHeight);
        batch.setColor(1f, 1f, 1f, 1f);
        batch.end();
    }

    public void dispose() {
        mask.dispose();
        if (debugOverlayTexture != null) debugOverlayTexture.dispose();
    }
}
