package io.github.fableops.level2;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;

// The Unstable Core: on the pedestal, carried, or in the socket. Only the host changes it
public class CoreObject {

    public enum State { ON_PEDESTAL, CARRIED, IN_SOCKET }

    private static final float RADIUS = 14f;

    private State state = State.ON_PEDESTAL;
    private int carrierId = 0; // 1 or 2 while carried, otherwise 0
    private float glowTime = 0f;

    public State getState() { return state; }

    public int getCarrierId() { return carrierId; }

    void set(State state, int carrierId) {
        this.state = state;
        this.carrierId = carrierId;
    }

    public void update(float delta) {
        glowTime += delta;
    }

    // The host also uses this to check an E press, so the prompt and the logic always agree
    public String prompt(Level2Map world, Player player, int playerId) {
        switch (state) {
            case ON_PEDESTAL:
                return player.canReach(world.getCoreZone()) ? "Press E to take the core" : null;
            case CARRIED:
                return (carrierId == playerId && player.canReach(world.getSocketZone()))
                    ? "Press E to place the core" : null;
            default:
                return null;
        }
    }

    // Call inside a filled ShapeRenderer pass
    public void draw(ShapeRenderer shape, float centreX, float centreY) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(glowTime * 3f);
        shape.setColor(0.2f, 0.8f, 1f, 0.30f + pulse * 0.25f);
        shape.circle(centreX, centreY, RADIUS + 6f + pulse * 4f, 24);
        shape.setColor(0.6f, 0.95f, 1f, 0.9f);
        shape.circle(centreX, centreY, RADIUS, 20);
    }
}
