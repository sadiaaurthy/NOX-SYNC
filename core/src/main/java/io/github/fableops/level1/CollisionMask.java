package io.github.fableops.level1;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-pixel collision read from a painted mask image instead of hand-traced rectangles.
 *
 * The mask is authored as a layer in assets/Level1Map.psd and exported to
 * assets/Level1Mapcollision.png — LibGDX can't decode .psd, so the PSD stays the editable
 * source and the PNG is what ships. Every pixel is one of a small palette of flat
 * colours, each meaning one kind of surface (see the CLASS_* constants).
 *
 * Why this replaces rectangles: a union of rectangles could only approximate the art's
 * octagonal chamber and angled walls, and the old "hitbox must fit entirely inside ONE
 * rectangle" test meant neighbouring rooms had to overlap by a full player width or the
 * seam became an invisible wall. A mask has no seams, no overlap bookkeeping, and no
 * minimum corridor width — the painted shape is the collision, exactly.
 *
 * Mask resolution is independent of world size: lookups map world coordinates to mask
 * pixels by ratio, so the mask can be repainted at any resolution without code changes.
 * Y is flipped on lookup — image rows run top-to-bottom, world space runs bottom-to-top.
 */
public class CollisionMask {

    public static final byte CLASS_VOID   = 0; // wall / outside the building
    public static final byte CLASS_SHARED = 1; // floor either player may stand on
    public static final byte CLASS_P1     = 2; // Player 1's wing only
    public static final byte CLASS_P2     = 3; // Player 2's wing only
    public static final byte CLASS_GATE   = 4; // walkable only once the gates open
    public static final byte CLASS_PLATE  = 5; // floor, also a pressure plate
    public static final byte CLASS_TERM   = 6; // floor, also a terminal zone

    /**
     * Palette the painted mask is matched against, indexed by CLASS_*.
     *
     * CLASS_TERM is neon green 00FF11, which is what the terminals are actually painted
     * with — not the FF7F00 orange originally reserved for them, which nothing uses.
     *
     * Caution when painting: 00FF11 (terminal) and 00FF00 (plate) differ by 17 in one
     * channel only. Because pixels snap to the NEAREST palette entry, the boundary
     * between them sits at blue 8 — so a terminal pixel needs only the faintest blue
     * shift to be read as a pressure plate. They are safe while the export is flat, but
     * if plates are ever painted, moving one of the two onto the now-unused FF7F00 would
     * remove the hazard entirely.
     */
    private static final int[][] PALETTE = {
        {0, 0, 0}, {255, 255, 255}, {0, 255, 255}, {255, 0, 255},
        {255, 255, 0}, {0, 255, 0}, {0, 255, 17}
    };

    /** Below this a blob is stray paint, not an authored zone. Real zones are ~1200px. */
    private static final int MIN_ZONE_PIXELS = 200;

    private final int width;
    private final int height;
    private final float worldW;
    private final float worldH;
    /** One class byte per mask pixel — ~1.5MB at 1678x937, far cheaper than keeping the Pixmap. */
    private final byte[] classes;

    /** Built lazily, only if the F1 debug overlay is actually used. */
    private Texture debugTexture;
    private final String maskPath;

    public CollisionMask(String maskPath, float worldW, float worldH) {
        this.maskPath = maskPath;
        this.worldW = worldW;
        this.worldH = worldH;

        Pixmap pixmap = new Pixmap(Gdx.files.internal(maskPath));
        width = pixmap.getWidth();
        height = pixmap.getHeight();
        classes = new byte[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = pixmap.getPixel(x, y);
                classes[y * width + x] = nearestClass(
                    (rgba >>> 24) & 0xFF, (rgba >>> 16) & 0xFF, (rgba >>> 8) & 0xFF);
            }
        }
        // The pixel data now lives in `classes`; the Pixmap itself is dead weight.
        pixmap.dispose();
    }

    /**
     * Snaps a pixel to the closest palette entry rather than requiring an exact match,
     * so anti-aliased edges from the paint program still resolve to a sensible surface
     * instead of failing to match and silently becoming void.
     */
    private static byte nearestClass(int r, int g, int b) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int dr = r - PALETTE[i][0], dg = g - PALETTE[i][1], db = b - PALETTE[i][2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return (byte) best;
    }

    /** Human-readable name plus the hex colour to paint, for diagnostics. */
    public static String describeClass(byte cls) {
        int[] rgb = PALETTE[cls];
        return String.format("%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
    }

    /** Surface class at a world point; anything outside the world reads as void. */
    public byte classAt(float worldX, float worldY) {
        int px = (int) (worldX * width / worldW);
        int py = (int) ((worldH - worldY) * height / worldH);
        if (px < 0 || py < 0 || px >= width || py >= height) return CLASS_VOID;
        return classes[py * width + px];
    }

    /**
     * Whether the given player may stand on the surface at this world point.
     *
     * The wings are private only while the reactor is locked — that separation is what
     * forces the puzzle to be solved by two people who cannot see each other's terminals.
     * Once every stage is done that reason is gone, so the restriction lifts: both players
     * can cross into either wing and the shared room, which is how they reach the reactor
     * chamber and the two pressure plates at all. Keeping them penned in afterwards made
     * the ending unreachable for whichever side the plates were not on.
     *
     * @param reactorUnlocked all three stages solved — see Level1Map.unlockReactor()
     */
    public boolean isWalkable(float worldX, float worldY, int playerSide, boolean reactorUnlocked) {
        switch (classAt(worldX, worldY)) {
            case CLASS_SHARED:
            case CLASS_PLATE:
            case CLASS_TERM:
                return true;
            case CLASS_P1:
                return playerSide == 1 || reactorUnlocked;
            case CLASS_P2:
                return playerSide == 2 || reactorUnlocked;
            case CLASS_GATE:
                return reactorUnlocked;
            default:
                return false; // CLASS_VOID
        }
    }

    /**
     * Finds every separately painted blob of one class and returns its bounds in world
     * space. This is what lets interaction zones be authored by painting them rather than
     * by hand-typing coordinates that then drift out of step with the art.
     *
     * Blobs are 8-connected, so a diagonal join still counts as one region, and anything
     * smaller than MIN_ZONE_PIXELS is discarded as stray paint or a compression artefact
     * rather than becoming a phantom zone.
     */
    public List<Rectangle> findZones(byte target) {
        List<Rectangle> zones = new ArrayList<>();
        boolean[] visited = new boolean[width * height];
        int[] queue = new int[width * height];
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy = {0, 0, 1, -1, 1, -1, 1, -1};

        for (int start = 0; start < classes.length; start++) {
            if (classes[start] != target || visited[start]) continue;

            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            int minX = width, maxX = -1, minY = height, maxY = -1, count = 0;

            while (head < tail) {
                int current = queue[head++];
                int cx = current % width, cy = current / width;
                count++;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;

                for (int k = 0; k < 8; k++) {
                    int nx = cx + dx[k], ny = cy + dy[k];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int next = ny * width + nx;
                    if (classes[next] != target || visited[next]) continue;
                    visited[next] = true;
                    queue[tail++] = next;
                }
            }
            if (count < MIN_ZONE_PIXELS) continue;

            // Image rows run top-to-bottom, world space bottom-to-top — so the blob's
            // bottom edge in world space comes from its MAXIMUM image row.
            float wx = minX * worldW / width;
            float wy = worldH - (maxY + 1) * worldH / height;
            float ww = (maxX - minX + 1) * worldW / width;
            float wh = (maxY - minY + 1) * worldH / height;
            zones.add(new Rectangle(wx, wy, ww, wh));
        }
        return zones;
    }

    /** The painted mask as a texture, for the debug overlay only. Created on first use. */
    public Texture getDebugTexture() {
        if (debugTexture == null) {
            debugTexture = new Texture(Gdx.files.internal(maskPath));
        }
        return debugTexture;
    }

    public void dispose() {
        if (debugTexture != null) debugTexture.dispose();
    }
}
