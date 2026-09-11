package io.github.fableops;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;


/**
 * A swarm enemy. Collision stays a plain SIZE x SIZE box; the sprite is drawn larger
 * than that box and centred on it, because the sheet's cells carry a lot of empty
 * padding around the figure.
 */
public class Enemy {

    public static final float SIZE = 70f;
    private static final float SPEED = 90f;

    /**
     * Sprite cells are drawn bigger than the collision box so the figure reads at a
     * sensible size next to the 100px player. Walk and death use the exact same rect,
     * which is what keeps the death effect aligned with where the enemy was standing.
     */
    public static final float DRAW_SIZE = 120f;
    private static final float DRAW_OFFSET = (SIZE - DRAW_SIZE) / 2f; // negative: sprite overhangs

    private static final int DIR_DOWN = 0, DIR_UP = 1, DIR_LEFT = 2, DIR_RIGHT = 3;

    public float x, y;
    public int health = 30;
    private final int side; // which player's room this enemy belongs to (1 or 2)

    // Unstick state — see update(). An enemy covering less than this fraction of its
    // possible step is treated as blocked, not merely slowed by a wall slide.
    private static final float PROGRESS_FRACTION = 0.35f;
    private static final float STUCK_SECONDS = 0.35f;
    private static final float DETOUR_SECONDS = 0.55f;

    private float lastX;
    private float lastY;
    private float stuckTimer = 0f;
    private float detourTimer = 0f;
    private float detourSign = 1f; // +1 = sidestep left of the target direction, -1 = right

    private int facing = DIR_DOWN;
    private boolean facingRight = false; // last horizontal facing, picks the death row
    private float stateTime = 0f;
    private boolean dying = false;
    private float deathTime = 0f;
    /** Reused by getPosition(); never handed out as owned state. */
    private final float[] position = new float[2];

    public Enemy(float startX, float startY, int side) {
        this.x = startX;
        this.y = startY;
        this.side = side;
        // Seed the progress tracker at the spawn point, so the first frame measures a real
        // step rather than a jump from the origin and instantly declares the enemy stuck.
        this.lastX = startX;
        this.lastY = startY;
    }

    /** True once health has run out — the enemy is playing its death animation. */
    public boolean isDying() {
        return dying;
    }

    /** True once the death animation has finished and the enemy can be removed. */
    public boolean isFinished() {
        return dying && deathTime >= EnemySprites.DEATH_DURATION;
    }

    /** A dying enemy no longer chases or deals contact damage. */
    public boolean isActive() {
        return !dying;
    }

    /**
     * This enemy's position in a reusable array, for the host's network snapshot.
     * Owned by the enemy for its whole lifetime so building a snapshot allocates
     * nothing — the caller must read it before the next call.
     */
    public float[] getPosition() {
        position[0] = x;
        position[1] = y;
        return position;
    }

    public void takeDamage(int amount) {
        if (dying) return;
        health -= amount;
        if (health <= 0) {
            health = 0;
            dying = true;
            deathTime = 0f;
        }
    }

    /**
     * Moves toward the target player, checking collision the same way Player does.
     *
     * Steering is greedy — straight at the target, each axis resolved separately so a
     * blocked X still allows the Y step. That alone slides along flat walls but deadlocks
     * in any concave corner: both axes stay blocked and the enemy vibrates against the
     * wall forever while the player stands two metres away behind it. The detour below is
     * the cheap fix — when no real ground has been covered for a moment, commit to
     * sidestepping perpendicular to the target for a short burst, which walks the enemy out
     * around the obstruction. Not pathfinding, but enough to unstick a corner.
     */
    public void update(float delta, Player target, Collidable world) {
        if (dying) {
            deathTime += delta;
            return;
        }
        stateTime += delta;

        // Aim at the player's collider centre, not the sprite box's corner — the sprite box
        // is wider than the body, so chasing its corner made enemies drift to one side.
        float dx = target.centreX() - (x + SIZE / 2f);
        float dy = target.centreY() - (y + SIZE / 2f);
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float dirX = dx / dist;
        float dirY = dy / dist;

        // Face whichever axis dominates, so the sprite matches the direction of travel.
        if (Math.abs(dx) > Math.abs(dy)) {
            facing = (dx < 0) ? DIR_LEFT : DIR_RIGHT;
            facingRight = dx > 0;
        } else {
            facing = (dy > 0) ? DIR_UP : DIR_DOWN;
        }

        if (detourTimer > 0f) {
            detourTimer -= delta;
            // Perpendicular to the target direction, on the side picked when the detour
            // began — committing to one side is what makes progress; re-choosing each
            // frame just oscillates in place.
            step(-dirY * detourSign, dirX * detourSign, delta, world);
        } else {
            step(dirX, dirY, delta, world);
        }

        // Distance actually covered this frame, versus what a clear run would have covered.
        float movedX = x - lastX;
        float movedY = y - lastY;
        float moved = (float) Math.sqrt(movedX * movedX + movedY * movedY);
        if (moved < SPEED * delta * PROGRESS_FRACTION) {
            stuckTimer += delta;
            if (stuckTimer >= STUCK_SECONDS && detourTimer <= 0f) {
                detourTimer = DETOUR_SECONDS;
                // Prefer the side that is actually open; if both or neither are, pick one
                // and stick with it rather than stalling on the decision.
                float probe = SIZE * 0.9f;
                boolean leftOpen = !world.collides(x - dirY * probe, y + dirX * probe, SIZE, SIZE, side);
                detourSign = leftOpen ? 1f : -1f;
                stuckTimer = 0f;
            }
        } else {
            stuckTimer = 0f;
        }
        lastX = x;
        lastY = y;
    }

    /** One collision-resolved step, each axis independently so walls are slid along. */
    private void step(float dirX, float dirY, float delta, Collidable world) {
        float moveX = dirX * SPEED * delta;
        float moveY = dirY * SPEED * delta;
        if (!world.collides(x + moveX, y, SIZE, SIZE, side)) x += moveX;
        if (!world.collides(x, y + moveY, SIZE, SIZE, side)) y += moveY;
    }

    /**
     * Displaces this enemy by a small amount if the destination is clear — used by the
     * swarm's separation pass. Never moves into a wall, so crowding can't push a body
     * through geometry.
     */
    public void nudge(float dx, float dy, Collidable world) {
        if (dying) return;
        if (!world.collides(x + dx, y, SIZE, SIZE, side)) x += dx;
        if (!world.collides(x, y + dy, SIZE, SIZE, side)) y += dy;
    }

    public void draw(SpriteBatch batch, EnemySprites sprites) {
        TextureRegion frame = dying
            ? sprites.deathFrame(facingRight, deathTime)
            : sprites.walkFrame(facing, stateTime);

        // Not every frame is the full uniform cell anymore (EnemySprites trims the
        // row-2 walk row's height and the death rows have irregular per-frame widths),
        // so stretching every frame into a fixed DRAW_SIZE x DRAW_SIZE box would make
        // trimmed/narrower frames visibly stretch relative to full-cell ones. Scaling
        // each frame by its own actual pixel size against the shared uniform-cell
        // reference instead keeps the per-pixel scale identical across every frame —
        // full-cell frames (unchanged: 3 of 4 walk rows) come out at exactly DRAW_SIZE,
        // byte-for-byte the same as before. Centering horizontally and anchoring the
        // top vertically means any trimmed edge only ever eats into empty space that
        // was already below the feet (row-2 walk) or beyond the sides (death rows),
        // never shifting the character's own visible pixels or the floor contact point.
        float scale = DRAW_SIZE / EnemySprites.CELL_SIZE;
        float drawW = frame.getRegionWidth() * scale;
        float drawH = frame.getRegionHeight() * scale;
        float offX = (DRAW_SIZE - drawW) / 2f;
        float offY = DRAW_SIZE - drawH;
        batch.draw(frame, x + DRAW_OFFSET + offX, y + DRAW_OFFSET + offY, drawW, drawH);
    }
}
