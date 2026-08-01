package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

public class ReactorUnlockMessage extends NetworkMessage {
    @Override
    public String getType() { return "REACTOR_UNLOCK"; }

    @Override
    public String serializeBody() { return ""; }
}