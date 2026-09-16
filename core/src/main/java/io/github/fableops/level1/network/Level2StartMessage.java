package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: go to Level 2. Carries the loot layout seed so both machines randomise
// Level 2's loot into the same positions instead of the client rolling its own
public class Level2StartMessage extends NetworkMessage {
    private final long lootSeed;

    public Level2StartMessage(long lootSeed) {
        this.lootSeed = lootSeed;
    }

    public long getLootSeed() { return lootSeed; }

    @Override
    public String getType() { return "LEVEL2_START"; }

    @Override
    public String serializeBody() { return Long.toString(lootSeed); }

    public static Level2StartMessage deserialize(String body) {
        return new Level2StartMessage(Long.parseLong(body));
    }
}
