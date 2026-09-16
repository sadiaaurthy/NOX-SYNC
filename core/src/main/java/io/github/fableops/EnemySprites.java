package io.github.fableops;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import java.util.Arrays;
import java.util.List;

// Walk sheet rows: down, up, left, right. Kill sheet rows 2 and 3 are the death animations
public class EnemySprites {

    public static final float DRAW_SIZE = 120f;

    private static final int ROWS = 4;
    private static final int WALK_FRAMES = 8;
    private static final int DEATH_FRAMES = 7;
    private static final int DEATH_ROW_LEFT = 2;
    private static final int DEATH_ROW_RIGHT = 3;
    private static final float WALK_FRAME_TIME = 0.12f;
    private static final float DEATH_FRAME_TIME = 0.09f;
    private static final int ROW_PADDING = 2;
    // A 1px gap inside a frame (between the legs) shouldn't split it
    private static final int FRAME_GAP_TOLERANCE = 1;

    // 8 frames of time for the 7 frame death, so the last frame stays for a moment
    public static final float DEATH_DURATION = WALK_FRAMES * DEATH_FRAME_TIME;

    final SpriteBounds bounds;

    private final Texture walkSheet;
    private final Texture killSheet;
    private final float scale;
    private final Animation<TextureRegion>[] walk = newAnimationArray(ROWS);
    private final Animation<TextureRegion>[] death = newAnimationArray(2);

    @SuppressWarnings("unchecked")
    private static Animation<TextureRegion>[] newAnimationArray(int size) {
        return new Animation[size];
    }

    public EnemySprites() {
        // The file name really is spelled "enenmy".
        Pixmap walkPixels = new Pixmap(Gdx.files.internal("enenmyswarmspritesheet.png"));
        Pixmap killPixels = new Pixmap(Gdx.files.internal("enemyswarmkillanddestroyspritesheet.png"));
        walkSheet = new Texture(walkPixels);
        killSheet = new Texture(killPixels);
        scale = DRAW_SIZE / (walkPixels.getWidth() / (float) WALK_FRAMES);

        int[][] walkRows = rowBands(walkPixels, "walk");
        TextureRegion[] walkFrames = new TextureRegion[ROWS * WALK_FRAMES];
        for (int row = 0; row < ROWS; row++) {
            TextureRegion[] frames = sliceRow(walkSheet, walkPixels, walkRows[row], WALK_FRAMES);
            System.arraycopy(frames, 0, walkFrames, row * WALK_FRAMES, WALK_FRAMES);
            walk[row] = new Animation<>(WALK_FRAME_TIME, frames);
        }
        float[] scales = new float[walkFrames.length];
        Arrays.fill(scales, scale);
        bounds = new SpriteBounds(walkPixels, walkFrames, scales, DRAW_SIZE);

        int[][] killRows = rowBands(killPixels, "kill");
        death[0] = new Animation<>(DEATH_FRAME_TIME,
            sliceRow(killSheet, killPixels, killRows[DEATH_ROW_LEFT], DEATH_FRAMES));
        death[1] = new Animation<>(DEATH_FRAME_TIME,
            sliceRow(killSheet, killPixels, killRows[DEATH_ROW_RIGHT], DEATH_FRAMES));

        walkPixels.dispose();
        killPixels.dispose();
    }

    // Each row is cut to its content plus padding, so the rows above and below don't leak in
    private static int[][] rowBands(Pixmap pixels, String sheet) {
        int height = pixels.getHeight();
        List<int[]> runs = SpriteSheetSlicer.runs(pixels, false, 0, pixels.getWidth(), 0);
        int[][] bands = new int[ROWS][2];
        if (runs.size() != ROWS) {
            Gdx.app.error("EnemySprites", "Expected " + ROWS + " rows in the " + sheet
                + " sheet, measured " + runs.size() + "; falling back to an even grid.");
            int[] even = SpriteSheetSlicer.uniform(height, ROWS);
            for (int row = 0; row < ROWS; row++) {
                bands[row][0] = even[row];
                bands[row][1] = even[row + 1];
            }
            return bands;
        }
        for (int row = 0; row < ROWS; row++) {
            bands[row][0] = Math.max(0, runs.get(row)[0] - ROW_PADDING);
            bands[row][1] = Math.min(height, runs.get(row)[1] + 1 + ROW_PADDING);
        }
        return bands;
    }

    private static TextureRegion[] sliceRow(Texture sheet, Pixmap pixels, int[] band, int frames) {
        int width = pixels.getWidth();
        int[] columns = SpriteSheetSlicer.midpoints(
            SpriteSheetSlicer.runs(pixels, true, band[0], band[1], FRAME_GAP_TOLERANCE), width, frames);
        if (columns == null) {
            Gdx.app.error("EnemySprites", "Expected " + frames + " frames in the row at y "
                + band[0] + "-" + band[1] + "; falling back to an even grid.");
            columns = SpriteSheetSlicer.uniform(width, frames);
        }
        TextureRegion[] regions = new TextureRegion[frames];
        for (int i = 0; i < frames; i++) {
            regions[i] = new TextureRegion(sheet, columns[i], band[0],
                columns[i + 1] - columns[i], band[1] - band[0]);
        }
        return regions;
    }

    // direction: 0 down, 1 up, 2 left, 3 right
    public TextureRegion walkFrame(int direction, float stateTime) {
        return walk[direction].getKeyFrame(stateTime, true);
    }

    // There are only left and right death animations
    public TextureRegion deathFrame(boolean facingRight, float deathTime) {
        return death[facingRight ? 1 : 0].getKeyFrame(deathTime, false);
    }

    // Frames have different sizes, so they all get the same scale and are lined up at the top
    public void draw(SpriteBatch batch, TextureRegion frame, float x, float y) {
        float drawW = frame.getRegionWidth() * scale;
        float drawH = frame.getRegionHeight() * scale;
        batch.draw(frame, x - bounds.footX + (DRAW_SIZE - drawW) / 2f,
            y - bounds.footY + DRAW_SIZE - drawH, drawW, drawH);
    }

    // Skips the enemies outside this camera's view
    public void drawAll(SpriteBatch batch, OrthographicCamera camera, List<Enemy> enemies) {
        for (int i = 0; i < enemies.size(); i++) {
            Enemy enemy = enemies.get(i);
            if (isOnScreen(camera, enemy.x, enemy.y)) enemy.draw(batch, this);
        }
    }

    // A client only knows positions, so it draws a standing frame
    public void drawRemote(SpriteBatch batch, OrthographicCamera camera, List<float[]> positions) {
        TextureRegion standing = walkFrame(0, 0f);
        for (int i = 0; i < positions.size(); i++) {
            float[] position = positions.get(i);
            if (isOnScreen(camera, position[0], position[1])) draw(batch, standing, position[0], position[1]);
        }
    }

    private static boolean isOnScreen(OrthographicCamera camera, float x, float y) {
        return Math.abs(x - camera.position.x) <= camera.viewportWidth / 2f + DRAW_SIZE
            && Math.abs(y - camera.position.y) <= camera.viewportHeight / 2f + DRAW_SIZE;
    }

    public void dispose() {
        walkSheet.dispose();
        killSheet.dispose();
    }
}
