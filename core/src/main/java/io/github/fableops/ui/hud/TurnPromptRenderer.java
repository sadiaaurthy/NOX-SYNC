package io.github.fableops.ui.hud;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.ui.UiViewport;

// The one place Level 3 shows the turn menu reminder. It goes through Hud.drawBanner's prompt slot,
// exactly like Level 2's prompts: large cyan text, centred at the top, under the objective. Nothing
// else in the project may draw "PRESS T TO OPEN TURN MENU", and it is never drawn near a corner,
// a player card, an inventory panel or the combat area.
public final class TurnPromptRenderer {

    public static final String TEXT = "PRESS T TO OPEN TURN MENU";
    // Second cyan line, same block: shown whenever an active operator carries TNT, whatever else is
    // going on. Also the header text once TNT has been picked from the inventory.
    public static final String TNT_TEXT = "PRESS Y TO THROW TNT";
    // Crimson second line under the cyan one, teaching the order: Recover Memory, then Authorization.
    public static final String HINT_RECOVER_MEMORY = "CHOOSE RECOVER MEMORY";
    public static final String HINT_RESTORE_AUTHORIZATION = "CHOOSE RESTORE AUTHORIZATION";

    private TurnPromptRenderer() {}

    // visible is false while the Turn Menu is open (or T cannot open it), so that reminder is gone
    // only then and comes straight back once the menu closes. The TNT line is independent of it: it
    // is a plain instruction that follows carried TNT alone, so it stays up when T cannot open.
    // hint is null when there is no guidance to give; it only draws with the T line above it.
    public static void render(Hud hud, ShapeRenderer shape, SpriteBatch batch, UiViewport ui,
                              String title, String objective, boolean visible, boolean tnt,
                              String hint) {
        String prompt = visible ? (tnt ? TEXT + "\n" + TNT_TEXT : TEXT) : (tnt ? TNT_TEXT : null);
        hud.drawBanner(shape, batch, ui, title, objective, prompt, visible ? hint : null, -1f);
    }
}
