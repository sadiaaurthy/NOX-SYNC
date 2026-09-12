package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

/** Host -> client: Level 1 is complete and the host/debug side is advancing to Level 2.
 * A joined client never advances on its own ESC — it only reacts to this, same pattern
 * as LevelRestartMessage. */
public class Level2StartMessage extends NetworkMessage {
    @Override
    public String getType() { return "LEVEL2_START"; }

    @Override
    public String serializeBody() { return ""; }
}
