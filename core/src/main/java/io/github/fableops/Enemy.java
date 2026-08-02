package io.github.fableops;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Collidable;
import io.github.fableops.Player;

/**
 * Placeholder enemy — a red square for now. Call setTexture(...) later to
 * swap in real art without touching any other logic (same pattern as Player).
 */
public class Enemy {

    public static final float SIZE = 70f;
    private static final float SPEED = 90f;

    public float x, y;
    public int health = 30;
    private final int side; // which player's room this enemy belongs to (1 or 2)
    private Texture texture;

    public Enemy(float startX, float startY, int side) {
        this.x = startX;
        this.y = startY;
        this.side = side;
    }

    public int getSide() {
        return side;
    }

    public boolean isDead() {
        return health <= 0;
    }

    public void takeDamage(int amount) {
        health -= amount;
    }

    /** Swap the placeholder shape for real art once it exists — nothing else needs to change. */
    public void setTexture(Texture texture) {
        this.texture = texture;
    }

    /** Moves toward the target player, checking collision the same way Player does. */
    public void update(float delta, Player target, Collidable world) {
        float dx = target.x - x;
        float dy = target.y - y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float moveX = (dx / dist) * SPEED * delta;
        float moveY = (dy / dist) * SPEED * delta;

        if (!world.collides(x + moveX, y, SIZE, SIZE, side)) x += moveX;
        if (!world.collides(x, y + moveY, SIZE, SIZE, side)) y += moveY;
    }

    public void draw(ShapeRenderer shape) {
        if (texture != null) return; // sprite path draws separately once art exists
        shape.setColor(Color.RED);
        shape.rect(x, y, SIZE, SIZE);
    }

    public void draw(SpriteBatch batch) {
        if (texture == null) return;
        batch.draw(texture, x, y, SIZE, SIZE);
    }
}
