package io.github.fableops.level3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

// One mobile Defense Drone unit (STORY.md: "Defense Drone: use as mobile defense units"). A single
// slot is enough for a turn-based encounter - the Warden either has one deployed or it doesn't.
// "Defense Drone.png" is the same 9x4 grid shape as the Warden's own sheet: row 0 idle, row 1
// attack, row 2 damaged, row 3 calm/blue (shown once the Warden has stood down)
public class DefenseDroneController {

    private static final int COLUMNS = 9;
    private static final int ROWS = 4;
    private static final int ROW_IDLE = 0;
    private static final int ROW_ATTACK = 1;
    private static final int ROW_DAMAGED = 2;
    private static final int ROW_CALM = 3;
    private static final float FRAME_DURATION = 0.09f;
    private static final float FLASH_DURATION = COLUMNS * FRAME_DURATION;

    public static final float DRAW_SIZE = 190f;

    private final Texture texture;
    private final Animation<TextureRegion>[] rows;
    private final float x, y;

    private boolean active = false;
    private boolean destroying = false;
    private boolean calm = false;
    private float animTime = 0f;
    private float attackFlashTimer = 0f;
    private float damagedFlashTimer = 0f;

    @SuppressWarnings("unchecked")
    public DefenseDroneController(float x, float y) {
        this.x = x;
        this.y = y;
        texture = new Texture(Gdx.files.internal("Defense Drone.png"));
        int frameW = texture.getWidth() / COLUMNS;
        int frameH = texture.getHeight() / ROWS;
        rows = new Animation[ROWS];
        for (int row = 0; row < ROWS; row++) {
            TextureRegion[] frames = new TextureRegion[COLUMNS];
            for (int col = 0; col < COLUMNS; col++) {
                frames[col] = new TextureRegion(texture, col * frameW, row * frameH, frameW, frameH);
            }
            rows[row] = new Animation<>(FRAME_DURATION, frames);
        }
    }

    public boolean isActive() { return active || destroying; }

    public void spawn() {
        active = true;
        destroying = false;
        animTime = 0f;
    }

    // Visual only - the drone just deployed and struck a player this round
    public void playAttackFlash() { attackFlashTimer = FLASH_DURATION; }

    // A Breaker action took it out: flashes damaged, then disappears
    public void destroy() {
        if (!active) return;
        destroying = true;
        damagedFlashTimer = FLASH_DURATION;
    }

    public void setCalm(boolean calm) { this.calm = calm; }

    public void update(float delta) {
        if (!active && !destroying) return;
        animTime += delta;
        attackFlashTimer = Math.max(0f, attackFlashTimer - delta);
        if (destroying) {
            damagedFlashTimer -= delta;
            if (damagedFlashTimer <= 0f) {
                destroying = false;
                active = false;
            }
        }
    }

    public void draw(SpriteBatch batch) {
        if (!active && !destroying) return;
        int row;
        float time;
        boolean loop;
        if (destroying) {
            row = ROW_DAMAGED;
            time = FLASH_DURATION - damagedFlashTimer;
            loop = false;
        } else if (attackFlashTimer > 0f) {
            row = ROW_ATTACK;
            time = FLASH_DURATION - attackFlashTimer;
            loop = false;
        } else {
            row = calm ? ROW_CALM : ROW_IDLE;
            time = animTime;
            loop = true;
        }

        TextureRegion frame = rows[row].getKeyFrame(time, loop);
        float scale = DRAW_SIZE / frame.getRegionHeight();
        float drawW = frame.getRegionWidth() * scale;
        float drawH = frame.getRegionHeight() * scale;
        batch.draw(frame, x - drawW / 2f, y, drawW, drawH);
    }

    public void reset() {
        active = false;
        destroying = false;
        calm = false;
    }

    public void dispose() {
        texture.dispose();
    }
}
