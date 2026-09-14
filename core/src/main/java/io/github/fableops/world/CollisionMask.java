package io.github.fableops.world;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;

import java.util.ArrayList;
import java.util.List;

// Collision from a painted mask PNG. Each pixel becomes a class byte at load (nearest
// palette colour) and the image is freed. Class 0 has to be the wall/void colour
public final class CollisionMask {

    // 3x3 sample points, so a body can't squeeze through a wall thinner than itself
    private static final float[] SAMPLE_FRACTIONS = {0f, 0.5f, 1f};

    // Smaller blobs are stray paint
    private static final int MIN_ZONE_PIXELS = 200;

    private final int width;
    private final int height;
    private final float worldW;
    private final float worldH;
    private final int[][] palette;
    private final byte[] classes;
    private final String maskPath;

    // Only loaded when F1 is used
    private Texture debugTexture;

    public CollisionMask(String maskPath, float worldW, float worldH, int[][] palette) {
        this.maskPath = maskPath;
        this.worldW = worldW;
        this.worldH = worldH;
        this.palette = palette;

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
        pixmap.dispose();
    }

    // Nearest colour, so soft brush edges still work
    private byte nearestClass(int r, int g, int b) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int dr = r - palette[i][0], dg = g - palette[i][1], db = b - palette[i][2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return (byte) best;
    }

    public String hexOf(byte cls) {
        int[] rgb = palette[cls];
        return String.format("%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
    }

    // Outside the mask counts as class 0
    public byte classAt(float worldX, float worldY) {
        int px = (int) (worldX * width / worldW);
        int py = (int) ((worldH - worldY) * height / worldH);
        if (px < 0 || py < 0 || px >= width || py >= height) return 0;
        return classes[py * width + px];
    }

    // Takes a lookup table instead of a callback so nothing is allocated
    public boolean blocksBox(float x, float y, float w, float h, boolean[] walkable) {
        for (float fx : SAMPLE_FRACTIONS) {
            float sampleX = x + fx * (w - 1f);
            for (float fy : SAMPLE_FRACTIONS) {
                if (!walkable[classAt(sampleX, y + fy * (h - 1f))]) return true;
            }
        }
        return false;
    }

    // Bounding boxes of every painted blob, per class (8-connected flood fill). Load time only
    @SuppressWarnings("unchecked")
    public List<Rectangle>[] findZones() {
        List<Rectangle>[] zones = new List[palette.length];
        for (int i = 0; i < zones.length; i++) zones[i] = new ArrayList<>();
        boolean[] visited = new boolean[classes.length];
        int[] queue = new int[classes.length];
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy = {0, 0, 1, -1, 1, -1, 1, -1};

        for (int start = 0; start < classes.length; start++) {
            if (visited[start]) continue;
            byte target = classes[start];

            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            int minX = width, maxX = -1, minY = height, maxY = -1;

            while (head < tail) {
                int current = queue[head++];
                int cx = current % width, cy = current / width;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;

                for (int k = 0; k < 8; k++) {
                    int nx = cx + dx[k], ny = cy + dy[k];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int next = ny * width + nx;
                    if (visited[next] || classes[next] != target) continue;
                    visited[next] = true;
                    queue[tail++] = next;
                }
            }
            if (target == 0 || tail < MIN_ZONE_PIXELS) continue;

            // The blob's bottom edge in world space comes from its lowest image row.
            zones[target].add(new Rectangle(
                minX * worldW / width,
                worldH - (maxY + 1) * worldH / height,
                (maxX - minX + 1) * worldW / width,
                (maxY - minY + 1) * worldH / height));
        }
        return zones;
    }

    // F1 overlay
    public void renderDebug(SpriteBatch batch, OrthographicCamera camera) {
        if (debugTexture == null) debugTexture = new Texture(Gdx.files.internal(maskPath));
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.setColor(1f, 1f, 1f, 0.5f);
        batch.draw(debugTexture, 0, 0, worldW, worldH);
        batch.setColor(1f, 1f, 1f, 1f);
        batch.end();
    }

    public void dispose() {
        if (debugTexture != null) debugTexture.dispose();
    }
}
