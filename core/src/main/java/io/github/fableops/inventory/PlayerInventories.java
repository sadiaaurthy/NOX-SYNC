package io.github.fableops.inventory;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;
import io.github.fableops.ui.hud.InventoryUI;

/**
 * Both players' inventories and their panels, with all the key handling in one place.
 *
 * Level screens own one of these and delegate, rather than each level repeating the same
 * two inventories, two panels, key routing and freeze rules. Level 1 and Level 2 both use
 * it unchanged; what differs between them is only which items get put in.
 *
 * Key ownership: a player may only open their own panel, so the host (who is Player 1
 * locally) listens for 1 and the client (Player 2) listens for 2. Debug mode drives both
 * players from one keyboard, so there both keys are live.
 */
public class PlayerInventories {

    private final Inventory inventoryP1 = new Inventory();
    private final Inventory inventoryP2 = new Inventory();
    /** One item, held by the pair rather than by either player. Both panels show it. */
    private final SharedSlot shared = new SharedSlot();
    private final InventoryUI uiP1 = new InventoryUI();
    private final InventoryUI uiP2 = new InventoryUI();

    public Inventory forPlayer(int side) {
        return (side == 1) ? inventoryP1 : inventoryP2;
    }

    public SharedSlot shared() { return shared; }

    /** True while that player's panel is up — screens use this to freeze only that player. */
    public boolean isOpen(int side) {
        return (side == 1) ? uiP1.isOpen() : uiP2.isOpen();
    }

    public boolean anyOpen() {
        return uiP1.isOpen() || uiP2.isOpen();
    }

    public void closeAll() {
        uiP1.close();
        uiP2.close();
    }

    /**
     * @param localIsP1 this machine drives Player 1 (host, or debug)
     * @param localIsP2 this machine drives Player 2 (client, or debug)
     * @param blocked   something else owns the keyboard — a terminal popup or a
     *                  mission-failed banner — so the panels must stay shut
     *
     * Toggling lives here and nowhere else. isKeyJustPressed() reports true for the whole
     * frame instead of being consumed by the first reader, so a second check of the same
     * key further down the frame would see the same press and undo this one.
     */
    public void handleInput(boolean localIsP1, boolean localIsP2, boolean blocked,
                            Player player1, Player player2) {
        if (blocked) {
            closeAll();
        } else {
            // Only one panel at a time. In Debug both players share a keyboard, so two open
            // panels would both read TAB, ENTER and R and act on them twice. In networked
            // play each machine only ever has its own panel, so this changes nothing there.
            if (localIsP1 && Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) {
                uiP2.close();
                uiP1.toggle(1);
            }
            if (localIsP2 && Gdx.input.isKeyJustPressed(Input.Keys.NUM_2)) {
                uiP1.close();
                uiP2.toggle(2);
            }
        }

        // Each panel navigates with its own player's movement keys — that player is frozen
        // while it is open, so the keys are free and there is no second mapping to learn.
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
        uiP1.dispose();
        uiP2.dispose();
    }
}
