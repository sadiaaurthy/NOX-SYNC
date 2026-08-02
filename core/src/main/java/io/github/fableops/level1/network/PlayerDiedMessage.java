package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

/** Client -> host — tells the host (the only one authorized to restart the level) that the client's player has died. */
public class PlayerDiedMessage extends NetworkMessage {
    @Override
    public String getType() { return "PLAYER_DIED"; }

    @Override
    public String serializeBody() { return ""; }
}