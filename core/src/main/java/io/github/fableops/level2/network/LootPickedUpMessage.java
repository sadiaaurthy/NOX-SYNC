package io.github.fableops.level2.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: loot drop lootId was collected by playerId
public class LootPickedUpMessage extends NetworkMessage {
    private final int lootId;
    private final int playerId;

    public LootPickedUpMessage(int lootId, int playerId) {
        this.lootId = lootId;
        this.playerId = playerId;
    }

    public int getLootId() { return lootId; }

    public int getPlayerId() { return playerId; }

    @Override
    public String getType() { return "LOOT_PICKED_UP"; }

    @Override
    public String serializeBody() { return lootId + "," + playerId; }

    public static LootPickedUpMessage deserialize(String body) {
        String[] parts = body.split(",");
        return new LootPickedUpMessage(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}