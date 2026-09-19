package io.github.fableops.level2;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

import io.github.fableops.Collidable;
import io.github.fableops.Enemy;
import io.github.fableops.Player;
import io.github.fableops.SwarmController;
import io.github.fableops.level2.network.GunStateMessage;
import io.github.fableops.ui.SplitScreen;

// The sidearm from the loot. The host fires it and runs the timers, the client copies its state
public class Gun {

    public static final int CACHE_ROUNDS = 24;

    private static final int MAGAZINE = 12;
    private static final int START_SPARE = 24;
    private static final float FIRE_INTERVAL = 0.22f;
    private static final float RELOAD_TIME = 1.5f;
    private static final int DAMAGE = 30; // a melee swing does 15
    private static final float RANGE = 520f;
    // Only the general direction matters: an enemy within about 35 degrees of where the player faces
    private static final float AIM_COS = 0.82f;
    // Walls stop bullets, checked this often along the shot
    private static final float SIGHT_STEP = 12f;
    private static final float TRACER_TIME = 0.09f;
    private static final float HIT_MARKER_TIME = 0.25f;

    // HUD box, in the UI viewport's units
    private static final float HUD_W = 300f;
    private static final float HUD_H = 118f;
    private static final float PAD = 16f;
    private static final float BAR_H = 8f;
    private static final Color PANEL = new Color(0.02f, 0.02f, 0.03f, 0.72f);
    private static final Color BAR_BACK = new Color(0.22f, 0.22f, 0.24f, 1f);
    private static final Color TEXT = new Color(0.94f, 0.94f, 0.92f, 1f);
    private static final Color DIM = new Color(0.62f, 0.62f, 0.6f, 1f);
    private static final Color RED = new Color(1f, 0.33f, 0.33f, 1f);
    private static final Color ORANGE = new Color(1f, 0.6f, 0.2f, 1f);

    private int owner = 0; // player id, 0 until someone picks it up
    private int magazine = MAGAZINE;
    private int spare = START_SPARE;
    private boolean ammoCacheCollected = false;
    private float reloadLeft = 0f;
    private float cooldown = 0f;

    // The last shot, for the tracer and the hit marker. shots only counts up, so the client can spot a new one
    private int shots = 0;
    private float fromX, fromY, toX, toY;
    private boolean hit;
    private float tracerTimer = 0f;
    private float hitMarkerTimer = 0f;

    // The HUD numbers are only turned into text when they change
    private int shownMagazine = -1;
    private int shownSpare = -1;
    private String magazineText = "";
    private String spareText = "";

    public int getOwner() { return owner; }

    public void giveTo(int playerId) { owner = playerId; }

    public void addSpare(int rounds) {
        spare += rounds;
        if (rounds > 0) ammoCacheCollected = true;
    }

    public boolean hasAmmo() { return magazine > 0 || spare > 0; }

    public boolean hasAmmoCache() { return ammoCacheCollected; }

    public int turnBasedDamage() { return ammoCacheCollected ? 45 : DAMAGE; }

    // Level 3 is turn based: one round is consumed when the carried sidearm supports a physical
    // action. This reuses the weapon and ammunition collected in Level 2 instead of inventing a
    // second equipment model for the Warden encounter.
    public boolean useTurnBasedRound() {
        if (owner == 0 || (magazine == 0 && spare == 0)) return false;
        if (magazine == 0) {
            int rounds = Math.min(MAGAZINE, spare);
            magazine = rounds;
            spare -= rounds;
        }
        magazine--;
        return true;
    }

    // Level 3 resolves damage through its turn controller, but still uses the existing tracer so a
    // weapon action is a visible shot rather than only a changed number.
    public void showTurnBasedShot(Player shooter, float targetX, float targetY, boolean hit) {
        fromX = shooter.centreX();
        fromY = shooter.centreY();
        toX = targetX;
        toY = targetY;
        this.hit = hit;
        shots++;
        tracerTimer = TRACER_TIME;
        if (hit) hitMarkerTimer = HIT_MARKER_TIME;
    }

    public void update(float delta) {
        cooldown = Math.max(0f, cooldown - delta);
        tracerTimer = Math.max(0f, tracerTimer - delta);
        hitMarkerTimer = Math.max(0f, hitMarkerTimer - delta);
        if (reloadLeft <= 0f) return;

        reloadLeft -= delta;
        if (reloadLeft > 0f) return;
        reloadLeft = 0f;
        int rounds = Math.min(MAGAZINE - magazine, spare);
        magazine += rounds;
        spare -= rounds;
    }

    // Host: what the attack button does for this player. False means there's nothing to shoot with,
    // so the caller swings instead
    public boolean trigger(int playerId, Player shooter, SwarmController swarm, Collidable world) {
        if (playerId != owner || (magazine == 0 && spare == 0)) return false;
        if (magazine == 0) {
            reload(playerId);
        } else if (reloadLeft <= 0f && cooldown <= 0f) {
            fire(shooter, swarm, world);
        }
        return true;
    }

    public void reload(int playerId) {
        if (playerId != owner || reloadLeft > 0f || magazine == MAGAZINE || spare == 0) return;
        reloadLeft = RELOAD_TIME;
    }

    private void fire(Player shooter, SwarmController swarm, Collidable world) {
        magazine--;
        cooldown = FIRE_INTERVAL;

        float dirX = shooter.facingX();
        float dirY = shooter.facingY();
        // Aiming runs along the feet, the same height walls are measured at
        float feetX = shooter.colliderCentreX();
        float feetY = shooter.colliderCentreY();
        Enemy target = nearestInSight(swarm.getEnemiesP1(), null, feetX, feetY, dirX, dirY, world);
        target = nearestInSight(swarm.getEnemiesP2(), target, feetX, feetY, dirX, dirY, world);

        fromX = shooter.centreX();
        fromY = shooter.centreY();
        hit = target != null;
        if (hit) {
            toX = target.centreX();
            toY = target.centreY();
            target.takeDamage(DAMAGE);
            hitMarkerTimer = HIT_MARKER_TIME;
        } else {
            float reach = clearDistance(feetX, feetY, dirX, dirY, RANGE, world);
            toX = fromX + dirX * reach;
            toY = fromY + dirY * reach;
        }
        shots++;
        tracerTimer = TRACER_TIME;
        if (magazine == 0) reload(owner);
    }

    private static Enemy nearestInSight(List<Enemy> enemies, Enemy best, float feetX, float feetY,
                                        float dirX, float dirY, Collidable world) {
        float bestDist = (best == null) ? RANGE : distanceTo(best, feetX, feetY);
        for (int i = 0; i < enemies.size(); i++) {
            Enemy enemy = enemies.get(i);
            if (!enemy.isActive()) continue;
            float dx = enemy.footCentreX() - feetX;
            float dy = enemy.footCentreY() - feetY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist >= bestDist) continue;
            // An enemy right on top of the player always counts as in front of them
            if (dist > 1f && ((dx * dirX + dy * dirY) / dist < AIM_COS
                || clearDistance(feetX, feetY, dx / dist, dy / dist, dist, world) < dist)) continue;
            best = enemy;
            bestDist = dist;
        }
        return best;
    }

    private static float distanceTo(Enemy enemy, float x, float y) {
        float dx = enemy.footCentreX() - x;
        float dy = enemy.footCentreY() - y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // How far a bullet gets before a wall, up to maxDist
    private static float clearDistance(float x, float y, float dirX, float dirY, float maxDist, Collidable world) {
        for (float travelled = SIGHT_STEP; travelled < maxDist; travelled += SIGHT_STEP) {
            if (world.collides(x + dirX * travelled, y + dirY * travelled, 1f, 1f, 0)) return travelled;
        }
        return maxDist;
    }

    public GunStateMessage toMessage() {
        return new GunStateMessage(owner, magazine, spare, reloadLeft, shots, fromX, fromY, toX, toY, hit);
    }

    // Client side
    public void apply(GunStateMessage state) {
        owner = state.getOwner();
        magazine = state.getMagazine();
        spare = state.getSpare();
        reloadLeft = state.getReloadLeft();
        if (state.getShots() == shots) return;

        shots = state.getShots();
        fromX = state.getFromX();
        fromY = state.getFromY();
        toX = state.getToX();
        toY = state.getToY();
        hit = state.isHit();
        tracerTimer = TRACER_TIME;
        if (hit) hitMarkerTimer = HIT_MARKER_TIME;
    }

    // The gun and its ammo go back in the maze when the level restarts
    public void reset() {
        owner = 0;
        magazine = MAGAZINE;
        spare = START_SPARE;
        ammoCacheCollected = false;
        reloadLeft = 0f;
        cooldown = 0f;
        tracerTimer = 0f;
        hitMarkerTimer = 0f;
    }

    public boolean hasShotToDraw() { return tracerTimer > 0f || hitMarkerTimer > 0f; }

    // Tracer and hit marker in world units. Call inside a filled ShapeRenderer pass with blending on
    public void drawShot(ShapeRenderer shape) {
        if (tracerTimer > 0f) {
            shape.setColor(1f, 0.92f, 0.55f, tracerTimer / TRACER_TIME);
            shape.rectLine(fromX, fromY, toX, toY, 3f);
        }
        if (hitMarkerTimer <= 0f) return;

        float arm = 14f;
        float gap = 5f;
        shape.setColor(1f, 1f, 1f, hitMarkerTimer / HIT_MARKER_TIME);
        shape.rectLine(toX - arm, toY - arm, toX - gap, toY - gap, 3f);
        shape.rectLine(toX + gap, toY + gap, toX + arm, toY + arm, 3f);
        shape.rectLine(toX - arm, toY + arm, toX - gap, toY + gap, 3f);
        shape.rectLine(toX + gap, toY - gap, toX + arm, toY - arm, 3f);
    }

    // Ammo counter and reload bar in the bottom corner of the holder's half, like a shooter's HUD
    public void drawHud(ShapeRenderer shape, SpriteBatch batch, BitmapFont font, float uiWidth) {
        if (owner == 0) return;
        float x = (owner == 1 ? uiWidth / 2f : uiWidth) - SplitScreen.HUD_MARGIN - HUD_W;
        float y = SplitScreen.HUD_MARGIN;
        if (magazine != shownMagazine) {
            shownMagazine = magazine;
            magazineText = Integer.toString(magazine);
        }
        if (spare != shownSpare) {
            shownSpare = spare;
            spareText = "/ " + spare;
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, HUD_W, HUD_H);
        shape.setColor(owner == 1 ? SplitScreen.ACCENT_P1 : SplitScreen.ACCENT_P2);
        shape.rect(x, y, 4f, HUD_H);
        if (reloadLeft > 0f) {
            float barW = HUD_W - 2 * PAD;
            shape.setColor(BAR_BACK);
            shape.rect(x + PAD, y + PAD, barW, BAR_H);
            shape.setColor(ORANGE);
            shape.rect(x + PAD, y + PAD, barW * (1f - reloadLeft / RELOAD_TIME), BAR_H);
        }
        shape.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        batch.begin();
        font.getData().setScale(1.15f);
        font.setColor(DIM);
        font.draw(batch, "SIDEARM", x + PAD, y + HUD_H - PAD);
        String status = status();
        if (status != null) {
            font.setColor(reloadLeft > 0f ? ORANGE : RED);
            font.draw(batch, status, x + PAD, y + PAD + BAR_H + 26f);
        }
        font.getData().setScale(1.7f);
        font.setColor(DIM);
        font.draw(batch, spareText, x + HUD_W - PAD - 78f, y + HUD_H - 44f);
        font.getData().setScale(3.4f);
        font.setColor(magazine <= MAGAZINE / 4 ? RED : TEXT);
        font.draw(batch, magazineText, x + PAD, y + HUD_H - 30f, HUD_W - 2 * PAD - 86f, Align.right, false);
        batch.end();
    }

    private String status() {
        if (reloadLeft > 0f) return "RELOADING";
        if (magazine > 0) return null;
        return (spare > 0) ? "RELOAD" : "NO AMMO";
    }
}
