package io.github.fableops;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import io.github.fableops.Collidable;
import io.github.fableops.Player;

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
    private static final float DRAW_SIZE = 120f;
    private static final float DRAW_OFFSET = (SIZE - DRAW_SIZE) / 2f; // negative: sprite overhangs

    private static final int DIR_DOWN = 0, DIR_UP = 1, DIR_LEFT = 2, DIR_RIGHT = 3;

    public float x, y;
    public int health = 30;
    private final int side; // which player's room this enemy belongs to (1 or 2)

    private int facing = DIR_DOWN;
    private boolean facingRight = false; // last horizontal facing, picks the death row
    private float stateTime = 0f;
    private boolean dying = false;
    private float deathTime = 0f;

    public Enemy(float startX, float startY, int side) {
        this.x = startX;
        this.y = startY;
        this.side = side;
    }

    public int getSide() {
        return side;
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

    public void takeDamage(int amount) {
        if (dying) return;
        health -= amount;
        if (health <= 0) {
            health = 0;
            dying = true;
            deathTime = 0f;
        }
    }

    /** Moves toward the target player, checking collision the same way Player does. */
    public void update(float delta, Player target, Collidable world) {
        if (dying) {
            deathTime += delta;
            return;
        }
        stateTime += delta;

        float dx = target.x - x;
        float dy = target.y - y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        // Face whichever axis dominates, so the sprite matches the direction of travel.
        if (Math.abs(dx) > Math.abs(dy)) {
            facing = (dx < 0) ? DIR_LEFT : DIR_RIGHT;
            facingRight = dx > 0;
        } else {
            facing = (dy > 0) ? DIR_UP : DIR_DOWN;
        }

        float moveX = (dx / dist) * SPEED * delta;
        float moveY = (dy / dist) * SPEED * delta;

        if (!world.collides(x + moveX, y, SIZE, SIZE, side)) x += moveX;
        if (!world.collides(x, y + moveY, SIZE, SIZE, side)) y += moveY;
    }

    public void draw(SpriteBatch batch, EnemySprites sprites) {
        TextureRegion frame = dying
            ? sprites.deathFrame(facingRight, deathTime)
            : sprites.walkFrame(facing, stateTime);
        batch.draw(frame, x + DRAW_OFFSET, y + DRAW_OFFSET, DRAW_SIZE, DRAW_SIZE);
    }
}
