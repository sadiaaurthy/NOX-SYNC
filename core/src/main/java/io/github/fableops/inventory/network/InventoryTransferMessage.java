package io.github.fableops.inventory.network;

import io.github.fableops.network.messages.NetworkMessage;

// Sent client -> host as a request and host -> client after the authoritative transfer succeeds.
// Item data is not duplicated on the wire: loot pickup messages already put the same InventoryItem
// in the same slot on both peers, so a transfer only needs its source and personal-grid slot.
public final class InventoryTransferMessage extends NetworkMessage {

    private final int playerSide;
    private final boolean fromShared;
    private final int personalSlot;

    public InventoryTransferMessage(int playerSide, boolean fromShared, int personalSlot) {
        this.playerSide = playerSide;
        this.fromShared = fromShared;
        this.personalSlot = personalSlot;
    }

    public int getPlayerSide() { return playerSide; }

    public boolean isFromShared() { return fromShared; }

    public int getPersonalSlot() { return personalSlot; }

    @Override
    public String getType() { return "INVENTORY_TRANSFER"; }

    @Override
    public String serializeBody() {
        return playerSide + "," + (fromShared ? 1 : 0) + "," + personalSlot;
    }

    public static InventoryTransferMessage deserialize(String body) {
        String[] fields = body.split(",", -1);
        if (fields.length != 3) throw new IllegalArgumentException("Invalid inventory transfer");
        return new InventoryTransferMessage(Integer.parseInt(fields[0]), fields[1].equals("1"),
            Integer.parseInt(fields[2]));
    }
}
