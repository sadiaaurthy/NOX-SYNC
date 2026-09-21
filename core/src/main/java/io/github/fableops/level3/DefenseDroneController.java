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
    // A drone only attacks an operator this close (measured from the middle of the sprite)
    public static final float ATTACK_RANGE = 260f;

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int ROW_IDLE = 0;
    private static final int ROW_ATTACK = 1;
    private static final int ROW_DAMAGED = 2;
    private static final int ROW_RESTORED = 3;
    private static final float MAX_HEALTH = 30f;
    private static final float FRAME_DURATION = 0.09f;
    private static final float FLASH_DURATION = COLUMNS * FRAME_DURATION;
    private static final float RESTORATION_DURATION = COLUMNS * FRAME_DURATION;
    public static final float ATTACK_DURATION = COLUMNS * FRAME_DURATION;
    public static final float ATTACK_IMPACT_TIME = 6f * FRAME_DURATION;
    private static final float ATTACK_LUNGE = 52f;
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final long FORMATION_SEED = 0x4D4552494449414EL;

    private enum UnitState { INACTIVE, ACTIVE, DESTROYING, DESTROYED, RESTORING, RESTORED }
    public enum AnimationState {
        IDLE, MOVING, ATTACKING, DAMAGED, DESTROYING, DESTROYED, RESTORING, RESTORED
    }

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
        UnitState state = UnitState.INACTIVE;
        AnimationState animationState = AnimationState.IDLE;
        float animTime;
        float attackTimer;
        float damagedTimer;
        float restorationTimer;
        // The attack row's beam points right, so a target on the left mirrors the sprite
        boolean faceLeft;
    }

    private final Texture texture;
    private final Animation<TextureRegion>[] rows;
    private final Unit[] units = new Unit[UNIT_COUNT];
    private final float centreX;
    private final float centreY;
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
        for (Unit unit : units) if (unit.state == UnitState.ACTIVE) count++;
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
        int activeCount = getActiveCount();
        for (int i = units.length - 1; i >= 0 && activeCount > count; i--) {
            Unit unit = units[i];
            if (unit.state != UnitState.ACTIVE) continue;
            unit.state = UnitState.INACTIVE;
            unit.animationState = AnimationState.IDLE;
            activeCount--;
        }
        for (Unit unit : units) {
            if (activeCount >= count) break;
            if (unit.state != UnitState.INACTIVE) continue;
            unit.health = MAX_HEALTH;
            unit.animTime = 0f;
            unit.attackTimer = 0f;
            unit.damagedTimer = 0f;
            unit.restorationTimer = 0f;
            unit.animationState = AnimationState.MOVING;
            unit.state = UnitState.ACTIVE;
            position(unit);
            activeCount++;
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
        return damage(lastActiveIndex(), amount);
    }

    // Reaction shots must hit the same unit that telegraphed the incoming attack. Normal turn
    // damage keeps using damage(amount), so its existing target order is unchanged.
    public int damage(int index, float amount) {
        if (index < 0 || amount <= 0f) return -1;
        Unit unit = units[index];
        if (unit.state != UnitState.ACTIVE) return -1;
        unit.health = Math.max(0f, unit.health - amount);
        unit.damagedTimer = FLASH_DURATION;
        if (unit.health <= 0f) beginDestruction(unit);
        else unit.animationState = AnimationState.DAMAGED;
        return index;
    }

    public void playDamaged(int index, boolean destroyed) {
        if (index < 0 || index >= units.length) return;
        Unit unit = units[index];
        if (!destroyed && unit.state != UnitState.ACTIVE) return;
        unit.damagedTimer = FLASH_DURATION;
        if (destroyed) beginDestruction(unit);
        else unit.animationState = AnimationState.DAMAGED;
    }

    private static void beginDestruction(Unit unit) {
        unit.state = UnitState.DESTROYING;
        unit.animationState = AnimationState.DESTROYING;
        unit.attackTimer = 0f;
    }

    public int playAttackFlash() {
        return playAttackFlash(firstActiveIndex());
    }

    public int playAttackFlash(int index) {
        if (index < 0 || index >= units.length || units[index].state != UnitState.ACTIVE) return -1;
        Unit unit = units[index];
        unit.animationState = AnimationState.ATTACKING;
        unit.attackTimer = ATTACK_DURATION;
        return index;
    }

    // Same attack, fired toward targetX so the beam leaves the side the target is on
    public int playAttackFlash(int index, float targetX) {
        if (index >= 0 && index < units.length) units[index].faceLeft = targetX < units[index].x;
        return playAttackFlash(index);
    }

    public boolean isUnitAttacking(int index) {
        return index >= 0 && index < units.length
            && units[index].animationState == AnimationState.ATTACKING;
    }

    // Squared distance from the middle of an active unit, infinite for one that is not flying
    public float distanceSquaredToUnit(int index, float targetX, float targetY) {
        if (!isUnitActive(index)) return Float.POSITIVE_INFINITY;
        float dx = targetX - units[index].x;
        float dy = targetY - (units[index].y + DRAW_SIZE * 0.5f);
        return dx * dx + dy * dy;
    }

    public boolean isUnitActive(int index) {
        return index >= 0 && index < units.length && units[index].state == UnitState.ACTIVE;
    }

    public int lastActiveUnit() { return lastActiveIndex(); }

    public float unitX(int index) {
        return index >= 0 && index < units.length ? units[index].x : centreX;
    }

    public float unitY(int index) {
        return index >= 0 && index < units.length ? units[index].y + DRAW_SIZE * 0.5f : centreY;
    }

    public void setCalm(boolean calm) {
        if (calm) startRestoration();
    }

    public void startRestoration() {
        for (Unit unit : units) {
            if (unit.state != UnitState.DESTROYING && unit.state != UnitState.DESTROYED) continue;
            unit.state = UnitState.RESTORING;
            unit.animationState = AnimationState.RESTORING;
            unit.animTime = 0f;
            unit.restorationTimer = RESTORATION_DURATION;
            unit.damagedTimer = 0f;
        }
    }

    public void completeRestoration() {
        for (int i = 0; i < units.length; i++) {
            Unit unit = units[i];
            if (unit.state != UnitState.RESTORING) continue;
            unit.state = UnitState.RESTORED;
            unit.animationState = AnimationState.RESTORED;
            unit.animTime = 0f;
            unit.restorationTimer = 0f;
            Gdx.app.log("Level3RestorationTrace", "Drone " + i + " restored blue state");
        }
    }

    public boolean isRestorationComplete() {
        for (Unit unit : units) if (unit.state == UnitState.RESTORING) return false;
        return true;
    }

    public void update(float delta) {
        for (Unit unit : units) {
            if (unit.state == UnitState.INACTIVE || unit.state == UnitState.DESTROYED) continue;
            if (unit.state == UnitState.ACTIVE
                && unit.animationState == AnimationState.MOVING) {
                unit.orbitAngle = wrap(unit.orbitAngle + unit.orbitSpeed * delta);
                position(unit);
            }
            unit.animTime += delta;
            unit.attackTimer = Math.max(0f, unit.attackTimer - delta);
            unit.damagedTimer = Math.max(0f, unit.damagedTimer - delta);
            unit.restorationTimer = Math.max(0f, unit.restorationTimer - delta);
            // A living drone goes back to orbiting the Warden once an attack or a hit has played out
            if (unit.animationState == AnimationState.ATTACKING && unit.attackTimer <= 0f) {
                unit.animationState = AnimationState.MOVING;
            }
            if (unit.animationState == AnimationState.DAMAGED && unit.damagedTimer <= 0f) {
                unit.animationState = AnimationState.MOVING;
            } else if (unit.animationState == AnimationState.DESTROYING
                && unit.damagedTimer <= 0f) {
                unit.state = UnitState.DESTROYED;
                unit.animationState = AnimationState.DESTROYED;
            }
            if (unit.animationState == AnimationState.RESTORING
                && unit.restorationTimer <= 0f) {
                int index = indexOf(unit);
                unit.state = UnitState.RESTORED;
                unit.animationState = AnimationState.RESTORED;
                unit.animTime = 0f;
                Gdx.app.log("Level3RestorationTrace", "Drone " + index + " restored blue state");
            }
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
            if (unit.state == UnitState.INACTIVE || unit.state == UnitState.DESTROYED) continue;
            int row;
            float time;
            boolean loop;
            if (unit.animationState == AnimationState.DAMAGED
                || unit.animationState == AnimationState.DESTROYING) {
                row = ROW_DAMAGED;
                time = FLASH_DURATION - unit.damagedTimer;
                loop = false;
            } else if (unit.animationState == AnimationState.RESTORING
                || unit.animationState == AnimationState.RESTORED) {
                row = ROW_RESTORED;
                time = unit.animationState == AnimationState.RESTORING
                    ? RESTORATION_DURATION - unit.restorationTimer : unit.animTime;
                loop = unit.animationState == AnimationState.RESTORED;
            } else if (unit.animationState == AnimationState.ATTACKING) {
                row = ROW_ATTACK;
                time = ATTACK_DURATION - unit.attackTimer;
                loop = false;
            } else {
                row = ROW_IDLE;
                time = unit.animTime;
                loop = true;
            }
            TextureRegion frame = rows[row].getKeyFrame(time, loop);
            float scale = DRAW_SIZE / frame.getRegionHeight();
            float drawW = frame.getRegionWidth() * scale;
            float lunge = unit.animationState == AnimationState.ATTACKING
                ? (float) Math.sin((ATTACK_DURATION - unit.attackTimer) / ATTACK_DURATION * Math.PI) * ATTACK_LUNGE
                : 0f;
            if (unit.faceLeft && unit.animationState == AnimationState.ATTACKING) {
                batch.draw(frame, unit.x + drawW / 2f, unit.y - lunge, -drawW, DRAW_SIZE);
            } else {
                batch.draw(frame, unit.x - drawW / 2f, unit.y - lunge, drawW, DRAW_SIZE);
            }
        }
    }

    private int firstActiveIndex() {
        for (int i = 0; i < units.length; i++) {
            if (units[i].state == UnitState.ACTIVE) return i;
        }
        return -1;
    }

    private int lastActiveIndex() {
        for (int i = units.length - 1; i >= 0; i--) {
            if (units[i].state == UnitState.ACTIVE) return i;
        }
        return -1;
    }

    private int indexOf(Unit target) {
        for (int i = 0; i < units.length; i++) if (units[i] == target) return i;
        return -1;
    }

    public void reset() {
        formationReady = false;
        formationCount = 0;
        for (Unit unit : units) {
            unit.health = 0f;
            unit.state = UnitState.INACTIVE;
            unit.animationState = AnimationState.IDLE;
            unit.animTime = 0f;
            unit.attackTimer = 0f;
            unit.damagedTimer = 0f;
            unit.restorationTimer = 0f;
        }
    }

    public void dispose() { texture.dispose(); }
}
