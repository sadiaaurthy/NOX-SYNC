package io.github.fableops.level3;

import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

// Stationary defenses select deterministic, collision-validated ground points. A turret's visible
// base always draws on its assigned groundY; attack and damage animations never displace it.
public class SecurityTurretController {
    public static final int MIN_UNITS = 2;
    public static final int UNIT_COUNT = 4;
    public static final float DRAW_SIZE = 135f;
    public static final float DETECTION_RADIUS = 360f;

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int ROW_IDLE = 0;
    private static final int ROW_ATTACK = 1;
    private static final int ROW_DAMAGED = 2;
    private static final int ROW_RESTORED = 3;
    private static final float MAX_HEALTH = 30f;
    private static final float FRAME_DURATION = 0.09f;
    public static final float AIM_DURATION = 0.45f;
    public static final float FIRING_DURATION = COLUMNS * FRAME_DURATION;
    public static final float FIRE_IMPACT_TIME = 6f * FRAME_DURATION;
    private static final float FLASH_DURATION = COLUMNS * FRAME_DURATION;
    private static final float RESTORATION_DURATION = COLUMNS * FRAME_DURATION;
    private static final long FORMATION_SEED = 0x545552524554534CL;
    private static final int ALPHA_THRESHOLD = 20;

    public enum AnimationState {
        IDLE_ROTATING, AIMING, FIRING, DAMAGED, DESTROYING, DESTROYED, RESTORING, RESTORED
    }

    private static final class Unit {
        float x;
        float groundY;
        float health;
        boolean active;
        boolean destroying;
        boolean disabled;
        AnimationState animationState = AnimationState.IDLE_ROTATING;
        float animTime;
        float aimTimer;
        float firingTimer;
        float damagedTimer;
        float restorationTimer;
        float targetX = Float.NaN;
        float targetY = Float.NaN;
        boolean firingRenderLogged;
    }

    private final Texture texture;
    private final Animation<TextureRegion>[] rows;
    private final float[][] opaqueBottomOffsets = new float[ROWS][COLUMNS];
    private final Unit[] units = new Unit[UNIT_COUNT];
    private final float[][] groundPoints;
    private boolean formationReady;
    private int formationCount;
    private int encounterNumber;
    private boolean firingStarted;

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
        int activeCount = getActiveCount();
        for (int i = units.length - 1; i >= 0 && activeCount > count; i--) {
            Unit unit = units[i];
            if (!unit.active) continue;
            unit.active = false;
            activeCount--;
        }
        for (Unit unit : units) {
            if (activeCount >= count) break;
            // A destroyed turret has completed its lifecycle and cannot be reactivated. Dormant,
            // never-deployed units remain available for later Warden activation actions.
            if (unit.active || unit.destroying || unit.disabled) continue;
            unit.health = MAX_HEALTH;
            unit.animTime = 0f;
            unit.animationState = AnimationState.IDLE_ROTATING;
            unit.aimTimer = 0f;
            unit.firingTimer = 0f;
            unit.damagedTimer = 0f;
            unit.targetX = Float.NaN;
            unit.targetY = Float.NaN;
            unit.active = true;
            activeCount++;
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
        return damage(lastActiveIndex(), amount);
    }

    // Reaction fire targets the turret that detected the player. The original damage(amount)
    // path remains unchanged for normal turn actions.
    public int damage(int index, float amount) {
        if (index < 0 || amount <= 0f) return -1;
        Unit unit = units[index];
        if (!unit.active) return -1;
        unit.health = Math.max(0f, unit.health - amount);
        unit.damagedTimer = FLASH_DURATION;
        if (unit.health <= 0f) disable(unit);
        else {
            unit.animationState = AnimationState.DAMAGED;
            unit.aimTimer = 0f;
            unit.firingTimer = 0f;
        }
        return index;
    }

    public void playDamaged(int index, boolean destroyed) {
        if (index < 0 || index >= units.length) return;
        Unit unit = units[index];
        unit.damagedTimer = FLASH_DURATION;
        if (destroyed) disable(unit);
        else {
            unit.animationState = AnimationState.DAMAGED;
            unit.aimTimer = 0f;
            unit.firingTimer = 0f;
        }
    }

    private static void disable(Unit unit) {
        unit.active = false;
        unit.destroying = true;
        unit.disabled = true;
        unit.animationState = AnimationState.DESTROYING;
        unit.aimTimer = 0f;
        unit.firingTimer = 0f;
    }

    public int playAiming(float targetX, float targetY) {
        return playAiming(firstActiveIndex(), targetX, targetY);
    }

    public int playAiming(int index, float targetX, float targetY) {
        if (index < 0 || index >= units.length || !units[index].active) return -1;
        Unit unit = units[index];
        Gdx.app.log("SecurityTurretTrace", "playAiming unit=" + index
            + " target=(" + targetX + ", " + targetY + ")");
        unit.targetX = targetX;
        unit.targetY = targetY;
        unit.animationState = AnimationState.AIMING;
        unit.aimTimer = AIM_DURATION;
        unit.firingTimer = 0f;
        return index;
    }

    public void playFiring(float targetX, float targetY) {
        int index = firstAimingIndex();
        if (index < 0) index = firstActiveIndex();
        if (index < 0) return;
        Unit unit = units[index];
        unit.targetX = targetX;
        unit.targetY = targetY;
        if (unit.animationState != AnimationState.FIRING) beginFiring(unit);
    }

    // Retained for the existing Level 3 event name and older state snapshots.
    public void playAttackFlash() {
        playFiring(Float.NaN, Float.NaN);
    }

    private void beginFiring(Unit unit) {
        Gdx.app.log("SecurityTurretTrace", "firing started target=(" + unit.targetX + ", "
            + unit.targetY + ") duration=" + FIRING_DURATION);
        unit.animationState = AnimationState.FIRING;
        unit.aimTimer = 0f;
        unit.firingTimer = FIRING_DURATION;
        unit.firingRenderLogged = false;
        firingStarted = true;
    }

    public boolean consumeFiringStarted() {
        if (!firingStarted) return false;
        firingStarted = false;
        return true;
    }

    // Units are checked from the end to preserve the existing compact count synchronization: a
    // reaction kill then removes the same unit on host and client without a per-frame unit stream.
    public int detectingUnit(float targetX, float targetY) {
        float limit = DETECTION_RADIUS * DETECTION_RADIUS;
        for (int i = units.length - 1; i >= 0; i--) {
            Unit unit = units[i];
            if (!unit.active) continue;
            float dx = targetX - unit.x;
            float dy = targetY - (unit.groundY + DRAW_SIZE * 0.5f);
            float distance = dx * dx + dy * dy;
            if (distance <= limit) return i;
        }
        return -1;
    }

    public float distanceSquaredToUnit(int index, float targetX, float targetY) {
        if (index < 0 || index >= units.length || !units[index].active) return Float.POSITIVE_INFINITY;
        Unit unit = units[index];
        float dx = targetX - unit.x;
        float dy = targetY - (unit.groundY + DRAW_SIZE * 0.5f);
        return dx * dx + dy * dy;
    }

    public float nearestActiveDistance(float targetX, float targetY) {
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < units.length; i++) {
            float distanceSquared = distanceSquaredToUnit(i, targetX, targetY);
            if (distanceSquared < best) best = distanceSquared;
        }
        return best == Float.POSITIVE_INFINITY ? best : (float) Math.sqrt(best);
    }

    public boolean isUnitActive(int index) {
        return index >= 0 && index < units.length && units[index].active;
    }

    public float unitX(int index) {
        return index >= 0 && index < units.length ? units[index].x : 0f;
    }

    public float unitY(int index) {
        return index >= 0 && index < units.length
            ? units[index].groundY + DRAW_SIZE * 0.62f : 0f;
    }

    public void setCalm(boolean calm) {
        if (calm) startRestoration();
    }

    public void startRestoration() {
        for (Unit unit : units) {
            if (!unit.disabled || (unit.animationState != AnimationState.DESTROYING
                && unit.animationState != AnimationState.DESTROYED)) continue;
            unit.destroying = false;
            unit.animationState = AnimationState.RESTORING;
            unit.animTime = 0f;
            unit.restorationTimer = RESTORATION_DURATION;
            unit.damagedTimer = 0f;
        }
    }

    public void completeRestoration() {
        for (int i = 0; i < units.length; i++) {
            Unit unit = units[i];
            if (unit.animationState != AnimationState.RESTORING) continue;
            unit.animationState = AnimationState.RESTORED;
            unit.animTime = 0f;
            unit.restorationTimer = 0f;
            Gdx.app.log("Level3RestorationTrace", "Turret " + i + " restored blue state");
        }
    }

    public boolean isRestorationComplete() {
        for (Unit unit : units) if (unit.animationState == AnimationState.RESTORING) return false;
        return true;
    }

    public void update(float delta) {
        for (Unit unit : units) {
            if (!unit.active && !unit.destroying
                && unit.animationState != AnimationState.RESTORING
                && unit.animationState != AnimationState.RESTORED) continue;
            unit.animTime += delta;
            if (unit.animationState == AnimationState.AIMING) {
                unit.aimTimer = Math.max(0f, unit.aimTimer - delta);
                if (unit.aimTimer <= 0f) beginFiring(unit);
            } else if (unit.animationState == AnimationState.FIRING) {
                unit.firingTimer = Math.max(0f, unit.firingTimer - delta);
                if (unit.firingTimer <= 0f) {
                    unit.animationState = AnimationState.IDLE_ROTATING;
                    unit.animTime = 0f;
                    unit.targetX = Float.NaN;
                    unit.targetY = Float.NaN;
                }
            }
            unit.damagedTimer = Math.max(0f, unit.damagedTimer - delta);
            unit.restorationTimer = Math.max(0f, unit.restorationTimer - delta);
            if (unit.animationState == AnimationState.DAMAGED && unit.damagedTimer <= 0f) {
                unit.animationState = AnimationState.IDLE_ROTATING;
                unit.animTime = 0f;
            }
            if (unit.animationState == AnimationState.DESTROYING && unit.damagedTimer <= 0f) {
                unit.destroying = false;
                unit.animationState = AnimationState.DESTROYED;
            }
            if (unit.animationState == AnimationState.RESTORING
                && unit.restorationTimer <= 0f) {
                int index = indexOf(unit);
                unit.animationState = AnimationState.RESTORED;
                unit.animTime = 0f;
                Gdx.app.log("Level3RestorationTrace", "Turret " + index + " restored blue state");
            }
        }
    }

    public void draw(SpriteBatch batch) {
        for (Unit unit : units) {
            if (!unit.active && !unit.destroying
                && unit.animationState != AnimationState.RESTORING
                && unit.animationState != AnimationState.RESTORED) continue;
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
            } else if (unit.animationState == AnimationState.FIRING) {
                row = ROW_ATTACK;
                time = FIRING_DURATION - unit.firingTimer;
                loop = false;
                if (!unit.firingRenderLogged) {
                    Gdx.app.log("SecurityTurretTrace", "FIRING render branch row=" + row
                        + " frames=" + rows[row].getKeyFrames().length
                        + " frameDuration=" + FRAME_DURATION);
                    unit.firingRenderLogged = true;
                }
            } else if (unit.animationState == AnimationState.AIMING) {
                row = ROW_IDLE;
                time = (AIM_DURATION - unit.aimTimer) * FLASH_DURATION / AIM_DURATION;
                loop = false;
            } else { // IDLE_ROTATING
                row = ROW_IDLE;
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

    public boolean hasLaserToDraw() {
        for (Unit unit : units) {
            if (unit.animationState == AnimationState.FIRING
                && !Float.isNaN(unit.targetX) && !Float.isNaN(unit.targetY)) return true;
        }
        return false;
    }

    // Drawn in world space by Level3Screen. The sprite sheet supplies the muzzle flare and beam
    // buildup; this line connects that effect to the selected player without a duplicate weapon.
    public void drawLaser(ShapeRenderer shape) {
        for (Unit unit : units) {
            if (unit.animationState != AnimationState.FIRING
                || Float.isNaN(unit.targetX) || Float.isNaN(unit.targetY)) continue;
            float progress = 1f - unit.firingTimer / FIRING_DURATION;
            float alpha = 0.55f + 0.45f * (float) Math.sin(progress * Math.PI);
            float muzzleX = unit.x;
            float muzzleY = unit.groundY + DRAW_SIZE * 0.62f;
            shape.setColor(1f, 0.08f, 0.04f, alpha);
            shape.rectLine(muzzleX, muzzleY, unit.targetX, unit.targetY, 8f);
            shape.setColor(1f, 0.86f, 0.62f, alpha);
            shape.rectLine(muzzleX, muzzleY, unit.targetX, unit.targetY, 2.5f);
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

    private int firstAimingIndex() {
        for (int i = 0; i < units.length; i++) {
            if (units[i].active && units[i].animationState == AnimationState.AIMING) return i;
        }
        return -1;
    }

    private int lastActiveIndex() {
        for (int i = units.length - 1; i >= 0; i--) if (units[i].active) return i;
        return -1;
    }

    private int indexOf(Unit target) {
        for (int i = 0; i < units.length; i++) if (units[i] == target) return i;
        return -1;
    }

    public void reset() {
        firingStarted = false;
        formationReady = false;
        formationCount = 0;
        for (Unit unit : units) {
            unit.health = 0f;
            unit.active = false;
            unit.destroying = false;
            unit.disabled = false;
            unit.animationState = AnimationState.IDLE_ROTATING;
            unit.animTime = 0f;
            unit.aimTimer = 0f;
            unit.firingTimer = 0f;
            unit.damagedTimer = 0f;
            unit.restorationTimer = 0f;
            unit.targetX = Float.NaN;
            unit.targetY = Float.NaN;
            unit.firingRenderLogged = false;
        }
    }

    public void dispose() { texture.dispose(); }
}
