package io.github.fableops;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Loads the two enemy sheets once and hands out their animations, so a swarm of
 * 20+ enemies shares a single copy of each 1.3MB texture instead of loading its own.
 *
 * Both sheets are 1774x887 — exactly 8 columns x 4 rows of square cells — and are
 * sliced on that uniform grid rather than by trimming each frame to its visible
 * pixels. That matters for the death effect: trimming would re-centre every frame on
 * its own content, so a dying enemy would visibly jump sideways as the animation
 * collapses. Identical grid + identical draw rect keeps death locked to where the
 * enemy was standing.
 *
 * Walk sheet rows: 0 = down, 1 = up, 2 = left, 3 = right (8 frames each).
 * Kill sheet rows: 0 = attack left, 1 = attack right, 2 = death left, 3 = death right.
 */
public class EnemySprites {

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final float WALK_FRAME_TIME = 0.12f;
    private static final float DEATH_FRAME_TIME = 0.09f;

    /** How long a death animation plays before the corpse is removed. */
    public static final float DEATH_DURATION = COLUMNS * DEATH_FRAME_TIME;

    private final Texture walkSheet;
    private final Texture killSheet;
    private final Animation<TextureRegion>[] walk = newAnimationArray(ROWS);
    private final Animation<TextureRegion>[] death = newAnimationArray(2);

    @SuppressWarnings("unchecked")
    private static Animation<TextureRegion>[] newAnimationArray(int size) {
        return new Animation[size];
    }

    public EnemySprites() {
        // Filename really is spelled "enenmy" in assets/ — kept verbatim so it loads.
        walkSheet = new Texture(Gdx.files.internal("enenmyswarmspritesheet.png"));
        killSheet = new Texture(Gdx.files.internal("enemyswarmkillanddestroyspritesheet.png"));

        for (int row = 0; row < ROWS; row++) {
            walk[row] = new Animation<>(WALK_FRAME_TIME, sliceRow(walkSheet, row));
        }
        // Rows 2 and 3 of the kill sheet are the death sequences (left / right).
        death[0] = new Animation<>(DEATH_FRAME_TIME, sliceRow(killSheet, 2));
        death[1] = new Animation<>(DEATH_FRAME_TIME, sliceRow(killSheet, 3));
    }

    private static TextureRegion[] sliceRow(Texture sheet, int row) {
        float cellW = sheet.getWidth() / (float) COLUMNS;
        float cellH = sheet.getHeight() / (float) ROWS;
        int y0 = Math.round(row * cellH);
        int y1 = Math.round((row + 1) * cellH);

        TextureRegion[] frames = new TextureRegion[COLUMNS];
        for (int col = 0; col < COLUMNS; col++) {
            int x0 = Math.round(col * cellW);
            int x1 = Math.round((col + 1) * cellW);
            frames[col] = new TextureRegion(sheet, x0, y0, x1 - x0, y1 - y0);
        }
        return frames;
    }

    /** direction: 0 = down, 1 = up, 2 = left, 3 = right. */
    public TextureRegion walkFrame(int direction, float stateTime) {
        return walk[direction].getKeyFrame(stateTime, true);
    }

    /** Death only exists facing left or right, so up/down callers pick a side. */
    public TextureRegion deathFrame(boolean facingRight, float deathTime) {
        return death[facingRight ? 1 : 0].getKeyFrame(deathTime, false);
    }

    public void dispose() {
        walkSheet.dispose();
        killSheet.dispose();
    }
}
