package io.github.fableops;

/** Anything a Player can move against — implemented by WorldMap (Level 2+) and Level1Map. */
public interface Collidable {
    boolean collides(float x, float y, float w, float h, int playerSide);
    float getWorldWidth();
    float getWorldHeight();
}