package io.github.fableops.level2;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * The Unstable Core — a reusable-objective foundation, not ordinary loot. State only
 * ever changes host-side (setLocalState is package-private, callable only from
 * Level2Controller); a joined client always goes through applyRemoteState(), the same
 * "never bypass the authoritative transition" shape Player.health/enemy sync already
 * use. Final timer/debuff behavior once carried is intentionally not implemented yet —
 * this only prepares the pickup/drop hooks.
 */
public class CoreObject implements Interactable {

    public enum State { ON_GROUND, CARRIED, PLACED_ON_ALTAR }

    public static final float SIZE = 48f;
    private static final float INTERACT_RANGE = 90f;

    private float x, y;
    private State state = State.ON_GROUND;
    private int carrierPlayerId = -1; // -1 = not currently carried
    private float glowTime = 0f;

    private InteractionHandler interactionHandler = playerId -> { };

    public interface InteractionHandler {
        void handle(int playerId);
    }

    public CoreObject(float startX, float startY) {
        this.x = startX;
        this.y = startY;
    }

    public State getState() { return state; }
    public float getX() { return x; }
    public float getY() { return y; }
    public int getCarrierPlayerId() { return carrierPlayerId; }

    /** Host-only mutation — see class doc. Package-private on purpose. */
    void setLocalState(State state, float x, float y, int carrierPlayerId) {
        this.state = state;
        this.x = x;
        this.y = y;
        this.carrierPlayerId = carrierPlayerId;
    }

    /** Client-side synchronization method — adopts host-confirmed state, never guesses it. */
    public void applyRemoteState(State state, float x, float y, int carrierPlayerId) {
        setLocalState(state, x, y, carrierPlayerId);
    }

    /** Host/debug only — called once per frame while CARRIED so the Core tracks its carrier. */
    void followCarrier(float carrierX, float carrierY) {
        if (state != State.CARRIED) return;
        this.x = carrierX;
        this.y = carrierY;
    }

    public void update(float delta) {
        glowTime += delta;
    }

    public void setInteractionHandler(InteractionHandler handler) {
        this.interactionHandler = handler;
    }

    @Override
    public boolean isInRange(float playerX, float playerY, float playerSize) {
        if (state == State.PLACED_ON_ALTAR) return false; // nothing left to do with it (yet)
        float dx = (playerX + playerSize / 2f) - (x + SIZE / 2f);
        float dy = (playerY + playerSize / 2f) - (y + SIZE / 2f);
        return dx * dx + dy * dy <= INTERACT_RANGE * INTERACT_RANGE;
    }

    @Override
    public String getInteractionPrompt(int playerId) {
        if (state == State.ON_GROUND) return "Press E to collect Core";
        if (state == State.CARRIED && carrierPlayerId == playerId) return "Press E to set Core down";
        return "";
    }

    @Override
    public void onInteract(int playerId) {
        interactionHandler.handle(playerId);
    }

    /**
     * Placeholder art — a pulsing glow ring plus a bright core dot, floating marker
     * only. Assumes shape.begin(ShapeType.Filled) is already active (same convention
     * Level1Map.drawTerminalMarker uses), so the caller controls the batch.
     */
    public void draw(ShapeRenderer shape) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(glowTime * 3f);
        float centerX = x + SIZE / 2f;
        float centerY = y + SIZE / 2f;

        shape.setColor(0.2f, 0.8f, 1f, 0.30f + pulse * 0.25f);
        shape.circle(centerX, centerY, SIZE / 2f + 6f + pulse * 4f, 24);

        shape.setColor(0.6f, 0.95f, 1f, 0.9f);
        shape.circle(centerX, centerY, SIZE / 2f, 20);
    }
}
