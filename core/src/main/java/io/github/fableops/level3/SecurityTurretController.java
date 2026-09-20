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
//
// Each unit runs its own cycle:
//   IDLE -> ALERTED -> WAITING_FOR_PLAYER_RESPONSE -> COMBAT_READY -> AIMING -> FIRING -> IDLE
// A player inside TURRET_ATTACK_RANGE alerts the unit (warning only: no beam, no damage). The unit
// then waits, rotating and marking its target, until the host reports that the targeted player has
// prepared (Level3Controller.playerCombatReady). Alert and waiting are derived from positions on
// host and client alike; only the start of combat crosses the network, after which both peers run
// the same fixed timers.
public class SecurityTurretController {
    public static final int MIN_UNITS = 2;
    public static final int UNIT_COUNT = 4;
    public static final float DRAW_SIZE = 135f;
    // Ranged, but still "close combat": twice the 120 unit melee reach used by the Level 1-3
    // players and about the 155-210 unit orbit of a Defense Drone. Measured from the turret's
    // centre to the player's body centre.
    public static final float TURRET_ATTACK_RANGE = 240f;

    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int ROW_IDLE = 0;
    private static final int ROW_ATTACK = 1;
    private static final int ROW_DAMAGED = 2;
    private static final int ROW_RESTORED = 3;
    private static final float MAX_HEALTH = 30f;
    private static final float FRAME_DURATION = 0.09f;
    public static final float ALERT_DURATION = 0.5f;
    public static final float READY_DURATION = 0.35f;
    public static final float AIM_DURATION = 0.45f;
    public static final float FIRING_DURATION = COLUMNS * FRAME_DURATION;
    public static final float FIRE_IMPACT_TIME = 6f * FRAME_DURATION;
    private static final float FLASH_DURATION = COLUMNS * FRAME_DURATION;
    // Time between two damage events of one turret in steady state. The idle wait is derived from
    // it, so changing any animation timing keeps the spacing at this value (suggested 1.5-2.5 s).
    public static final float TURRET_DAMAGE_COOLDOWN = 2.4f;
    private static final float COOLDOWN_DURATION = TURRET_DAMAGE_COOLDOWN
        - (FIRING_DURATION - FIRE_IMPACT_TIME) - ALERT_DURATION - READY_DURATION
        - AIM_DURATION - FIRE_IMPACT_TIME;
    // A hit (sidearm or turn action) keeps the unit from re-engaging for a moment.
    private static final float HIT_RECOVERY_DURATION = 1.0f;
    private static final float RESTORATION_DURATION = COLUMNS * FRAME_DURATION;
    private static final long FORMATION_SEED = 0x545552524554534CL;
    private static final int ALPHA_THRESHOLD = 20;

    public enum AnimationState {
        IDLE_ROTATING, ALERTED, WAITING, COMBAT_READY, AIMING, FIRING,
        DAMAGED, DESTROYING, DESTROYED, RESTORING, RESTORED
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
        float alertTimer;
        float readyTimer;
        float aimTimer;
        float firingTimer;
        float cooldownTimer;
        // >0 from the moment firing starts until the shot lands on the player
        float impactTimer;
        boolean impactReady;
        int targetSide;
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
    private final float[] playerX = new float[2];
    private final float[] playerY = new float[2];
    private boolean playersKnown;

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
            cancelEngagement(unit);
            unit.cooldownTimer = 0f;
            unit.damagedTimer = 0f;
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
        else interrupt(unit);
        return index;
    }

    public void playDamaged(int index, boolean destroyed) {
        if (index < 0 || index >= units.length) return;
        Unit unit = units[index];
        unit.damagedTimer = FLASH_DURATION;
        if (destroyed) disable(unit);
        else interrupt(unit);
    }

    // A hit spoils the shot in progress; the unit recovers before it may engage again.
    private static void interrupt(Unit unit) {
        unit.animationState = AnimationState.DAMAGED;
        cancelEngagement(unit);
        unit.cooldownTimer = HIT_RECOVERY_DURATION;
    }

    private static void disable(Unit unit) {
        unit.active = false;
        unit.destroying = true;
        unit.disabled = true;
        unit.animationState = AnimationState.DESTROYING;
        cancelEngagement(unit);
    }

    private static void cancelEngagement(Unit unit) {
        unit.alertTimer = 0f;
        unit.readyTimer = 0f;
        unit.aimTimer = 0f;
        unit.firingTimer = 0f;
        unit.impactTimer = 0f;
        unit.impactReady = false;
        unit.targetSide = 0;
        unit.targetX = Float.NaN;
        unit.targetY = Float.NaN;
    }

    // Host and client: the targeted player is prepared, so this unit may start its attack. Only a
    // unit that is alerted or waiting can be released; a unit that was destroyed, hit or has left
    // the range in the meantime ignores the call.
    public void beginCombat(int index, int side) {
        if (index < 0 || index >= units.length || (side != 1 && side != 2)) return;
        Unit unit = units[index];
        if (!unit.active || (unit.animationState != AnimationState.ALERTED
            && unit.animationState != AnimationState.WAITING
            && unit.animationState != AnimationState.IDLE_ROTATING)) return;
        unit.targetSide = side;
        unit.targetX = playerX[side - 1];
        unit.targetY = playerY[side - 1];
        unit.alertTimer = 0f;
        unit.animationState = AnimationState.COMBAT_READY;
        unit.readyTimer = READY_DURATION;
    }

    public boolean isInCombat(int index) {
        if (index < 0 || index >= units.length) return false;
        AnimationState state = units[index].animationState;
        return units[index].active && (state == AnimationState.COMBAT_READY
            || state == AnimationState.AIMING || state == AnimationState.FIRING);
    }

    // Both peers feed in the current player positions so tracking and aiming follow the target.
    public void setTrackedPlayers(float x1, float y1, float x2, float y2) {
        playersKnown = true;
        playerX[0] = x1;
        playerY[0] = y1;
        playerX[1] = x2;
        playerY[1] = y2;
    }

    private void beginAiming(Unit unit) {
        unit.animationState = AnimationState.AIMING;
        unit.aimTimer = AIM_DURATION;
    }

    private void beginFiring(Unit unit) {
        unit.animationState = AnimationState.FIRING;
        unit.aimTimer = 0f;
        unit.firingTimer = FIRING_DURATION;
        unit.impactTimer = FIRE_IMPACT_TIME;
        unit.impactReady = false;
        unit.firingRenderLogged = false;
    }

    // Host only: returns the targeted side (1 or 2) once when this unit's shot lands, else 0.
    public int consumeImpactSide(int index) {
        if (index < 0 || index >= units.length || !units[index].impactReady) return 0;
        units[index].impactReady = false;
        return units[index].targetSide;
    }

    public int targetSide(int index) {
        return index >= 0 && index < units.length ? units[index].targetSide : 0;
    }

    public AnimationState stateOf(int index) {
        return index >= 0 && index < units.length ? units[index].animationState : null;
    }

    public static String traceName(AnimationState state) {
        if (state == null) return "NONE";
        switch (state) {
            case ALERTED: return "TURRET_ALERTED";
            case WAITING: return "TURRET_WAITING_FOR_PLAYER_RESPONSE";
            case COMBAT_READY: return "TURRET_COMBAT_READY";
            case AIMING: return "TURRET_AIMING";
            case FIRING: return "TURRET_FIRING";
            case IDLE_ROTATING: return "TURRET_IDLE";
            default: return state.name();
        }
    }

    public float distanceSquaredToUnit(int index, float targetX, float targetY) {
        if (index < 0 || index >= units.length || !units[index].active) return Float.POSITIVE_INFINITY;
        return distanceSquared(units[index], targetX, targetY);
    }

    private static float distanceSquared(Unit unit, float targetX, float targetY) {
        float dx = targetX - unit.x;
        float dy = targetY - (unit.groundY + DRAW_SIZE * 0.5f);
        return dx * dx + dy * dy;
    }

    // The nearer player inside this unit's own attack range (1 or 2), or 0 when both are outside.
    private int sideInRange(Unit unit) {
        if (!playersKnown) return 0;
        float limit = TURRET_ATTACK_RANGE * TURRET_ATTACK_RANGE;
        float d1 = distanceSquared(unit, playerX[0], playerY[0]);
        float d2 = distanceSquared(unit, playerX[1], playerY[1]);
        if (d1 > limit && d2 > limit) return 0;
        return d1 <= d2 ? 1 : 2;
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
            unit.cooldownTimer = Math.max(0f, unit.cooldownTimer - delta);
            if (unit.active) updateAlert(unit);
            if (unit.animationState == AnimationState.ALERTED) {
                unit.alertTimer = Math.max(0f, unit.alertTimer - delta);
                if (unit.alertTimer <= 0f) unit.animationState = AnimationState.WAITING;
            }
            if (unit.animationState == AnimationState.COMBAT_READY
                || unit.animationState == AnimationState.AIMING) {
                // The beam follows the player until the shot is fired, then stays where it went.
                unit.targetX = playerX[unit.targetSide - 1];
                unit.targetY = playerY[unit.targetSide - 1];
            }
            if (unit.animationState == AnimationState.COMBAT_READY) {
                unit.readyTimer = Math.max(0f, unit.readyTimer - delta);
                if (unit.readyTimer <= 0f) beginAiming(unit);
            } else if (unit.animationState == AnimationState.AIMING) {
                unit.aimTimer = Math.max(0f, unit.aimTimer - delta);
                if (unit.aimTimer <= 0f) beginFiring(unit);
            } else if (unit.animationState == AnimationState.FIRING) {
                if (unit.impactTimer > 0f) {
                    unit.impactTimer = Math.max(0f, unit.impactTimer - delta);
                    if (unit.impactTimer <= 0f) unit.impactReady = true;
                }
                unit.firingTimer = Math.max(0f, unit.firingTimer - delta);
                if (unit.firingTimer <= 0f) {
                    unit.animationState = AnimationState.IDLE_ROTATING;
                    unit.animTime = 0f;
                    unit.cooldownTimer = COOLDOWN_DURATION;
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

    // Purely positional, so host and client agree without any message: a unit that is free enters
    // ALERTED when a player steps inside its range, and drops back to idle when they all leave.
    private void updateAlert(Unit unit) {
        AnimationState state = unit.animationState;
        boolean idle = state == AnimationState.IDLE_ROTATING;
        if (!idle && state != AnimationState.ALERTED && state != AnimationState.WAITING) return;
        int side = sideInRange(unit);
        if (side == 0) {
            if (!idle) {
                unit.animationState = AnimationState.IDLE_ROTATING;
                unit.animTime = 0f;
                cancelEngagement(unit);
            }
            return;
        }
        if (idle) {
            if (unit.cooldownTimer > 0f) return;
            unit.animationState = AnimationState.ALERTED;
            unit.alertTimer = ALERT_DURATION;
        }
        // Rotating toward the player: keep marking whoever is nearest until combat is released.
        unit.targetSide = side;
        unit.targetX = playerX[side - 1];
        unit.targetY = playerY[side - 1];
    }

    public boolean hasWarningToDraw() {
        for (Unit unit : units) {
            if (isWarning(unit) && unit.targetSide != 0) return true;
        }
        return false;
    }

    private static boolean isWarning(Unit unit) {
        return unit.active && (unit.animationState == AnimationState.ALERTED
            || unit.animationState == AnimationState.WAITING);
    }

    // World-space targeting marker on the player a waiting turret has picked. It is a soft pulsing
    // disc, not a beam: an alerted turret never draws a laser and never deals damage.
    public void drawWarning(ShapeRenderer shape) {
        for (Unit unit : units) {
            if (!isWarning(unit) || unit.targetSide == 0 || Float.isNaN(unit.targetX)) continue;
            float pulse = 0.5f + 0.5f * (float) Math.sin(unit.animTime * 9.0);
            shape.setColor(1f, 0.55f, 0.12f, 0.16f + 0.16f * pulse);
            shape.circle(unit.targetX, unit.targetY, 38f + 8f * pulse);
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
            } else if (isWarning(unit)) {
                // Warning animation: the idle rotation runs fast while the turret picks its target.
                row = ROW_IDLE;
                time = unit.animTime * 2.5f;
                loop = true;
            } else { // IDLE_ROTATING, COMBAT_READY
                row = ROW_IDLE;
                time = unit.animTime;
                loop = true;
            }
            TextureRegion frame = rows[row].getKeyFrame(time, loop);
            int frameIndex = frameIndex(rows[row].getKeyFrames(), frame);
            float scale = DRAW_SIZE / frame.getRegionHeight();
            float drawW = frame.getRegionWidth() * scale;
            float drawY = unit.groundY - opaqueBottomOffsets[row][frameIndex];
            boolean warning = isWarning(unit);
            if (warning) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(unit.animTime * 9.0);
                batch.setColor(1f, 0.72f - 0.22f * pulse, 0.45f - 0.2f * pulse, 1f);
            }
            batch.draw(frame, unit.x - drawW / 2f, drawY, drawW, DRAW_SIZE);
            if (warning) batch.setColor(1f, 1f, 1f, 1f);
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

    private int lastActiveIndex() {
        for (int i = units.length - 1; i >= 0; i--) if (units[i].active) return i;
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
            unit.active = false;
            unit.destroying = false;
            unit.disabled = false;
            unit.animationState = AnimationState.IDLE_ROTATING;
            unit.animTime = 0f;
            cancelEngagement(unit);
            unit.cooldownTimer = 0f;
            unit.damagedTimer = 0f;
            unit.restorationTimer = 0f;
            unit.firingRenderLogged = false;
        }
    }

    public void dispose() { texture.dispose(); }
}
