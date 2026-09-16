package io.github.fableops.level2.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: go to Level 3
public class Level3StartMessage extends NetworkMessage {
    @Override
    public String getType() { return "LEVEL3_START"; }

    @Override
    public String serializeBody() { return ""; }
}
