package io.github.fableops.level3.network;

import io.github.fableops.network.messages.NetworkMessage;

// Client -> host: Player 2 confirmed a turn action. Host applies it the same way Level2Controller
// applies CORE_INTERACT/LOOT_INTERACT requests
public class Level3ActionMessage extends NetworkMessage {

    private final int actionOrdinal;
    private final int inventorySlot;
    private final boolean reaction;

    public Level3ActionMessage(int actionOrdinal) {
        this(actionOrdinal, -1, false);
    }

    public Level3ActionMessage(int actionOrdinal, int inventorySlot) {
        this(actionOrdinal, inventorySlot, false);
    }

    public Level3ActionMessage(int actionOrdinal, int inventorySlot, boolean reaction) {
        this.actionOrdinal = actionOrdinal;
        this.inventorySlot = inventorySlot;
        this.reaction = reaction;
    }

    public int getActionOrdinal() { return actionOrdinal; }

    public int getInventorySlot() { return inventorySlot; }

    public boolean isReaction() { return reaction; }

    @Override
    public String getType() { return "LEVEL3_ACTION"; }

    @Override
    public String serializeBody() {
        return actionOrdinal + "," + inventorySlot + "," + (reaction ? 1 : 0);
    }

    public static Level3ActionMessage deserialize(String body) {
        String[] fields = body.split(",", -1);
        int slot = fields.length > 1 ? Integer.parseInt(fields[1]) : -1;
        boolean reaction = fields.length > 2 && fields[2].equals("1");
        return new Level3ActionMessage(Integer.parseInt(fields[0]), slot, reaction);
    }
}
