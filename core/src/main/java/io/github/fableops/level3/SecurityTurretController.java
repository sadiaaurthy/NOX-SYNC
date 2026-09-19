package io.github.fableops.level3;

import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

// Stationary defenses select deterministic, collision-validated ground points. A turret's visible
// base always draws on its assigned groundY; attack and damage animations never displace it.
public class SecurityTurretController {
    public static final int MIN_UNITS = 2;
    public static final int UNIT_COUNT = 4;
    public static final float DRAW_SIZE = 135f;

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int ROW_IDLE = 0;
    private static final int ROW_ATTACK = 1;
    private static final int ROW_DAMAGED = 2;
    private static final int ROW_CALM = 3;
    private static final float MAX_HEALTH = 30f;
    private static final float FRAME_DURATION = 0.09f;
    private static final float FLASH_DURATION = COLUMNS * FRAME_DURATION;
    private static final long FORMATION_SEED = 0x545552524554534CL;
    private static final int ALPHA_THRESHOLD = 20;

    private static final class Unit {
        float x;
        float groundY;
        float health;
        boolean active;
        boolean destroying;
        boolean disabled;
        float animTime;
        float attackTimer;
        float damagedTimer;
    }

    private final Texture texture;
    private final Animation<TextureRegion>[] rows;
    private final float[][] opaqueBottomOffsets = new float[ROWS][COLUMNS];
    private final Unit[] units = new Unit[UNIT_COUNT];
    private final float[][] groundPoints;
    private boolean calm;
    private boolean formationReady;
    private int formationCount;
    private int encounterNumber;

    @SuppressWarnings("unchecked")
    public SecurityTurretController(float[][] groundPoints) {
        if (groundPoints == null || groundPoints.length < UNIT_COUNT) {
            throw new IllegalArgumentException("Security Turrets require at least " + UNIT_COUNT
                + " ground points.");
        }
        this.groundPoints = new float[groundPoints.length][2];
        for (int i = 0; i < groundPoints.length; i++) {
            this.groundPoints[i][0] = groundPoints[i][0];
            this.groundPoints[i][1] = groundPoints[i][1];
        }

        Pixmap pixmap = new Pixmap(Gdx.files.internal("Security Turret.png"));
        texture = new Texture(pixmap);
        rows = new Animation[ROWS];
        for (int row = 0; row < ROWS; row++) {
            TextureRegion[] frames = new TextureRegion[COLUMNS];
            for (int col = 0; col < COLUMNS; col++) {
                int x0 = col * texture.getWidth() / COLUMNS;
                int x1 = (col + 1) * texture.getWidth() / COLUMNS;
                int y0 = row * texture.getHeight() / ROWS;
                int y1 = (row + 1) * texture.getHeight() / ROWS;
                frames[col] = new TextureRegion(texture, x0, y0, x1 - x0, y1 - y0);
                opaqueBottomOffsets[row][col] = measureOpaqueBottomOffset(
                    pixmap, x0, y0, x1 - x0, y1 - y0);
            }
            rows[row] = new Animation<>(FRAME_DURATION, frames);
        }
        pixmap.dispose();
        for (int i = 0; i < units.length; i++) units[i] = new Unit();
    }

    private static float measureOpaqueBottomOffset(Pixmap pixmap, int x0, int y0, int width,
                                                    int height) {
        int opaqueBottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixmap.getPixel(x0 + x, y0 + y) & 0xFF) > ALPHA_THRESHOLD) {
                    opaqueBottom = y;
                }
            }
        }
        if (opaqueBottom < 0) return 0f;
        return (height - 1 - opaqueBottom) * (DRAW_SIZE / height);
    }

    public boolean isActive() { return getActiveCount() > 0; }

    public int getActiveCount() {
        int count = 0;
        for (Unit unit : units) if (unit.active) count++;
        return count;
    }

    public void activateAll() {
        prepareFormation(-1);
        setActiveCountInternal(formationCount);
    }

    public void activate() {
        int count = getActiveCount();
        if (count < UNIT_COUNT) setActiveCount(count + 1);
    }

    public void setActiveCount(int requested) {
        int count = Math.max(0, Math.min(UNIT_COUNT, requested));
        if (count > 0 && !formationReady) prepareFormation(count);
        setActiveCountInternal(count);
    }

    private void setActiveCountInternal(int count) {
        for (int i = 0; i < units.length; i++) {
            Unit unit = units[i];
            boolean shouldBeActive = i < count;
            if (shouldBeActive && !unit.active) {
                unit.health = MAX_HEALTH;
                unit.animTime = 0f;
                unit.disabled = false;
            }
            unit.active = shouldBeActive;
            if (shouldBeActive) unit.destroying = false;
        }
    }

    private void prepareFormation(int synchronizedCount) {
        if (formationReady) return;
        long anchorBits = 0L;
        for (float[] point : groundPoints) {
            anchorBits = Long.rotateLeft(anchorBits, 11) ^ Float.floatToIntBits(point[0]);
            anchorBits = Long.rotateLeft(anchorBits, 11) ^ Float.floatToIntBits(point[1]);
        }
        Random random = new Random(FORMATION_SEED ^ anchorBits
            ^ (0x9E3779B97F4A7C15L * encounterNumber++));
        int rolledCount = MIN_UNITS + random.nextInt(UNIT_COUNT - MIN_UNITS + 1);
        formationCount = synchronizedCount >= 0 ? synchronizedCount : rolledCount;

        int[] anchors = new int[groundPoints.length];
        for (int i = 0; i < anchors.length; i++) anchors[i] = i;
        for (int i = anchors.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = anchors[i];
            anchors[i] = anchors[j];
            anchors[j] = swap;
        }
        for (int i = 0; i < units.length; i++) {
            float[] point = groundPoints[anchors[i]];
            units[i].x = point[0];
            units[i].groundY = point[1];
        }
        formationReady = true;
    }

    public int damage(float amount) {
        int index = lastActiveIndex();
        if (index < 0 || amount <= 0f) return -1;
        Unit unit = units[index];
        unit.health = Math.max(0f, unit.health - amount);
        unit.damagedTimer = FLASH_DURATION;
        if (unit.health <= 0f) disable(unit);
        return index;
    }

    public void playDamaged(int index, boolean destroyed) {
        if (index < 0 || index >= units.length) return;
        Unit unit = units[index];
        unit.damagedTimer = FLASH_DURATION;
        if (destroyed) disable(unit);
    }

    private static void disable(Unit unit) {
        unit.active = false;
        unit.destroying = true;
        unit.disabled = true;
        unit.attackTimer = 0f;
    }

    public void playAttackFlash() {
        int index = firstActiveIndex();
        if (index >= 0) units[index].attackTimer = FLASH_DURATION;
    }

    public void setCalm(boolean calm) { this.calm = calm; }

    public void update(float delta) {
        for (Unit unit : units) {
            if (!unit.active && !unit.destroying && !unit.disabled) continue;
            unit.animTime += delta;
            unit.attackTimer = Math.max(0f, unit.attackTimer - delta);
            unit.damagedTimer = Math.max(0f, unit.damagedTimer - delta);
            if (unit.destroying && unit.damagedTimer <= 0f) unit.destroying = false;
        }
    }

    public void draw(SpriteBatch batch) {
        for (Unit unit : units) {
            if (!unit.active && !unit.destroying && !unit.disabled) continue;
            int row;
            float time;
            boolean loop;
            if (unit.damagedTimer > 0f || unit.disabled) {
                row = ROW_DAMAGED;
                time = unit.disabled && unit.damagedTimer <= 0f
                    ? FLASH_DURATION : FLASH_DURATION - unit.damagedTimer;
                loop = false;
            } else if (unit.attackTimer > 0f) {
                row = ROW_ATTACK;
                time = FLASH_DURATION - unit.attackTimer;
                loop = false;
            } else {
                row = calm ? ROW_CALM : ROW_IDLE;
                time = unit.animTime;
                loop = true;
            }
            TextureRegion frame = rows[row].getKeyFrame(time, loop);
            int frameIndex = frameIndex(rows[row].getKeyFrames(), frame);
            float scale = DRAW_SIZE / frame.getRegionHeight();
            float drawW = frame.getRegionWidth() * scale;
            float drawY = unit.groundY - opaqueBottomOffsets[row][frameIndex];
            batch.draw(frame, unit.x - drawW / 2f, drawY, drawW, DRAW_SIZE);
        }
    }

    private static int frameIndex(TextureRegion[] frames, TextureRegion frame) {
        for (int i = 0; i < frames.length; i++) if (frames[i] == frame) return i;
        return 0;
    }

    private int firstActiveIndex() {
        for (int i = 0; i < units.length; i++) if (units[i].active) return i;
        return -1;
    }

    private int lastActiveIndex() {
        for (int i = units.length - 1; i >= 0; i--) if (units[i].active) return i;
        return -1;
    }

    public void reset() {
        calm = false;
        formationReady = false;
        formationCount = 0;
        for (Unit unit : units) {
            unit.health = 0f;
            unit.active = false;
            unit.destroying = false;
            unit.disabled = false;
            unit.animTime = 0f;
            unit.attackTimer = 0f;
            unit.damagedTimer = 0f;
        }
    }

    public void dispose() { texture.dispose(); }
}
