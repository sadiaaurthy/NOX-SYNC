package io.github.fableops.level3;

import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

// Phase 1's mobile defenses orbit close to the Warden. Combat state remains per unit; only each
// unit's render position changes continuously.
public class DefenseDroneController {
    public static final int MIN_UNITS = 3;
    public static final int UNIT_COUNT = 5;
    public static final float DRAW_SIZE = 130f;

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int ROW_IDLE = 0;
    private static final int ROW_ATTACK = 1;
    private static final int ROW_DAMAGED = 2;
    private static final int ROW_CALM = 3;
    private static final float MAX_HEALTH = 30f;
    private static final float FRAME_DURATION = 0.09f;
    private static final float FLASH_DURATION = COLUMNS * FRAME_DURATION;
    private static final float ATTACK_LUNGE = 52f;
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final long FORMATION_SEED = 0x4D4552494449414EL;

    private static final class Unit {
        float x;
        float y;
        float health;
        float orbitAngle;
        float orbitRadiusX;
        float orbitRadiusY;
        float orbitSpeed;
        float bobPhase;
        float bobSpeed;
        boolean active;
        boolean destroying;
        boolean disabled;
        float animTime;
        float attackTimer;
        float damagedTimer;
    }

    private final Texture texture;
    private final Animation<TextureRegion>[] rows;
    private final Unit[] units = new Unit[UNIT_COUNT];
    private final float centreX;
    private final float centreY;
    private boolean calm;
    private boolean formationReady;
    private int formationCount;
    private int encounterNumber;

    @SuppressWarnings("unchecked")
    public DefenseDroneController(float centreX, float centreY) {
        this.centreX = centreX;
        this.centreY = centreY;
        texture = new Texture(Gdx.files.internal("Defense Drone.png"));
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
        for (int i = 0; i < units.length; i++) units[i] = new Unit();
    }

    public boolean isActive() { return getActiveCount() > 0; }

    public int getActiveCount() {
        int count = 0;
        for (Unit unit : units) if (unit.active) count++;
        return count;
    }

    public void spawnAll() {
        prepareFormation(-1);
        setActiveCountInternal(formationCount);
    }

    public void spawn() {
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
                position(unit);
            }
            unit.active = shouldBeActive;
            if (shouldBeActive) unit.destroying = false;
        }
    }

    // Host rolls the count; a client consumes the same roll but honors the synchronized count.
    private void prepareFormation(int synchronizedCount) {
        if (formationReady) return;
        long anchorBits = ((long) Float.floatToIntBits(centreX) << 32)
            ^ Float.floatToIntBits(centreY);
        Random random = new Random(FORMATION_SEED ^ anchorBits
            ^ (0x9E3779B97F4A7C15L * encounterNumber++));
        int rolledCount = MIN_UNITS + random.nextInt(UNIT_COUNT - MIN_UNITS + 1);
        formationCount = synchronizedCount >= 0 ? synchronizedCount : rolledCount;
        float rotation = random.nextFloat() * TAU;
        for (int i = 0; i < units.length; i++) {
            Unit unit = units[i];
            unit.orbitAngle = rotation + TAU * i / Math.max(1, formationCount)
                + (random.nextFloat() - 0.5f) * 0.16f;
            unit.orbitRadiusX = 155f + random.nextFloat() * 55f;
            unit.orbitRadiusY = 48f + random.nextFloat() * 28f;
            float direction = (i & 1) == 0 ? 1f : -1f;
            unit.orbitSpeed = direction * (0.34f + random.nextFloat() * 0.18f);
            unit.bobPhase = random.nextFloat() * TAU;
            unit.bobSpeed = 1.7f + random.nextFloat() * 0.8f;
            position(unit);
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
            if (unit.active) {
                unit.orbitAngle = wrap(unit.orbitAngle + unit.orbitSpeed * delta);
                position(unit);
            }
            unit.animTime += delta;
            unit.attackTimer = Math.max(0f, unit.attackTimer - delta);
            unit.damagedTimer = Math.max(0f, unit.damagedTimer - delta);
            if (unit.destroying && unit.damagedTimer <= 0f) unit.destroying = false;
        }
    }

    private void position(Unit unit) {
        unit.x = centreX + (float) Math.cos(unit.orbitAngle) * unit.orbitRadiusX;
        unit.y = centreY + (float) Math.sin(unit.orbitAngle) * unit.orbitRadiusY
            + (float) Math.sin(unit.animTime * unit.bobSpeed + unit.bobPhase) * 9f;
    }

    private static float wrap(float angle) {
        if (angle > TAU) return angle - TAU;
        if (angle < 0f) return angle + TAU;
        return angle;
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
            float scale = DRAW_SIZE / frame.getRegionHeight();
            float drawW = frame.getRegionWidth() * scale;
            float lunge = unit.attackTimer > 0f
                ? (float) Math.sin((FLASH_DURATION - unit.attackTimer) / FLASH_DURATION * Math.PI) * ATTACK_LUNGE
                : 0f;
            batch.draw(frame, unit.x - drawW / 2f, unit.y - lunge, drawW, DRAW_SIZE);
        }
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
