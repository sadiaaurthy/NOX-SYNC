package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

import io.github.fableops.inventory.Inventory;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.level2.Gun;
import io.github.fableops.level3.Level3Controller;
import io.github.fableops.level3.PlayerActionType;
import io.github.fableops.ui.UiViewport;

// A compact selector over the existing Inventory. It owns no items and performs no item effects;
// Level3Screen only uses it to choose the real inventory slot sent to Level3Controller.
public final class EquipmentSelectionPanel {

    private static final float PANEL_W = 500f;
    private static final float PAD = 20f;
    private static final float HEADER_H = 76f;
    private static final float ROW_H = 52f;
    private static final float FOOTER_H = 47f;
    private static final float ICON = 36f;
    private static final int MAX_VISIBLE_ROWS = 6;

    private static final Color PANEL = new Color(0.03f, 0.04f, 0.05f, 0.96f);
    private static final Color ROW = new Color(0.08f, 0.09f, 0.10f, 0.96f);
    private static final Color SELECTED = new Color(0.12f, 0.20f, 0.22f, 0.98f);
    private static final Color EDGE = new Color(1f, 1f, 1f, 0.12f);
    private static final Color TEXT = new Color(0.94f, 0.94f, 0.91f, 1f);
    private static final Color DIM = new Color(0.62f, 0.62f, 0.60f, 1f);

    private final BitmapFont font;

    public EquipmentSelectionPanel(BitmapFont font) {
        this.font = font;
    }

    public void render(ShapeRenderer shape, SpriteBatch batch, UiViewport ui, int side,
                       String roleName, PlayerActionType action, Inventory inventory,
                       int selectedSlot, Gun gun, Color accent) {
        int compatibleCount = compatibleCount(inventory, action);
        int selectedPosition = compatiblePosition(inventory, action, selectedSlot);
        int firstPosition = Math.max(0, Math.min(selectedPosition - MAX_VISIBLE_ROWS / 2,
            Math.max(0, compatibleCount - MAX_VISIBLE_ROWS)));
        int visibleRows = Math.min(MAX_VISIBLE_ROWS, compatibleCount);
        float panelH = HEADER_H + visibleRows * ROW_H + FOOTER_H;
        float halfW = ui.width() / 2f;
        float x = (side == 1 ? 0f : halfW) + (halfW - PANEL_W) / 2f;
        float y = 30f;

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, PANEL_W, panelH);
        shape.setColor(accent);
        shape.rect(x, y + panelH - 4f, PANEL_W, 4f);
        shape.setColor(EDGE);
        shape.rect(x, y + FOOTER_H - 1f, PANEL_W, 1f);

        int position = 0;
        int drawn = 0;
        for (int slot = 0; slot < Inventory.CAPACITY && drawn < visibleRows; slot++) {
            InventoryItem item = inventory.get(slot);
            if (!Level3Controller.itemSupportsAction(action, item)) continue;
            if (position++ < firstPosition) continue;
            float rowY = y + FOOTER_H + (visibleRows - 1 - drawn) * ROW_H;
            shape.setColor(slot == selectedSlot ? SELECTED : ROW);
            shape.rect(x + PAD, rowY + 4f, PANEL_W - 2f * PAD, ROW_H - 8f);
            drawn++;
        }
        shape.end();
        blend(false);

        batch.begin();
        font.getData().setScale(1.35f);
        font.setColor(TEXT);
        font.draw(batch, roleName + " EQUIPMENT", x + PAD, y + panelH - 18f);
        font.getData().setScale(0.82f);
        font.setColor(DIM);
        font.draw(batch, "Select equipment for " + action.label(), x + PAD, y + panelH - 48f);

        position = 0;
        drawn = 0;
        for (int slot = 0; slot < Inventory.CAPACITY && drawn < visibleRows; slot++) {
            InventoryItem item = inventory.get(slot);
            if (!Level3Controller.itemSupportsAction(action, item)) continue;
            if (position++ < firstPosition) continue;
            float rowY = y + FOOTER_H + (visibleRows - 1 - drawn) * ROW_H;
            batch.draw(item.getIcon(), x + PAD + 8f, rowY + 8f, ICON, ICON);
            font.getData().setScale(1.0f);
            font.setColor(slot == selectedSlot ? accent : TEXT);
            font.draw(batch, (slot == selectedSlot ? "> " : "  ") + item.getName(),
                x + PAD + ICON + 18f, rowY + 33f);
            font.getData().setScale(0.72f);
            font.setColor(DIM);
            String detail = detailFor(action, item, inventory, gun);
            font.draw(batch, detail, x + PANEL_W - PAD - 210f, rowY + 31f,
                190f, Align.right, false);
            drawn++;
        }

        font.getData().setScale(0.78f);
        font.setColor(DIM);
        String positionText = compatibleCount > MAX_VISIBLE_ROWS
            ? "  " + (selectedPosition + 1) + "/" + compatibleCount
            : "";
        font.draw(batch, "ENTER  CONFIRM     ESC  CANCEL" + positionText,
            x + PAD, y + 20f, PANEL_W - 2f * PAD, Align.center, false);
        batch.end();
    }

    private static int compatibleCount(Inventory inventory, PlayerActionType action) {
        int count = 0;
        for (int slot = 0; slot < Inventory.CAPACITY; slot++) {
            if (Level3Controller.itemSupportsAction(action, inventory.get(slot))) count++;
        }
        return count;
    }

    private static int compatiblePosition(Inventory inventory, PlayerActionType action, int selectedSlot) {
        int position = 0;
        for (int slot = 0; slot < Inventory.CAPACITY; slot++) {
            if (!Level3Controller.itemSupportsAction(action, inventory.get(slot))) continue;
            if (slot == selectedSlot) return position;
            position++;
        }
        return 0;
    }

    private static String detailFor(PlayerActionType action, InventoryItem item,
                                    Inventory inventory, Gun gun) {
        if (action == PlayerActionType.BREAKER_WEAPON_ATTACK) {
            return "Ammo: " + gun.getTotalRounds();
        }
        int remaining = 0;
        for (int slot = 0; slot < Inventory.CAPACITY; slot++) {
            InventoryItem carried = inventory.get(slot);
            if (carried != null && item.getName().equalsIgnoreCase(carried.getName())) remaining++;
        }
        return "Remaining: " + remaining;
    }

    private static void blend(boolean on) {
        if (on) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }
}
