package io.github.fableops;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Loads the two enemy sheets once and hands out their animations, so a swarm of
 * 20+ enemies shares a single copy of each 1.3MB texture instead of loading its own.
 *
 * Both sheets are 1774x887. Three of the four walk rows are a clean uniform 8 columns
 * x 4 rows — confirmed by direct pixel inspection, every frame sits centered in its
 * nominal cell with wide transparent margins on both sides. Row 2 (left-facing walk)
 * is the one exception: a connected-component scan of every one of its 8 columns found
 * a second, disconnected blob of pixels (4-6% of that frame's content) sitting well
 * below the character's own feet, separated from it by a clean transparent gap — a
 * direct visual crop confirmed this is the very top of row 3's (right-facing) head
 * plus a stray dot, both leaking upward out of row 3's own cell into the bottom of
 * row 2's. Trimming row 2's cell height below that leak point (see
 * WALK_LEFT_TRIMMED_HEIGHT) removes it without touching the other three walk rows,
 * which have no such leak (row 3 itself scanned as a single clean blob per column).
 *
 * The kill sheet's two active death rows (2 and 3) do NOT follow the uniform grid
 * either: pixel inspection of the real asset found the death sequence is seven
 * irregularly spaced frame groups, not eight equal-width cells (see
 * DEATH_LEFT_BOUNDARIES / DEATH_RIGHT_BOUNDARIES below) — slicing those rows on a
 * uniform 8-column grid used to cut through real frame artwork and could blend two
 * different frames into one region. A separate connected-component scan of all 14
 * death frames at their current (corrected) boundaries found only small, scattered
 * secondary blobs consistent with intentional explosion/debris art, not neighboring-
 * frame bleed — no further region change was needed there.
 *
 * Walk sheet rows: 0 = down, 1 = up, 2 = left, 3 = right (8 frames each).
 * Kill sheet rows: 0 = attack left, 1 = attack right (unused — no attack animation
 * exists yet), 2 = death left, 3 = death right (7 real frames each, see above).
 */
public class EnemySprites {

    private static final int WALK_FRAME_COUNT = 8;
    private static final int ROWS = 4;
    private static final float WALK_FRAME_TIME = 0.12f;
    private static final float DEATH_FRAME_TIME = 0.09f;

    private static final int DEATH_FRAME_COUNT = 7;

    // Row 2 (left-facing walk) only — every other walk row keeps the full uniform
    // cell height. Measured directly: the real character content in row 2 never
    // extends past local y=167 in any of its 8 columns, and the leaked row-3 content
    // never starts before local y=199 in any column — 190 sits safely in that gap
    // (23px margin above the character, 9px margin before the leak) and is used
    // uniformly for the whole row rather than per-column, so no column jumps
    // relative to the others.
    private static final int WALK_LEFT_ROW = 2;
    private static final int WALK_LEFT_TRIMMED_HEIGHT = 190;

    /**
     * The uniform grid cell size (both axes, both sheets: 1774/8 columns ==
     * 887/4 rows == 221.75) that Enemy.DRAW_SIZE is calibrated against. Frames whose
     * actual TextureRegion is smaller than this in either dimension — the row-2 walk
     * height trim above, or the death rows' irregular per-frame widths — are scaled by
     * Enemy.draw() relative to this reference instead of being stretched to fill a
     * fixed box, so every frame keeps the same per-pixel scale.
     */
    public static final float CELL_SIZE = 1774f / WALK_FRAME_COUNT;

    // Measured directly from the actual asset: an alpha-content scan of rows 2/3
    // (threshold 20), with gaps of 1px treated as noise inside a single frame rather
    // than a real gutter between frames. Boundaries sit at the midpoint of each
    // genuine transparent gutter between the seven real frame groups, so every
    // frame's full content — including any smoke, spark, or fragment pixels reaching
    // close to a neighboring frame — stays intact in exactly one region. Row 2 =
    // death-left, row 3 = death-right (unchanged mapping).
    private static final int[] DEATH_LEFT_BOUNDARIES  = {0, 250, 492, 758, 1020, 1284, 1548, 1774};
    private static final int[] DEATH_RIGHT_BOUNDARIES = {0, 242, 478, 740, 1009, 1278, 1532, 1774};

    /**
     * How long a death animation plays before the corpse is removed. Deliberately
     * kept at the original 8-frame-equivalent duration, independent of the death
     * sheet's real frame count (7), so removal timing/gameplay pacing doesn't change.
     * Animation's non-looping getKeyFrame() already holds the last real frame for any
     * time beyond the death row's own (shorter) playback length, so the corpse simply
     * holds its final pose for the remainder instead of the timer finishing early.
     */
    public static final float DEATH_DURATION = WALK_FRAME_COUNT * DEATH_FRAME_TIME;

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
        // Pixel art — nearest-neighbor keeps frame edges crisp. This was already the
        // libGDX default for both textures; making it explicit documents intent only,
        // it is not the fix for the death-row bleed (that was incorrect region
        // placement — see DEATH_LEFT_BOUNDARIES / DEATH_RIGHT_BOUNDARIES above).
        walkSheet.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        killSheet.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

        for (int row = 0; row < ROWS; row++) {
            walk[row] = new Animation<>(WALK_FRAME_TIME, sliceRow(walkSheet, row));
        }
        // Rows 2 and 3 of the kill sheet are the death sequences (left / right),
        // sliced at their measured real frame boundaries instead of a uniform grid.
        death[0] = new Animation<>(DEATH_FRAME_TIME, sliceRowAtBoundaries(killSheet, 2, DEATH_LEFT_BOUNDARIES));
        death[1] = new Animation<>(DEATH_FRAME_TIME, sliceRowAtBoundaries(killSheet, 3, DEATH_RIGHT_BOUNDARIES));
    }

    /**
     * Equal-width slicing for the walk sheet's 8 columns per row — unchanged for rows
     * 0, 1 and 3. Row 2 alone is cut short at WALK_LEFT_TRIMMED_HEIGHT instead of the
     * full uniform cell height, to exclude the row-3 leak described above; every other
     * row's y0/y1 compute exactly as before (verified: same formula, same result).
     */
    private static TextureRegion[] sliceRow(Texture sheet, int row) {
        float cellW = sheet.getWidth() / (float) WALK_FRAME_COUNT;
        float cellH = sheet.getHeight() / (float) ROWS;
        int y0 = Math.round(row * cellH);
        int y1 = (row == WALK_LEFT_ROW) ? y0 + WALK_LEFT_TRIMMED_HEIGHT : Math.round((row + 1) * cellH);

        TextureRegion[] frames = new TextureRegion[WALK_FRAME_COUNT];
        for (int col = 0; col < WALK_FRAME_COUNT; col++) {
            int x0 = Math.round(col * cellW);
            int x1 = Math.round((col + 1) * cellW);
            frames[col] = new TextureRegion(sheet, x0, y0, x1 - x0, y1 - y0);
        }
        return frames;
    }

    /**
     * Slices one death row using explicit, asset-measured x boundaries instead of a
     * uniform grid. boundaries must have DEATH_FRAME_COUNT + 1 entries (fence posts
     * for DEATH_FRAME_COUNT regions, strictly increasing, first entry 0, last entry
     * the sheet width). Row height still comes from the same 4-row grid the walk
     * sheet uses — only the horizontal layout of the death rows is irregular.
     */
    private static TextureRegion[] sliceRowAtBoundaries(Texture sheet, int row, int[] boundaries) {
        float cellH = sheet.getHeight() / (float) ROWS;
        int y0 = Math.round(row * cellH);
        int y1 = Math.round((row + 1) * cellH);

        TextureRegion[] frames = new TextureRegion[DEATH_FRAME_COUNT];
        for (int col = 0; col < DEATH_FRAME_COUNT; col++) {
            int x0 = boundaries[col];
            int x1 = boundaries[col + 1];
            frames[col] = new TextureRegion(sheet, x0, y0, x1 - x0, y1 - y0);
        }
        return frames;
    }

    /** direction: 0 = down, 1 = up, 2 = left, 3 = right. */
    public TextureRegion walkFrame(int direction, float stateTime) {
        return walk[direction].getKeyFrame(stateTime, true);
    }

    /**
     * Death only exists facing left or right, so up/down callers pick a side. The
     * backing Animation now holds DEATH_FRAME_COUNT (7) real frames; getKeyFrame with
     * looping=false clamps to the last valid index for any deathTime beyond the death
     * row's own (shorter) playback length, so this never indexes an eighth,
     * nonexistent frame even as deathTime keeps climbing toward DEATH_DURATION.
     */
    public TextureRegion deathFrame(boolean facingRight, float deathTime) {
        return death[facingRight ? 1 : 0].getKeyFrame(deathTime, false);
    }

    public void dispose() {
        walkSheet.dispose();
        killSheet.dispose();
    }
}
