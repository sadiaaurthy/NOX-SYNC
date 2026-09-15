package io.github.fableops.level2.network;

import io.github.fableops.network.messages.NetworkMessage;

// Client -> host: player 2 pressed E within reach of loot drop lootId
public class LootInteractRequestMessage extends NetworkMessage {
    private final int lootId;

    public LootInteractRequestMessage(int lootId) {
        this.lootId = lootId;
    }

    public int getLootId() { return lootId; }

    @Override
    public String getType() { return "LOOT_INTERACT"; }

    @Override
    public String serializeBody() { return Integer.toString(lootId); }

    public static LootInteractRequestMessage deserialize(String body) {
        return new LootInteractRequestMessage(Integer.parseInt(body));
    }
}
