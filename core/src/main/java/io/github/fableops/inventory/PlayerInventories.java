package io.github.fableops.inventory;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;
import io.github.fableops.ui.hud.InventoryUI;

// Both inventories and panels. The host opens with 1, the client with 2, debug can use both
public class PlayerInventories {

    private final Inventory inventoryP1 = new Inventory();
    private final Inventory inventoryP2 = new Inventory();
    private final SharedSlot shared = new SharedSlot();
    // Shared by both panels
    private final BitmapFont font = new BitmapFont(Gdx.files.internal("pixel.fnt"));
    private final InventoryUI uiP1;
    private final InventoryUI uiP2;

    public PlayerInventories() {
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        font.setUseIntegerPositions(false);
        uiP1 = new InventoryUI(font);
        uiP2 = new InventoryUI(font);
    }

    public boolean isOpen(int side) {
        return (side == 1) ? uiP1.isOpen() : uiP2.isOpen();
    }

    public boolean anyOpen() {
        return uiP1.isOpen() || uiP2.isOpen();
    }

    // blocked means a terminal popup is using the keyboard
    // The open key is only checked here, isKeyJustPressed stays true for the whole frame
    public void handleInput(boolean localIsP1, boolean localIsP2, boolean blocked,
                            Player player1, Player player2) {
        if (blocked) {
            uiP1.close();
            uiP2.close();
        } else {
            // Only one panel at a time, debug mode shares one keyboard
            if (localIsP1 && Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) {
                uiP2.close();
                uiP1.toggle(1);
            }
            if (localIsP2 && Gdx.input.isKeyJustPressed(Input.Keys.NUM_2)) {
                uiP1.close();
                uiP2.toggle(2);
            }
        }

        // Each panel uses its owner's movement keys, that player can't move while it's open
        uiP1.handleInput(inventoryP1, shared, player1, Input.Keys.W, Input.Keys.S,
            Input.Keys.A, Input.Keys.D);
        uiP2.handleInput(inventoryP2, shared, player2, Input.Keys.UP, Input.Keys.DOWN,
            Input.Keys.LEFT, Input.Keys.RIGHT);
    }

    public void render(ShapeRenderer shape, SpriteBatch batch, float uiWorldW, float uiWorldH,
                       Player player1, Player player2, Color accentP1, Color accentP2) {
        uiP1.render(shape, batch, uiWorldW, uiWorldH, inventoryP1, shared, player1, accentP1);
        uiP2.render(shape, batch, uiWorldW, uiWorldH, inventoryP2, shared, player2, accentP2);
    }

    public void dispose() {
        font.dispose();
    }
}
