package io.github.fableops;

public interface Collidable {
    boolean collides(float x, float y, float w, float h, int playerSide);
    float getWorldWidth();
    float getWorldHeight();
}