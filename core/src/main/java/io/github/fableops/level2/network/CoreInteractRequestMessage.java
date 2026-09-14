package io.github.fableops.level2.network;

import io.github.fableops.network.messages.NetworkMessage;

// Client -> host: player 2 pressed E
public class CoreInteractRequestMessage extends NetworkMessage {
    @Override
    public String getType() { return "CORE_INTERACT"; }

    @Override
    public String serializeBody() { return ""; }
}
