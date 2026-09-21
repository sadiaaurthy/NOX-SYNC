package io.github.fableops.level2;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level2.network.GunStateMessage;

// One sidearm per operator. Level 2 drops a weapon for each of them, so ammo, reload timers and
// the ammo-cache bonus are tracked per side rather than on a single shared weapon.
//
// A side's weapon is live only while that side carries a "Sidearm" item, so the existing
// inventory rules still decide who is armed - including handing one over through the shared slot
public class Sidearms {

    public static final String ITEM_NAME = "Sidearm";

    private final Gun p1 = new Gun(1);
    private final Gun p2 = new Gun(2);

    public Gun forSide(int side) {
        return (side == 2) ? p2 : p1;
    }

    public void update(float delta) {
        p1.update(delta);
        p2.update(delta);
    }

    public void reset() {
        p1.reset();
        p2.reset();
    }

    // Re-reads both inventories and arms or disarms each side to match. Called after any pickup or
    // shared-slot transfer, so ownership never drifts from what the players are actually carrying
    public void syncOwnership(PlayerInventories inventories) {
        p1.giveTo(inventories.holds(1, ITEM_NAME) ? 1 : 0);
        p2.giveTo(inventories.holds(2, ITEM_NAME) ? 2 : 0);
    }

    // Client side: the message carries its own side, so it always reaches the right weapon
    public void apply(GunStateMessage state) {
        forSide(state.getSide()).apply(state);
    }

    public boolean hasShotToDraw() {
        return p1.hasShotToDraw() || p2.hasShotToDraw();
    }

    // Call inside a filled ShapeRenderer pass with blending on
    public void drawShots(ShapeRenderer shape) {
        if (p1.hasShotToDraw()) p1.drawShot(shape);
        if (p2.hasShotToDraw()) p2.drawShot(shape);
    }

    // Each weapon draws in its own half, and an unheld one draws nothing
    public void drawHuds(ShapeRenderer shape, SpriteBatch batch, BitmapFont font, float uiWidth) {
        p1.drawHud(shape, batch, font, uiWidth);
        p2.drawHud(shape, batch, font, uiWidth);
    }
}
