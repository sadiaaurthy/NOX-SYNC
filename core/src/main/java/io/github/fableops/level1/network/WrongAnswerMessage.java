package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: close the terminal after a wrong digit
public class WrongAnswerMessage extends NetworkMessage {
    @Override
    public String getType() { return "WRONG_ANSWER"; }

    @Override
    public String serializeBody() { return ""; }
}
