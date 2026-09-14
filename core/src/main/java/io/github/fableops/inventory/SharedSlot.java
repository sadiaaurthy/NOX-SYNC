package io.github.fableops.inventory;

// One slot both players can use to hand items to each other
public class SharedSlot {

    private InventoryItem item;

    public InventoryItem get() { return item; }

    // Returns the item that was in the slot, or null
    public InventoryItem put(InventoryItem newItem) {
        InventoryItem previous = item;
        item = newItem;
        return previous;
    }

    public void clear() {
        item = null;
    }
}
