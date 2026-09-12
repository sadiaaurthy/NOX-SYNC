package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;
import io.github.fableops.inventory.Inventory;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.SharedSlot;

/**
 * The in-game inventory panel — one universal 5x5 grid, the carrying player drawn beside
 * it, opened with that player's own number key.
 *
 * Rendered over the opening player's split-screen half rather than the whole window, the
 * same way {@link CodePopupUI} is. A full-screen panel would blank out the partner's view
 * while enemies are still chasing them, which in a co-op game is a way to get someone
 * killed by the menu.
 *
 * Everything below is in the caller's virtual UI space (see Level1Screen's UI_REF_W/H), so
 * the panel holds its proportions on any resolution. Text uses the bitmap pixel font in
 * assets/pixel.fnt with a Nearest filter — the whole point of a bitmap font here is that
 * upscaling it keeps hard pixel edges instead of smearing them, which is what gives the
 * panel its look.
 */
public class InventoryUI {

    // Panel box, virtual units. Sized to sit comfortably inside one 960-wide half.
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

    // Same palette as Level1Screen and launcher.css, so the panel reads as one game.
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
    /** Reused by the health bar so a full inventory frame allocates nothing. */
    private final StringBuilder healthBar = new StringBuilder(HP_SEGMENTS);

    private final BitmapFont font;

    private boolean open = false;
    /** 1 = draw over the left half, 2 = the right half. Set when the panel opens. */
    private int playerSide = 1;
    /** Whether the cursor is on the shared slot rather than in the personal grid. */
    private boolean sharedFocused = false;

    // Right-hand column: portrait above, the shared slot beneath it.
    private static final float SHARED_BLOCK_H = 110f;

    public InventoryUI() {
        font = new BitmapFont(Gdx.files.internal("pixel.fnt"), false);
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        font.setUseIntegerPositions(false);
    }

    public boolean isOpen() { return open; }

    public void close() { open = false; }

    /** Opens over that player's half, or closes if it was already showing. */
    public void toggle(int playerSide) {
        if (open) {
            open = false;
            return;
        }
        this.playerSide = playerSide;
        this.open = true;
        // Always reopen on the grid — leaving focus parked on the shared slot from last
        // time makes the arrow keys look broken when the panel comes back up.
        this.sharedFocused = false;
    }

    /**
     * Cursor movement, and ESC to dismiss. Takes the movement keys of whichever player
     * owns this panel: that player is frozen while it is open, so reusing their own
     * movement keys costs nothing and needs no second key map to learn.
     *
     * Deliberately does NOT handle the open/close key. isKeyJustPressed() reports true for
     * the whole frame rather than being consumed by the first reader, so when the caller
     * toggled the panel open and this method then checked the same key, it saw that press
     * too and closed the panel again before a single frame had drawn. Toggling has exactly
     * one owner now — the caller.
     */
    public void handleInput(Inventory inventory, SharedSlot shared, Player owner,
                            int keyUp, int keyDown, int keyLeft, int keyRight) {
        if (!open) return;

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            open = false;
            return;
        }
        // TAB moves the cursor between the personal grid and the shared slot. A dedicated
        // key rather than an extra grid cell, because the shared slot is not part of the
        // 5x5 layout and arrowing into it from an arbitrary edge has no sensible geometry.
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) sharedFocused = !sharedFocused;

        if (!sharedFocused) {
            if (Gdx.input.isKeyJustPressed(keyLeft))  inventory.moveSelection(-1, 0);
            if (Gdx.input.isKeyJustPressed(keyRight)) inventory.moveSelection(1, 0);
            // Grid row 0 is the top row, so "up" walks toward lower row indices.
            if (Gdx.input.isKeyJustPressed(keyUp))    inventory.moveSelection(0, -1);
            if (Gdx.input.isKeyJustPressed(keyDown))  inventory.moveSelection(0, 1);
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) use(inventory, shared, owner);
        // R, not F: F is Player 1's attack, and in Debug only one player is frozen at a
        // time, so a panel reading F would swap items every time the other player swung.
        if (Gdx.input.isKeyJustPressed(Input.Keys.R)) transfer(inventory, shared);
    }

    /**
     * Consumes the focused item if it does anything. A consumable is spent whether or not
     * it was needed — no "already at full health" refusal, because silently doing nothing
     * on a keypress reads as a broken button.
     */
    private void use(Inventory inventory, SharedSlot shared, Player owner) {
        InventoryItem item = sharedFocused ? shared.get() : inventory.getSelected();
        if (item == null || !item.isConsumable()) return;
        owner.heal(item.getHealAmount());
        if (sharedFocused) shared.clear();
        else inventory.remove(inventory.getSelectedIndex());
    }

    /**
     * Moves the focused item between this player's grid and the shared slot — the whole
     * point of the shared slot being to hand things over.
     *
     * A swap rather than a move, so pushing into an occupied shared slot doesn't silently
     * destroy whatever was already there. Pulling out into a full grid is refused instead,
     * since there is nowhere for the displaced item to go.
     */
    private void transfer(Inventory inventory, SharedSlot shared) {
        if (sharedFocused) {
            InventoryItem incoming = shared.get();
            if (incoming == null) return;
            if (!inventory.add(incoming)) return; // grid full — leave it where it is
            shared.clear();
        } else {
            int index = inventory.getSelectedIndex();
            InventoryItem outgoing = inventory.get(index);
            if (outgoing == null) return;
            inventory.set(index, shared.put(outgoing));
        }
    }

    /**
     * @param accent    that player's colour — cyan for P1, magenta for P2
     * @param uiWorldW  virtual width of the caller's UI space
     * @param uiWorldH  virtual height of the caller's UI space
     */
    public void render(ShapeRenderer shape, SpriteBatch batch, float uiWorldW, float uiWorldH,
                       Inventory inventory, SharedSlot shared, Player player, Color accent) {
        if (!open) return;

        float halfW = uiWorldW / 2f;
        float panelX = (playerSide == 1 ? 0f : halfW) + (halfW - PANEL_W) / 2f;
        float panelY = (uiWorldH - PANEL_H) / 2f;

        // ShapeRenderer does not manage blending; without this the dimmed backdrop and the
        // panel's own alpha render as solid blocks wherever a previous batch left it off.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        drawChrome(shape, panelX, panelY, uiWorldW, uiWorldH, accent);
        drawSlots(shape, panelX, panelY, inventory, accent);
        drawPortraitFrame(shape, panelX, panelY);
        drawSharedSlot(shape, panelX, panelY, shared, accent);
        drawText(batch, panelX, panelY, inventory, shared, player, accent);
        drawPortrait(batch, panelX, panelY, player);

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Dimmed backdrop over this half, then the double-bordered panel box. */
    private void drawChrome(ShapeRenderer shape, float panelX, float panelY,
                            float uiWorldW, float uiWorldH, Color accent) {
        float halfW = uiWorldW / 2f;
        float halfX = (playerSide == 1) ? 0f : halfW;

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0f, 0f, 0f, 0.55f);
        shape.rect(halfX, 0f, halfW, uiWorldH);

        // Outer border is the player's accent, so a glance says whose panel this is.
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

        // Two accent ticks beside the title — the same mark the launcher uses as its logo.
        shape.setColor(accent.r, accent.g, accent.b, 1f);
        float tickY = panelY + PANEL_H - PAD - 30f;
        shape.rect(panelX + PAD, tickY, 7f, 26f);
        shape.rect(panelX + PAD + 12f, tickY, 7f, 26f);
        shape.end();
    }

    private void drawSlots(ShapeRenderer shape, float panelX, float panelY,
                           Inventory inventory, Color accent) {
        float gridX = panelX + PAD;
        float gridTop = contentTop(panelY);

        shape.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            float sx = gridX + (i % Inventory.COLUMNS) * (SLOT + SLOT_GAP);
            float sy = gridTop - (i / Inventory.COLUMNS + 1) * SLOT - (i / Inventory.COLUMNS) * SLOT_GAP;
            shape.setColor(SLOT_BG);
            shape.rect(sx, sy, SLOT, SLOT);

            InventoryItem item = inventory.get(i);
            if (item != null) {
                // Blocky stand-in icon: there is no item art yet, so an item reads as its
                // accent colour here and is named in the detail strip below.
                Color c = item.getAccent();
                shape.setColor(c.r, c.g, c.b, 1f);
                shape.rect(sx + 16f, sy + 16f, SLOT - 32f, SLOT - 32f);
                shape.setColor(0f, 0f, 0f, 1f);
                shape.rect(sx + 26f, sy + 26f, SLOT - 52f, SLOT - 52f);
            }
        }
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            float sx = gridX + (i % Inventory.COLUMNS) * (SLOT + SLOT_GAP);
            float sy = gridTop - (i / Inventory.COLUMNS + 1) * SLOT - (i / Inventory.COLUMNS) * SLOT_GAP;
            boolean selected = (i == inventory.getSelectedIndex());
            shape.setColor(selected ? accent : SLOT_LINE);
            shape.rect(sx, sy, SLOT, SLOT);
            // The cursor gets a second inset outline so it stays obvious on a filled slot.
            if (selected) shape.rect(sx + 3f, sy + 3f, SLOT - 6f, SLOT - 6f);
        }
        shape.end();
    }

    /** Left edge and width of the right-hand column, shared by the portrait and the slot. */
    private static float rightColumnX(float panelX) { return panelX + PAD + GRID_W + 24f; }

    private static float rightColumnW() { return PANEL_W - 2 * PAD - GRID_W - 24f; }

    /** Top of the grid, which the right-hand column aligns to. */
    private static float contentTop(float panelY) { return panelY + PANEL_H - PAD - 124f; }

    /** Bottom edge of the shared slot — it sits level with the bottom row of the grid. */
    private static float sharedSlotY(float panelY) { return contentTop(panelY) - GRID_H + 10f; }

    /**
     * The shared slot, drawn under the portrait and set apart from the 5x5 grid on purpose
     * — it is not one of this player's pockets, and putting it inside the grid would read
     * as though it were.
     */
    private void drawSharedSlot(ShapeRenderer shape, float panelX, float panelY,
                                SharedSlot shared, Color accent) {
        float slotX = rightColumnX(panelX) + (rightColumnW() - SLOT) / 2f;
        float slotY = sharedSlotY(panelY);

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(SLOT_BG);
        shape.rect(slotX, slotY, SLOT, SLOT);
        InventoryItem item = shared.get();
        if (item != null) {
            Color c = item.getAccent();
            shape.setColor(c.r, c.g, c.b, 1f);
            shape.rect(slotX + 16f, slotY + 16f, SLOT - 32f, SLOT - 32f);
            shape.setColor(0f, 0f, 0f, 1f);
            shape.rect(slotX + 26f, slotY + 26f, SLOT - 52f, SLOT - 52f);
        }
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(sharedFocused ? accent : SLOT_LINE);
        shape.rect(slotX, slotY, SLOT, SLOT);
        if (sharedFocused) shape.rect(slotX + 3f, slotY + 3f, SLOT - 6f, SLOT - 6f);
        shape.end();
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

        // Fit inside the box preserving the frame's own aspect, so the character is never
        // stretched however the sheet's cells are proportioned.
        float inset = 18f;
        float maxW = boxW - 2 * inset;
        float maxH = boxH - 2 * inset;
        float scale = Math.min(maxW / frame.getRegionWidth(), maxH / frame.getRegionHeight());
        float drawW = frame.getRegionWidth() * scale;
        float drawH = frame.getRegionHeight() * scale;

        batch.begin();
        batch.draw(frame, boxX + (boxW - drawW) / 2f, boxTop - boxH + (boxH - drawH) / 2f, drawW, drawH);
        batch.end();
    }

    private void drawText(SpriteBatch batch, float panelX, float panelY,
                          Inventory inventory, SharedSlot shared, Player player, Color accent) {
        float left = panelX + PAD;
        float right = panelX + PANEL_W - PAD;

        batch.begin();

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

        // Health, as a segmented bar — reads at a glance, and drawn with the font rather
        // than ShapeRenderer so its blocks land on the same pixel rhythm as the glyphs.
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

        // "SHARED" sits directly above the shared slot, labelling it as the pair's, not
        // this player's — the count below covers the personal grid only. Positioned off
        // the slot itself rather than re-deriving the offset, which previously left about
        // one unit of clearance between the text and the slot's top edge.
        float sharedLabelY = sharedSlotY(panelY) + SLOT + 32f;
        font.getData().setScale(BODY_SCALE);
        font.setColor(sharedFocused ? accent : DIM);
        font.draw(batch, "SHARED", rightColumnX(panelX), sharedLabelY,
            rightColumnW(), com.badlogic.gdx.utils.Align.center, false);

        font.setColor(DIM);
        font.draw(batch, inventory.count() + " / " + Inventory.CAPACITY, left + GRID_W - 90f,
            panelY + PAD + 142f);

        // Detail strip follows the cursor, whichever side of TAB it is on.
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

        // Prompts only appear when the key would actually do something — a hint on an
        // inert item or an empty slot is a lie the player only discovers by pressing it.
        float promptY = detailY;
        font.setColor(DIM);
        font.draw(batch, "[TAB] SHARED", right - 200f, promptY);
        promptY -= 30f;
        if (focused != null) {
            font.setColor(accent);
            font.draw(batch, sharedFocused ? "[R] TAKE" : "[R] PUT", right - 200f, promptY);
            promptY -= 30f;
        }
        if (focused != null && focused.isConsumable()) {
            font.setColor(accent);
            font.draw(batch, "[ENTER] USE", right - 200f, promptY);
        }

        batch.end();
    }

    public void dispose() {
        font.dispose();
    }
}
