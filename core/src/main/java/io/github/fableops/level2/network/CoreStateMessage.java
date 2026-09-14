package io.github.fableops.level2.network;

import io.github.fableops.level2.CoreObject;
import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: core state and who carries it
public class CoreStateMessage extends NetworkMessage {
    private final CoreObject.State state;
    private final int carrierId;

    public CoreStateMessage(CoreObject.State state, int carrierId) {
        this.state = state;
        this.carrierId = carrierId;
    }

    public CoreObject.State getState() { return state; }

    public int getCarrierId() { return carrierId; }

    @Override
    public String getType() { return "CORE_STATE"; }

    @Override
    public String serializeBody() {
        return state.name() + "," + carrierId;
    }

    public static CoreStateMessage deserialize(String body) {
        String[] parts = body.split(",");
        return new CoreStateMessage(CoreObject.State.valueOf(parts[0]), Integer.parseInt(parts[1]));
    }
}
