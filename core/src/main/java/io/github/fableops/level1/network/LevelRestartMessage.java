package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

public class LevelRestartMessage extends NetworkMessage {
    @Override
    public String getType() { return "LEVEL_RESTART"; }

    @Override
    public String serializeBody() { return ""; }
}