package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: go to Level 2
public class Level2StartMessage extends NetworkMessage {
    @Override
    public String getType() { return "LEVEL2_START"; }

    @Override
    public String serializeBody() { return ""; }
}
