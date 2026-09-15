package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;
import io.github.fableops.inventory.Inventory;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.SharedSlot;

// Inventory panel: 5x5 grid, the player's portrait and the shared slot
// Only drawn over the owner's half, so the other player can keep playing
public class InventoryUI {

    // UI units, fits inside one half
    private static final float PANEL_W = 820f;
    private static final float PANEL_H = 780f;
    private static final float PAD = 30f;
    private static final float BORDER = 4f;

    private static final float SLOT = 78f;
    private static final float SLOT_GAP = 10f;
    private static final float GRID_W = Inventory.COLUMNS * SLOT + (Inventory.COLUMNS - 1) * SLOT_GAP;
    private static final float GRID_H = Inventory.ROWS * SLOT + (Inventory.ROWS - 1) * SLOT_GAP;

    private static final float TITLE_SCALE = 3.0f;
    private static final float LABEL_SCALE = 2.0f;
    private static final float BODY_SCALE = 1.8f;

    // Colours from launcher.css
    private static final Color PANEL_BG   = new Color(0.039f, 0.043f, 0.047f, 0.98f);
    private static final Color INNER_LINE = new Color(0.141f, 0.133f, 0.125f, 1f);
    private static final Color SLOT_BG    = new Color(0.078f, 0.082f, 0.086f, 1f);
    private static final Color SLOT_LINE  = new Color(0.165f, 0.165f, 0.157f, 1f);
    private static final Color TEXT       = new Color(0.929f, 0.929f, 0.909f, 1f);
    private static final Color DIM        = new Color(0.451f, 0.451f, 0.42f, 1f);
    private static final Color ORANGE     = new Color(1f, 0.541f, 0.239f, 1f);
    private static final Color MAGENTA    = new Color(1f, 0.16f, 0.43f, 1f);

    private static final int HP_SEGMENTS = 10;
    private static final float HP_CRITICAL = 0.3f;
    // Reused every frame for the health bar
    private final StringBuilder healthBar = new StringBuilder(HP_SEGMENTS);

    private final BitmapFont font;

    private boolean open = false;
    // 1 = left half, 2 = right half
    private int playerSide = 1;
    private boolean sharedFocused = false;

    // Right-hand column: portrait above, the shared slot beneath it.
    private static final float SHARED_BLOCK_H = 110f;

    // Owned by PlayerInventories
    public InventoryUI(BitmapFont font) {
        this.font = font;
    }

    public boolean isOpen() { return open; }

    public void close() { open = false; }

    public void toggle(int playerSide) {
        if (open) {
            open = false;
            return;
        }
        this.playerSide = playerSide;
        this.open = true;
        // Always reopen on the grid, not the shared slot
        this.sharedFocused = false;
    }

    // Uses the owner's movement keys, that player can't move while the panel is open
    // The open/close key is handled by the caller, isKeyJustPressed stays true all frame
    public void handleInput(Inventory inventory, SharedSlot shared, Player owner,
                            int keyUp, int keyDown, int keyLeft, int keyRight) {
        if (!open) return;

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            open = false;
            return;
        }
        // TAB switches between the grid and the shared slot
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) sharedFocused = !sharedFocused;

        if (!sharedFocused) {
            if (Gdx.input.isKeyJustPressed(keyLeft))  inventory.moveSelection(-1, 0);
            if (Gdx.input.isKeyJustPressed(keyRight)) inventory.moveSelection(1, 0);
            // Grid row 0 is the top row, so "up" walks toward lower row indices.
            if (Gdx.input.isKeyJustPressed(keyUp))    inventory.moveSelection(0, -1);
            if (Gdx.input.isKeyJustPressed(keyDown))  inventory.moveSelection(0, 1);
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) use(inventory, shared, owner);
        // R, not F, because F is player 1's attack
        if (Gdx.input.isKeyJustPressed(Input.Keys.R)) transfer(inventory, shared);
    }

    // Items are used up even at full health
    private void use(Inventory inventory, SharedSlot shared, Player owner) {
        InventoryItem item = sharedFocused ? shared.get() : inventory.getSelected();
        if (item == null || !item.isConsumable()) return;
        owner.heal(item.getHealAmount());
        if (sharedFocused) shared.clear();
        else inventory.remove(inventory.getSelectedIndex());
    }

    // Swaps with the shared slot. Taking it out fails if the grid is full
    private void transfer(Inventory inventory, SharedSlot shared) {
        if (sharedFocused) {
            InventoryItem incoming = shared.get();
            if (incoming == null) return;
            if (!inventory.add(incoming)) return; // grid is full
            shared.clear();
        } else {
            int index = inventory.getSelectedIndex();
            InventoryItem outgoing = inventory.get(index);
            if (outgoing == null || !outgoing.isShareable()) return;
            inventory.set(index, shared.put(outgoing));
        }
    }

    // accent is cyan for P1, magenta for P2
    public void render(ShapeRenderer shape, SpriteBatch batch, float uiWorldW, float uiWorldH,
                       Inventory inventory, SharedSlot shared, Player player, Color accent) {
        if (!open) return;

        float halfW = uiWorldW / 2f;
        float panelX = (playerSide == 1 ? 0f : halfW) + (halfW - PANEL_W) / 2f;
        float panelY = (uiWorldH - PANEL_H) / 2f;

        // ShapeRenderer doesn't turn on blending by itself
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        drawChrome(shape, panelX, panelY, uiWorldW, uiWorldH, accent);
        drawSlots(shape, panelX, panelY, inventory, accent);
        drawPortraitFrame(shape, panelX, panelY);
        drawSharedSlot(shape, panelX, panelY, accent);

        // One batch pass for these three, so none of them can use the ShapeRenderer
        batch.begin();
        drawIcons(batch, panelX, panelY, inventory, shared);
        drawText(batch, panelX, panelY, inventory, shared, player, accent);
        drawPortrait(batch, panelX, panelY, player);
        batch.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawChrome(ShapeRenderer shape, float panelX, float panelY,
                            float uiWorldW, float uiWorldH, Color accent) {
        float halfW = uiWorldW / 2f;
        float halfX = (playerSide == 1) ? 0f : halfW;

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0f, 0f, 0f, 0.55f);
        shape.rect(halfX, 0f, halfW, uiWorldH);

        // Border in the player's colour
        shape.setColor(accent.r, accent.g, accent.b, 1f);
        shape.rect(panelX - BORDER, panelY - BORDER, PANEL_W + 2 * BORDER, PANEL_H + 2 * BORDER);
        shape.setColor(PANEL_BG);
        shape.rect(panelX, panelY, PANEL_W, PANEL_H);
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(INNER_LINE);
        shape.rect(panelX + 8f, panelY + 8f, PANEL_W - 16f, PANEL_H - 16f);
        shape.end();

        float dividerTop = panelY + PANEL_H - PAD - 108f;
        float dividerBottom = panelY + PAD + 104f;
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(INNER_LINE);
        shape.rect(panelX + PAD, dividerTop, PANEL_W - 2 * PAD, 3f);
        shape.rect(panelX + PAD, dividerBottom, PANEL_W - 2 * PAD, 3f);

        // Same two ticks as the launcher logo
        shape.setColor(accent.r, accent.g, accent.b, 1f);
        float tickY = panelY + PANEL_H - PAD - 30f;
        shape.rect(panelX + PAD, tickY, 7f, 26f);
        shape.rect(panelX + PAD + 12f, tickY, 7f, 26f);
        shape.end();
    }

    private void drawSlots(ShapeRenderer shape, float panelX, float panelY,
                           Inventory inventory, Color accent) {
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(SLOT_BG);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            shape.rect(slotX(panelX, i), slotY(panelY, i), SLOT, SLOT);
        }
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            float sx = slotX(panelX, i);
            float sy = slotY(panelY, i);
            boolean selected = (i == inventory.getSelectedIndex());
            shape.setColor(selected ? accent : SLOT_LINE);
            shape.rect(sx, sy, SLOT, SLOT);
            // Double outline for the cursor
            if (selected) shape.rect(sx + 3f, sy + 3f, SLOT - 6f, SLOT - 6f);
        }
        shape.end();
    }

    private static float rightColumnX(float panelX) { return panelX + PAD + GRID_W + 24f; }

    private static float rightColumnW() { return PANEL_W - 2 * PAD - GRID_W - 24f; }

    private static float contentTop(float panelY) { return panelY + PANEL_H - PAD - 124f; }

    private static float sharedSlotY(float panelY) { return contentTop(panelY) - GRID_H + 10f; }

    private static float sharedSlotX(float panelX) { return rightColumnX(panelX) + (rightColumnW() - SLOT) / 2f; }

    private static float slotX(float panelX, int i) { return panelX + PAD + (i % Inventory.COLUMNS) * (SLOT + SLOT_GAP); }

    private static float slotY(float panelY, int i) {
        return contentTop(panelY) - (i / Inventory.COLUMNS + 1) * SLOT - (i / Inventory.COLUMNS) * SLOT_GAP;
    }

    // Kept apart from the grid so it doesn't look like one of the player's own slots
    private void drawSharedSlot(ShapeRenderer shape, float panelX, float panelY, Color accent) {
        float slotX = sharedSlotX(panelX);
        float slotY = sharedSlotY(panelY);

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(SLOT_BG);
        shape.rect(slotX, slotY, SLOT, SLOT);
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(sharedFocused ? accent : SLOT_LINE);
        shape.rect(slotX, slotY, SLOT, SLOT);
        if (sharedFocused) shape.rect(slotX + 3f, slotY + 3f, SLOT - 6f, SLOT - 6f);
        shape.end();
    }

    private void drawIcons(SpriteBatch batch, float panelX, float panelY, Inventory inventory, SharedSlot shared) {
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            drawIcon(batch, inventory.get(i), slotX(panelX, i), slotY(panelY, i));
        }
        drawIcon(batch, shared.get(), sharedSlotX(panelX), sharedSlotY(panelY));
    }

    // 46x46 in the middle of the slot, the same size as UnstableCore.png
    private static void drawIcon(SpriteBatch batch, InventoryItem item, float slotX, float slotY) {
        if (item != null) batch.draw(item.getIcon(), slotX + 16f, slotY + 16f, SLOT - 32f, SLOT - 32f);
    }

    private void drawPortraitFrame(ShapeRenderer shape, float panelX, float panelY) {
        float boxX = rightColumnX(panelX);
        float boxW = rightColumnW();
        float boxTop = contentTop(panelY);
        float boxH = GRID_H - 74f - SHARED_BLOCK_H;

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(SLOT_BG);
        shape.rect(boxX, boxTop - boxH, boxW, boxH);
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(SLOT_LINE);
        shape.rect(boxX, boxTop - boxH, boxW, boxH);
        shape.end();
    }

    private void drawPortrait(SpriteBatch batch, float panelX, float panelY, Player player) {
        TextureRegion frame = player.portraitFrame();
        if (frame == null) return;

        float boxX = rightColumnX(panelX);
        float boxW = rightColumnW();
        float boxTop = contentTop(panelY);
        float boxH = GRID_H - 74f - SHARED_BLOCK_H;

        // Keep the sprite's aspect ratio
        float inset = 18f;
        float maxW = boxW - 2 * inset;
        float maxH = boxH - 2 * inset;
        float scale = Math.min(maxW / frame.getRegionWidth(), maxH / frame.getRegionHeight());
        float drawW = frame.getRegionWidth() * scale;
        float drawH = frame.getRegionHeight() * scale;

        batch.draw(frame, boxX + (boxW - drawW) / 2f, boxTop - boxH + (boxH - drawH) / 2f, drawW, drawH);
    }

    private void drawText(SpriteBatch batch, float panelX, float panelY,
                          Inventory inventory, SharedSlot shared, Player player, Color accent) {
        float left = panelX + PAD;
        float right = panelX + PANEL_W - PAD;

        float titleY = panelY + PANEL_H - PAD - 4f;
        font.getData().setScale(TITLE_SCALE);
        font.setColor(TEXT);
        font.draw(batch, "INVENTORY", left + 30f, titleY);

        font.getData().setScale(LABEL_SCALE);
        String tag = "P" + playerSide;
        font.setColor(accent);
        font.draw(batch, tag, right - 210f, titleY - 4f);
        font.setColor(DIM);
        font.draw(batch, "[" + playerSide + "] CLOSE", right - 155f, titleY - 4f);

        // Health bar made of # characters
        float hpY = panelY + PANEL_H - PAD - 62f;
        font.setColor(DIM);
        font.draw(batch, "HP", left, hpY);

        float health = Math.max(0f, player.health);
        float filled = health / Player.MAX_HEALTH * HP_SEGMENTS;
        healthBar.setLength(0);
        for (int i = 0; i < HP_SEGMENTS; i++) healthBar.append(i < filled ? '#' : '-');
        font.setColor(health <= Player.MAX_HEALTH * HP_CRITICAL ? MAGENTA : accent);
        font.draw(batch, healthBar, left + 60f, hpY);

        font.setColor(TEXT);
        font.draw(batch, String.valueOf((int) Math.ceil(health)), left + 340f, hpY);

        float sharedLabelY = sharedSlotY(panelY) + SLOT + 32f;
        font.getData().setScale(BODY_SCALE);
        font.setColor(sharedFocused ? accent : DIM);
        font.draw(batch, "SHARED", rightColumnX(panelX), sharedLabelY,
            rightColumnW(), com.badlogic.gdx.utils.Align.center, false);

        font.setColor(DIM);
        font.draw(batch, inventory.count() + " / " + Inventory.CAPACITY, left + GRID_W - 90f,
            panelY + PAD + 142f);

        InventoryItem focused = sharedFocused ? shared.get() : inventory.getSelected();
        float detailY = panelY + PAD + 74f;
        font.getData().setScale(LABEL_SCALE);
        font.setColor(focused == null ? DIM : ORANGE);
        font.draw(batch, focused == null
            ? (sharedFocused ? "SHARED SLOT - EMPTY" : "EMPTY SLOT")
            : focused.getName().toUpperCase(), left, detailY);

        font.getData().setScale(BODY_SCALE);
        font.setColor(DIM);
        font.draw(batch, focused == null
                ? (sharedFocused ? "Both of you can reach this one." : "Nothing stored here.")
                : focused.getDescription(),
            left, detailY - 34f, PANEL_W - 2 * PAD - 220f, com.badlogic.gdx.utils.Align.left, true);

        // Only show a key prompt when that key does something
        float promptY = detailY;
        font.setColor(DIM);
        font.draw(batch, "[TAB] SHARED", right - 200f, promptY);
        promptY -= 30f;
        if (focused != null && (sharedFocused || focused.isShareable())) {
            font.setColor(accent);
            font.draw(batch, sharedFocused ? "[R] TAKE" : "[R] PUT", right - 200f, promptY);
            promptY -= 30f;
        }
        if (focused != null && focused.isConsumable()) {
            font.setColor(accent);
            font.draw(batch, "[ENTER] USE", right - 200f, promptY);
        }
    }
}
