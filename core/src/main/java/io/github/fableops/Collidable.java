package io.github.fableops;

/** Anything a Player can move against — implemented by Level1Map and Level2Map. */
public interface Collidable {
    boolean collides(float x, float y, float w, float h, int playerSide);
    float getWorldWidth();
    float getWorldHeight();
}