package io.github.fableops;

public interface Collidable {
    boolean collides(float x, float y, float w, float h, int playerSide);
    float getWorldWidth();
    float getWorldHeight();

    // Where an enemy may be dropped in. Anywhere it fits, unless a level says otherwise -
    // Level 2 also keeps them out of the exit so the way to Level 3 never spawns blocked
    default boolean blocksSpawn(float x, float y, float w, float h, int playerSide) {
        return collides(x, y, w, h, playerSide);
    }
}