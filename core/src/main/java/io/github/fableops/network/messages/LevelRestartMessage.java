package io.github.fableops.network.messages;

// Host -> client: the current level starts over. Both levels use it
public class LevelRestartMessage extends NetworkMessage {
    @Override
    public String getType() { return "LEVEL_RESTART"; }

    @Override
    public String serializeBody() { return ""; }
}
