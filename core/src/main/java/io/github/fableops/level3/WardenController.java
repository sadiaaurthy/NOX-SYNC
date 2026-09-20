package io.github.fableops.level3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

// The Warden itself: sprite, animation and narrative/meter state. "Spider Warden.png" is a clean
// 8x4 grid whose four rows already are the four looks STORY.md asks for - row 0 idle/red (defense
// active), row 1 attack/red beam, row 2 damaged/cracked, row 3 restored/blue (stand down) - so no
// directional logic is needed, just "which row for the current moment".
//
// Holds the narrative state and the three encounter meters, but never decides how they change -
// Level3Controller
// (host-authoritative) does that and calls the setters here; the client sets the same fields
// straight from Level3TurnStateMessage. Either way this class only turns state into a picture
public class WardenController {

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int ROW_IDLE = 0;
    private static final int ROW_ATTACK = 1;
    private static final int ROW_DAMAGED = 2;
    private static final int ROW_RESTORED = 3;
    private static final float FRAME_DURATION = 0.09f;
    private static final float FLASH_DURATION = COLUMNS * FRAME_DURATION;

    // Players render at 100 world units tall. This keeps the Warden imposing without making it
    // tower over the encounter at more than four player heights.
    public static final float DRAW_SIZE = 185f;

    private final Texture texture;
    private final Animation<TextureRegion>[] rows;
    private final float anchorX, anchorY;

    private WardenState state = WardenState.DEFENSE_ACTIVE;
    private float stability = 0f;   // 0..100
    private float directiveConflict = 100f; // 100 = directives fully opposed; trends down
    private float dualMeter = 0f;   // 0..100, only meaningful during DIRECTIVE_CONFLICT

    private float animTime = 0f;
    private float attackFlashTimer = 0f;
    private float damagedFlashTimer = 0f;

    @SuppressWarnings("unchecked")
    public WardenController(float anchorX, float anchorY) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        texture = new Texture(Gdx.files.internal("Spider Warden.png"));
        rows = new Animation[ROWS];
        for (int row = 0; row < ROWS; row++) {
            TextureRegion[] frames = new TextureRegion[COLUMNS];
            for (int col = 0; col < COLUMNS; col++) {
                int x0 = col * texture.getWidth() / COLUMNS;
                int x1 = (col + 1) * texture.getWidth() / COLUMNS;
                int y0 = row * texture.getHeight() / ROWS;
                int y1 = (row + 1) * texture.getHeight() / ROWS;
                frames[col] = new TextureRegion(texture, x0, y0, x1 - x0, y1 - y0);
            }
            rows[row] = new Animation<>(FRAME_DURATION, frames);
        }
    }

    public WardenState getState() { return state; }

    public void setState(WardenState state) {
        if (this.state != state) {
            // Narrative transitions own the frame completely; a hit that crosses a threshold must
            // not make Memory Recovery, Directive Conflict, or Stand Down look like damage beats.
            attackFlashTimer = 0f;
            damagedFlashTimer = 0f;
            // The restored row must begin at frame zero exactly once.
            if (state.isStoodDown()) animTime = 0f;
        }
        this.state = state;
    }

    public float getStability() { return stability; }

    public void setStability(float stability) { this.stability = Math.max(0f, Math.min(100f, stability)); }

    public float getDirectiveConflict() { return directiveConflict; }

    public void setDirectiveConflict(float directiveConflict) {
        this.directiveConflict = Math.max(0f, Math.min(100f, directiveConflict));
    }

    public float getDualMeter() { return dualMeter; }

    public void setDualMeter(float dualMeter) { this.dualMeter = Math.max(0f, Math.min(100f, dualMeter)); }

    // Called once when the Warden's own turn resolves
    public void playAttackFlash() { attackFlashTimer = FLASH_DURATION; }

    // Called once a direct player strike lands on the Warden itself.
    public void playDamagedFlash() { damagedFlashTimer = FLASH_DURATION; }

    public void update(float delta) {
        animTime += delta;
        attackFlashTimer = Math.max(0f, attackFlashTimer - delta);
        damagedFlashTimer = Math.max(0f, damagedFlashTimer - delta);
    }

    public void draw(SpriteBatch batch) {
        int row;
        float time;
        boolean loop;
        if (damagedFlashTimer > 0f) {
            row = ROW_DAMAGED;
            time = FLASH_DURATION - damagedFlashTimer;
            loop = false;
        } else if (attackFlashTimer > 0f) {
            row = ROW_ATTACK;
            time = FLASH_DURATION - attackFlashTimer;
            loop = false;
        } else if (state.isStoodDown()) {
            row = ROW_RESTORED;
            time = animTime;
            loop = false;
        } else {
            row = ROW_IDLE;
            time = animTime;
            loop = true;
        }

        TextureRegion frame = rows[row].getKeyFrame(time, loop);
        float scale = DRAW_SIZE / frame.getRegionHeight();
        float drawW = frame.getRegionWidth() * scale;
        float drawH = frame.getRegionHeight() * scale;
        batch.draw(frame, anchorX - drawW / 2f, anchorY, drawW, drawH);
    }

    public float centreX() { return anchorX; }

    public float centreY() { return anchorY + DRAW_SIZE / 2f; }

    public void reset() {
        state = WardenState.DEFENSE_ACTIVE;
        stability = 0f;
        directiveConflict = 100f;
        dualMeter = 0f;
        animTime = 0f;
        attackFlashTimer = 0f;
        damagedFlashTimer = 0f;
    }

    public void dispose() {
        texture.dispose();
    }
}
