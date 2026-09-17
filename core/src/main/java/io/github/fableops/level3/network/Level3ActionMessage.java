package io.github.fableops.level3.network;

import io.github.fableops.network.messages.NetworkMessage;

// Client -> host: Player 2 confirmed a turn action. Host applies it the same way Level2Controller
// applies CORE_INTERACT/LOOT_INTERACT requests
public class Level3ActionMessage extends NetworkMessage {

    private final int actionOrdinal;

    public Level3ActionMessage(int actionOrdinal) {
        this.actionOrdinal = actionOrdinal;
    }

    public int getActionOrdinal() { return actionOrdinal; }

    @Override
    public String getType() { return "LEVEL3_ACTION"; }

    @Override
    public String serializeBody() { return String.valueOf(actionOrdinal); }

    public static Level3ActionMessage deserialize(String body) {
        return new Level3ActionMessage(Integer.parseInt(body));
    }
}
