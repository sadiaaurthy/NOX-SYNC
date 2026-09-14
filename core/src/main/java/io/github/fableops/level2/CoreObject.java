package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.PlayerInventories;

// The Unstable Core: on the pedestal, carried, or in the socket. Only the host changes it
public class CoreObject {

    public enum State { ON_PEDESTAL, CARRIED, IN_SOCKET }

    private static final float RADIUS = 14f;

    private final PlayerInventories inventories;
    // UnstableCore.png is a blank 46x46 placeholder until the real sprite is added
    private final Texture icon = new Texture(Gdx.files.internal("UnstableCore.png"));
    // Not shareable, so it stays with whoever picked it up
    private final InventoryItem item = new InventoryItem("Unstable Core",
        "Take it to the reactor socket.", icon, 0f, false);
    private State state = State.ON_PEDESTAL;
    private int carrierId = 0; // 1 or 2 while carried, otherwise 0
    private float glowTime = 0f;

    public CoreObject(PlayerInventories inventories) {
        this.inventories = inventories;
    }

    public State getState() { return state; }

    public int getCarrierId() { return carrierId; }

    // The host and the client both change the core through here, so the carrier's inventory matches on both
    void set(State state, int carrierId) {
        if (this.state == State.CARRIED) inventories.forPlayer(this.carrierId).remove(item);
        if (state == State.CARRIED) inventories.forPlayer(carrierId).add(item);
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

    public void dispose() {
        icon.dispose();
    }
}
