package io.github.fableops.level2.network;

import io.github.fableops.network.messages.NetworkMessage;

/** Client -> host, sent when the joined player presses E in range of the Core; the
 * host resolves it authoritatively and broadcasts the result via CoreStateMessage. */
public class CoreInteractRequestMessage extends NetworkMessage {
    private final int playerId;

    public CoreInteractRequestMessage(int playerId) {
        this.playerId = playerId;
    }

    public int getPlayerId() { return playerId; }

    @Override
    public String getType() { return "CORE_INTERACT_REQUEST"; }

    @Override
    public String serializeBody() { return String.valueOf(playerId); }

    public static CoreInteractRequestMessage deserialize(String body) {
        return new CoreInteractRequestMessage(Integer.parseInt(body));
    }
}
