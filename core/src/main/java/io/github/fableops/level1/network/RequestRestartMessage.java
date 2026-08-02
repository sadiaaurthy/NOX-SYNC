package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

/** Client -> host — sent when the client presses any key on the Mission Failed screen; only the host can actually restart. */
public class RequestRestartMessage extends NetworkMessage {
    @Override
    public String getType() { return "REQUEST_RESTART"; }

    @Override
    public String serializeBody() { return ""; }
}