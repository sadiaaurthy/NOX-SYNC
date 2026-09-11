package io.github.fableops.inventory;

/**
 * One player's carried items — a fixed 5x5 grid, universal (no categories, no tabs).
 *
 * Each player owns their own instance; nothing is shared between them. The grid is a flat
 * array rather than a list because slot position is meaningful: an item stays where it was
 * put, and removing one leaves a hole rather than shuffling everything left under the
 * player's cursor.
 *
 * Purely model state — it draws nothing and reads no input. {@code selectedIndex} lives
 * here rather than in the UI so a player's cursor position survives closing and reopening
 * the panel.
 */
public class Inventory {

    public static final int COLUMNS = 5;
    public static final int ROWS = 5;
    public static final int CAPACITY = COLUMNS * ROWS;

    private final InventoryItem[] slots = new InventoryItem[CAPACITY];
    private int selectedIndex = 0;

    /** @return true if the item fit; false when every slot is occupied. */
    public boolean add(InventoryItem item) {
        for (int i = 0; i < CAPACITY; i++) {
            if (slots[i] == null) {
                slots[i] = item;
                return true;
            }
        }
        return false;
    }

    /** Places an item in a specific slot, replacing whatever was there. */
    public void set(int index, InventoryItem item) {
        if (index < 0 || index >= CAPACITY) return;
        slots[index] = item;
    }

    /** @return the item in that slot, or null if empty or out of range. */
    public InventoryItem get(int index) {
        if (index < 0 || index >= CAPACITY) return null;
        return slots[index];
    }

    public void remove(int index) {
        set(index, null);
    }

    public int count() {
        int n = 0;
        for (InventoryItem item : slots) {
            if (item != null) n++;
        }
        return n;
    }

    public int getSelectedIndex() { return selectedIndex; }

    public InventoryItem getSelected() { return get(selectedIndex); }

    /**
     * Moves the cursor by whole columns/rows, clamped at the edges rather than wrapping.
     * Wrapping would teleport the cursor across the grid on a single keypress, which reads
     * as a glitch when the player is holding a direction to scan along a row.
     */
    public void moveSelection(int dx, int dy) {
        int col = selectedIndex % COLUMNS;
        int row = selectedIndex / COLUMNS;
        col = Math.max(0, Math.min(COLUMNS - 1, col + dx));
        row = Math.max(0, Math.min(ROWS - 1, row + dy));
        selectedIndex = row * COLUMNS + col;
    }

    public void clear() {
        for (int i = 0; i < CAPACITY; i++) slots[i] = null;
        selectedIndex = 0;
    }
}
