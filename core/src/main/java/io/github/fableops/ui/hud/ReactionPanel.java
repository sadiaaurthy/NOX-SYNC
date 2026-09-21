package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

import io.github.fableops.level3.ReactionType;
import io.github.fableops.ui.UiViewport;

// Visual-only reaction selector. Inventory validation and all effects remain authoritative in
// Level3Controller; this panel only reports which choices are currently usable.
public final class ReactionPanel {

    private static final float PANEL_W = 430f;
    private static final float PANEL_H = 312f;
    private static final float ROW_H = 46f;
    private static final float PAD = 18f;
    private static final float EDGE_MARGIN = 24f;

    private static final Color PANEL = new Color(0.025f, 0.03f, 0.04f, 0.97f);
    private static final Color ROW = new Color(0.08f, 0.09f, 0.11f, 0.98f);
    private static final Color SELECTED = new Color(0.18f, 0.08f, 0.10f, 0.98f);
    private static final Color DISABLED = new Color(0.05f, 0.05f, 0.06f, 0.92f);
    private static final Color ALERT = new Color(1f, 0.22f, 0.25f, 1f);
    private static final Color CYAN = new Color(0.35f, 0.92f, 1f, 1f);
    private static final Color TEXT = new Color(0.95f, 0.95f, 0.92f, 1f);
    private static final Color DIM = new Color(0.52f, 0.52f, 0.52f, 1f);

    private final BitmapFont font;

    public ReactionPanel(BitmapFont font) {
        this.font = font;
    }

    public void render(ShapeRenderer shape, SpriteBatch batch, UiViewport ui, int playerSide,
                       String targetName, String roleName, String attackerName, int selected,
                       boolean[] available) {
        float x = playerSide == 1 ? EDGE_MARGIN : ui.width() - EDGE_MARGIN - PANEL_W;
        float y = EDGE_MARGIN;

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, PANEL_W, PANEL_H);
        shape.setColor(ALERT);
        shape.rect(x, y + PANEL_H - 5f, PANEL_W, 5f);
        for (int i = 0; i < ReactionType.values().length; i++) {
            float rowY = y + 49f + (ReactionType.values().length - 1 - i) * ROW_H;
            shape.setColor(!available[i] ? DISABLED : i == selected ? SELECTED : ROW);
            shape.rect(x + PAD, rowY, PANEL_W - PAD * 2f, ROW_H - 7f);
        }
        shape.end();
        blend(false);

        batch.begin();
        font.getData().setScale(1.0f);
        font.setColor(ALERT);
        font.draw(batch, "PLAYER " + playerSide + " - " + targetName + " / " + roleName,
            x + PAD, y + PANEL_H - 21f, PANEL_W - PAD * 2f, Align.center, false);
        font.getData().setScale(0.75f);
        font.setColor(TEXT);
        font.draw(batch, attackerName + " TARGETING " + targetName, x + PAD,
            y + PANEL_H - 48f, PANEL_W - PAD * 2f, Align.center, false);

        String[] keys = {"ENTER", "X", "H", "M", "Y"};
        ReactionType[] types = ReactionType.values();
        for (int i = 0; i < types.length; i++) {
            float rowY = y + 49f + (types.length - 1 - i) * ROW_H;
            font.getData().setScale(0.82f);
            font.setColor(available[i] ? (i == selected ? ALERT : TEXT) : DIM);
            String suffix = available[i] ? "" : "  [UNAVAILABLE]";
            font.draw(batch, "[" + keys[i] + "]  " + types[i].label() + suffix,
                x + PAD + 14f, rowY + 30f);
        }

        font.getData().setScale(0.72f);
        font.setColor(DIM);
        font.draw(batch, "UP/DOWN OR W/D  SELECT     ENTER  CONFIRM",
            x + PAD, y + 20f, PANEL_W - PAD * 2f, Align.center, false);
        batch.end();
    }

    // Compact replacement for the automatic reaction popup. It keeps the existing ReactionPanel
    // component and authoritative reaction choices, but leaves the battlefield unobstructed.
    public void renderActionHints(SpriteBatch batch, UiViewport ui, int playerSide,
                                  String targetName, String attackerName, boolean[] available) {
        float halfW = ui.width() / 2f;
        float x = playerSide == 1 ? EDGE_MARGIN : halfW + EDGE_MARGIN;
        float width = halfW - EDGE_MARGIN * 2f;
        float y = 116f;

        batch.begin();
        font.getData().setScale(0.83f);
        font.setColor(CYAN);
        font.draw(batch, attackerName + " TARGETING " + targetName, x, y + 84f,
            width, playerSide == 1 ? Align.left : Align.right, false);

        StringBuilder hints = new StringBuilder();
        appendHint(hints, available[ReactionType.SIDEARM.ordinal()], "[X] FIRE SIDE ARM");
        appendHint(hints, available[ReactionType.SHIELD.ordinal()], "[H] ACTIVATE SHIELD");
        appendHint(hints, available[ReactionType.MEDKIT.ordinal()], "[M] USE MEDKIT");
        font.getData().setScale(0.76f);
        font.draw(batch, hints, x, y + 48f, width,
            playerSide == 1 ? Align.left : Align.right, true);
        batch.end();
    }

    public void renderActivationHeader(SpriteBatch batch, UiViewport ui, String instruction) {
        batch.begin();
        font.getData().setScale(1.55f);
        font.setColor(CYAN);
        font.draw(batch, instruction, 0f, ui.height() - 122f, ui.width(), Align.center, false);
        batch.end();
    }

    private static void appendHint(StringBuilder text, boolean enabled, String hint) {
        if (!enabled) return;
        if (text.length() > 0) text.append("    ");
        text.append(hint);
    }

    private static void blend(boolean enabled) {
        if (enabled) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }
}
