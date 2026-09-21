package io.github.fableops.level3;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

// Plays "TNT explosion.png" once at each blast and then forgets it. The sheet is 8 columns by 4 rows:
// two rows of fuse, then the burning charge, then the detonation and its smoke. Only the last two
// rows play, so the fuse stays lit for a beat and the blast follows. Purely visual: the damage is
// decided by Level3Controller, so host and client draw the same thing from the same trigger.
public class TntExplosion {

    // Deliberately smaller than a Security Turret (SecurityTurretController.DRAW_SIZE), so a blast
    // reads as one charge on one turret and never floods the arena
    public static final float DRAW_SIZE = SecurityTurretController.DRAW_SIZE * 0.8f;

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int FIRST_ROW = 2;
    private static final float FRAME_DURATION = 0.055f;

    private static final class Blast {
        final float x;
        final float y;
        float time;

        Blast(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private final Texture texture;
    private final TextureRegion[] frames = new TextureRegion[(ROWS - FIRST_ROW) * COLUMNS];
    private final List<Blast> blasts = new ArrayList<>();

    public TntExplosion() {
        texture = new Texture(Gdx.files.internal("TNT explosion.png"));
        int index = 0;
        for (int row = FIRST_ROW; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int x0 = col * texture.getWidth() / COLUMNS;
                int x1 = (col + 1) * texture.getWidth() / COLUMNS;
                int y0 = row * texture.getHeight() / ROWS;
                int y1 = (row + 1) * texture.getHeight() / ROWS;
                frames[index++] = new TextureRegion(texture, x0, y0, x1 - x0, y1 - y0);
            }
        }
    }

    // (x, y) is the middle of the blast in world coordinates.
    public void spawn(float x, float y) {
        blasts.add(new Blast(x, y));
        Gdx.app.log("Level3TntTrace", "explosion started at " + Math.round(x) + "," + Math.round(y)
            + " size=" + Math.round(DRAW_SIZE));
    }

    public boolean isPlaying() { return !blasts.isEmpty(); }

    public void update(float delta) {
        for (int i = blasts.size() - 1; i >= 0; i--) {
            Blast blast = blasts.get(i);
            blast.time += delta;
            if (blast.time >= frames.length * FRAME_DURATION) blasts.remove(i);
        }
    }

    public void draw(SpriteBatch batch) {
        for (Blast blast : blasts) {
            int frame = Math.min(frames.length - 1, (int) (blast.time / FRAME_DURATION));
            batch.draw(frames[frame], blast.x - DRAW_SIZE / 2f, blast.y - DRAW_SIZE / 2f,
                DRAW_SIZE, DRAW_SIZE);
        }
    }

    public void dispose() { texture.dispose(); }
}
