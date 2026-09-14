package io.github.fableops.inventory;

// 5x5 grid as an array, so removing an item leaves a gap instead of shifting the others
public class Inventory {

    public static final int COLUMNS = 5;
    public static final int ROWS = 5;
    public static final int CAPACITY = COLUMNS * ROWS;

    private final InventoryItem[] slots = new InventoryItem[CAPACITY];
    private int selectedIndex = 0;

    public boolean add(InventoryItem item) {
        for (int i = 0; i < CAPACITY; i++) {
            if (slots[i] == null) {
                slots[i] = item;
                return true;
            }
        }
        return false;
    }

    public void set(int index, InventoryItem item) {
        if (index < 0 || index >= CAPACITY) return;
        slots[index] = item;
    }

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

    // Clamped at the edges, no wrap-around
    public void moveSelection(int dx, int dy) {
        int col = Math.max(0, Math.min(COLUMNS - 1, selectedIndex % COLUMNS + dx));
        int row = Math.max(0, Math.min(ROWS - 1, selectedIndex / COLUMNS + dy));
        selectedIndex = row * COLUMNS + col;
    }
}
