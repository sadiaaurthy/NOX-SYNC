package io.github.fableops.level2.network;

import io.github.fableops.level2.CoreObject;
import io.github.fableops.network.messages.NetworkMessage;

/** Host -> client, sent on every Core state change (and once at Level 2 start) so a
 * joined client's Core always reflects the host's authoritative position/state. */
public class CoreStateMessage extends NetworkMessage {
    private final CoreObject.State state;
    private final float x;
    private final float y;
    private final int carrierPlayerId;

    public CoreStateMessage(CoreObject.State state, float x, float y, int carrierPlayerId) {
        this.state = state;
        this.x = x;
        this.y = y;
        this.carrierPlayerId = carrierPlayerId;
    }

    public CoreObject.State getState() { return state; }
    public float getX() { return x; }
    public float getY() { return y; }
    public int getCarrierPlayerId() { return carrierPlayerId; }

    @Override
    public String getType() { return "CORE_STATE"; }

    @Override
    public String serializeBody() {
        return state.name() + "," + x + "," + y + "," + carrierPlayerId;
    }

    public static CoreStateMessage deserialize(String body) {
        String[] parts = body.split(",");
        return new CoreStateMessage(
            CoreObject.State.valueOf(parts[0]),
            Float.parseFloat(parts[1]),
            Float.parseFloat(parts[2]),
            Integer.parseInt(parts[3])
        );
    }
}
