package io.github.fableops;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

// x, y is the bottom-left of the feet hitbox, the sprite is drawn around it
public class Enemy {

    private static final float SPEED = 90f;
    private static final int DIR_DOWN = 0, DIR_UP = 1, DIR_LEFT = 2, DIR_RIGHT = 3;

    // Used to notice when an enemy is stuck on a wall
    private static final float PROGRESS_FRACTION = 0.35f;
    private static final float STUCK_SECONDS = 0.35f;
    private static final float DETOUR_SECONDS = 0.55f;

    public float x, y;
    public int health = 30;
    private final int side; // which player's side (1 or 2)
    private final SpriteBounds bounds;

    private float lastX;
    private float lastY;
    private float stuckTimer = 0f;
    private float detourTimer = 0f;
    private float detourSign = 1f; // +1 sidesteps left of the target direction, -1 right

    private int facing = DIR_DOWN;
    private boolean facingRight = false; // last horizontal facing, picks the death row
    private float stateTime = 0f;
    private boolean dying = false;
    private float deathTime = 0f;
    // Reused by getPosition() to avoid garbage
    private final float[] position = new float[2];

    Enemy(SpriteBounds bounds, float x, float y, int side) {
        this.bounds = bounds;
        this.x = x;
        this.y = y;
        this.side = side;
        // Seeded at the spawn point, so the first frame measures a real step.
        this.lastX = x;
        this.lastY = y;
    }

    public boolean isFinished() {
        return dying && deathTime >= EnemySprites.DEATH_DURATION;
    }

    public boolean isActive() {
        return !dying;
    }

    // Returns a reused array, read it before calling again
    public float[] getPosition() {
        position[0] = x;
        position[1] = y;
        return position;
    }

    public float centreX() { return x - bounds.footX + bounds.bodyX + bounds.bodyW / 2f; }

    public float centreY() { return y - bounds.footY + bounds.bodyY + bounds.bodyH / 2f; }

    public boolean touches(Player player) {
        return player.bodyOverlaps(x - bounds.footX + bounds.bodyX, y - bounds.footY + bounds.bodyY,
            bounds.bodyW, bounds.bodyH);
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

    // Chases the player, and sidesteps for a moment when it gets stuck on a wall
    public void update(float delta, Player target, Collidable world) {
        if (dying) {
            deathTime += delta;
            return;
        }
        stateTime += delta;

        float dx = target.colliderCentreX() - (x + bounds.footW / 2f);
        float dy = target.colliderCentreY() - (y + bounds.footH / 2f);
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float dirX = dx / dist;
        float dirY = dy / dist;

        if (Math.abs(dx) > Math.abs(dy)) {
            facing = (dx < 0) ? DIR_LEFT : DIR_RIGHT;
            facingRight = dx > 0;
        } else {
            facing = (dy > 0) ? DIR_UP : DIR_DOWN;
        }

        if (detourTimer > 0f) {
            detourTimer -= delta;
            // Stick to one side, switching every frame makes it jitter
            step(-dirY * detourSign, dirX * detourSign, delta, world);
        } else {
            step(dirX, dirY, delta, world);
        }

        float movedX = x - lastX;
        float movedY = y - lastY;
        if ((float) Math.sqrt(movedX * movedX + movedY * movedY) < SPEED * delta * PROGRESS_FRACTION) {
            stuckTimer += delta;
            if (stuckTimer >= STUCK_SECONDS && detourTimer <= 0f) {
                detourTimer = DETOUR_SECONDS;
                // Sidestep towards the open side
                float probe = bounds.footW * 0.9f;
                boolean leftOpen = !world.collides(x - dirY * probe, y + dirX * probe,
                    bounds.footW, bounds.footH, side);
                detourSign = leftOpen ? 1f : -1f;
                stuckTimer = 0f;
            }
        } else {
            stuckTimer = 0f;
        }
        lastX = x;
        lastY = y;
    }

    private void step(float dirX, float dirY, float delta, Collidable world) {
        float moveX = dirX * SPEED * delta;
        float moveY = dirY * SPEED * delta;
        if (!world.collides(x + moveX, y, bounds.footW, bounds.footH, side)) x += moveX;
        if (!world.collides(x, y + moveY, bounds.footW, bounds.footH, side)) y += moveY;
    }

    public void nudge(float dx, float dy, Collidable world) {
        if (dying) return;
        if (!world.collides(x + dx, y, bounds.footW, bounds.footH, side)) x += dx;
        if (!world.collides(x, y + dy, bounds.footW, bounds.footH, side)) y += dy;
    }

    public void draw(SpriteBatch batch, EnemySprites sprites) {
        TextureRegion frame = dying
            ? sprites.deathFrame(facingRight, deathTime)
            : sprites.walkFrame(facing, stateTime);
        sprites.draw(batch, frame, x, y);
    }
}
