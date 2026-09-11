package io.github.fableops.inventory;

/**
 * A single slot both players share — one item, one instance, visible in both panels.
 *
 * Distinct from {@link Inventory}, which is private to one player. Whatever sits here is
 * held by the pair rather than by either of them, so either player can take it out or drop
 * something in, and both see the change. That is the whole point: it is the handover point
 * between two people who are usually in different rooms.
 *
 * Deliberately one slot, not a second grid. A shared container with room to spare is just
 * extra pockets; a single slot forces the pair to agree on what is worth carrying between
 * them, which is the co-operative decision it exists to create.
 *
 * Currently local state. Once items can actually be acquired, the host has to own this and
 * push changes over the existing session channel — otherwise each side would be looking at
 * its own copy and the "shared" part would be a lie. See Level1Screen's host/client split
 * for the pattern.
 */
public class SharedSlot {

    private InventoryItem item;

    public InventoryItem get() { return item; }

    public boolean isEmpty() { return item == null; }

    /** @return the item that was displaced, or null if the slot was empty. */
    public InventoryItem put(InventoryItem newItem) {
        InventoryItem previous = item;
        item = newItem;
        return previous;
    }

    /** @return what was in the slot, leaving it empty. */
    public InventoryItem take() {
        InventoryItem taken = item;
        item = null;
        return taken;
    }

    public void clear() {
        item = null;
    }
}
