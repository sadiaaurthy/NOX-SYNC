package io.github.fableops.network.messages;

/** Base class for messages on the generic session channel (separate from GameServer/GameClient). */
public abstract class NetworkMessage {
    public abstract String getType();
    public abstract String serializeBody();

    public String toLine() {
        return getType() + "|" + serializeBody();
    }
}