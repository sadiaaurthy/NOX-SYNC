package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

import io.github.fableops.Role;
import io.github.fableops.level3.PlayerActionType;
import io.github.fableops.level3.TurnManager;
import io.github.fableops.level3.WardenState;
import io.github.fableops.ui.UiViewport;

// The Warden encounter's shared action panel: one joint window between the two split-screen
// halves (not per-half like the inventory or the HUD cards), because choosing actions here is a
// decision the two operators make together, in view of each other. Shows the action list during
// PLAYER_TURN, a short "considers its response" beat during WARDEN_TURN, and the round's result
// during RESOLUTION - plus the Stability meter and (once it matters) the dual-authorization meter
public class TurnPanel {

    private static final float PANEL_W = 900f;
    private static final float TOP_Y = 760f;
    private static final float COLUMN_W = 380f;
    private static final float ROW_H = 34f;

    private static final Color PANEL = new Color(0.03f, 0.04f, 0.05f, 0.85f);
    private static final Color EDGE = new Color(1f, 1f, 1f, 0.1f);
    private static final Color TRACK = new Color(0.15f, 0.15f, 0.17f, 1f);
    private static final Color TEXT = new Color(0.94f, 0.94f, 0.91f, 1f);
    private static final Color DIM = new Color(0.6f, 0.6f, 0.58f, 1f);
    private static final Color CYAN = new Color(0.35f, 0.92f, 1f, 1f);
    private static final Color MAGENTA = new Color(1f, 0.16f, 0.43f, 1f);
    private static final Color GREEN = new Color(0.45f, 0.95f, 0.55f, 1f);
    private static final Color STABILITY = new Color(0.35f, 0.92f, 1f, 1f);
    private static final Color DUAL = new Color(1f, 0.7f, 0.25f, 1f);

    private final BitmapFont font;

    public TurnPanel(BitmapFont font) {
        this.font = font;
    }

    public void render(ShapeRenderer shape, SpriteBatch batch, UiViewport ui, TurnManager.Phase phase,
                       WardenState state, float stability, float dualMeter, Role sideOneRole,
                       String p1CallSign, String p2CallSign, int p1Selected, int p2Selected,
                       boolean p1Confirmed, boolean p2Confirmed, boolean p1HasItem, boolean p2HasItem,
                       String wardenLine, String breakerLine, String listenerLine) {
        float x = (ui.width() - PANEL_W) / 2f;
        float y = TOP_Y - 220f;
        float h = 220f;

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, PANEL_W, h);
        shape.setColor(EDGE);
        shape.rect(x, y + h - 1f, PANEL_W, 1f);
        shape.end();

        drawMeters(shape, x, y + h - 14f, state, stability, dualMeter);
        blend(false);

        boolean p1IsBreaker = sideOneRole == Role.BREAKER;
        String breakerCallSign = p1IsBreaker ? p1CallSign : p2CallSign;
        String listenerCallSign = p1IsBreaker ? p2CallSign : p1CallSign;
        int breakerSelected = p1IsBreaker ? p1Selected : p2Selected;
        int listenerSelected = p1IsBreaker ? p2Selected : p1Selected;
        boolean breakerConfirmed = p1IsBreaker ? p1Confirmed : p2Confirmed;
        boolean listenerConfirmed = p1IsBreaker ? p2Confirmed : p1Confirmed;
        boolean breakerHasItem = p1IsBreaker ? p1HasItem : p2HasItem;
        boolean listenerHasItem = p1IsBreaker ? p2HasItem : p1HasItem;

        batch.begin();
        switch (phase) {
            case PLAYER_TURN:
                drawColumn(batch, x + 20f, y + h - 40f, breakerCallSign, PlayerActionType.optionsFor(Role.BREAKER),
                    breakerSelected, breakerConfirmed, breakerHasItem);
                drawColumn(batch, x + PANEL_W / 2f + 20f, y + h - 40f, listenerCallSign,
                    PlayerActionType.optionsFor(Role.LISTENER), listenerSelected, listenerConfirmed, listenerHasItem);
                break;
            case WARDEN_TURN:
                font.getData().setScale(1.6f);
                font.setColor(MAGENTA);
                font.draw(batch, "THE WARDEN CONSIDERS ITS RESPONSE...", x, y + h / 2f + 10f, PANEL_W, Align.center, false);
                break;
            case RESOLUTION:
                float lineY = y + h - 40f;
                font.getData().setScale(1.3f);
                font.setColor(CYAN);
                lineY -= font.draw(batch, wardenLine, x + 20f, lineY, PANEL_W - 40f, Align.left, true).height + 16f;
                font.setColor(TEXT);
                lineY -= font.draw(batch, breakerLine, x + 20f, lineY, PANEL_W - 40f, Align.left, true).height + 10f;
                font.draw(batch, listenerLine, x + 20f, lineY, PANEL_W - 40f, Align.left, true);
                break;
            default:
                break;
        }
        batch.end();
    }

    private void drawColumn(SpriteBatch batch, float x, float topY, String callSign, PlayerActionType[] fixed,
                            int selected, boolean confirmed, boolean hasItem) {
        int count = hasItem ? fixed.length + 1 : fixed.length;

        font.getData().setScale(1.4f);
        font.setColor(TEXT);
        font.draw(batch, callSign, x, topY);
        if (confirmed) {
            font.setColor(GREEN);
            font.draw(batch, "READY", x + COLUMN_W - 90f, topY);
        }

        float rowY = topY - ROW_H;
        font.getData().setScale(1.15f);
        for (int i = 0; i < count; i++) {
            String label = (i < fixed.length) ? fixed[i].label() : "Use Item";
            boolean isSelected = (i == selected);
            font.setColor(confirmed ? DIM : (isSelected ? CYAN : DIM));
            String prefix = (!confirmed && isSelected) ? "> " : "   ";
            font.draw(batch, prefix + label, x, rowY);
            rowY -= ROW_H;
        }
    }

    private void drawMeters(ShapeRenderer shape, float x, float y, WardenState state, float stability, float dualMeter) {
        float meterW = 340f;
        float meterH = 10f;

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(TRACK);
        shape.rect(x + 20f, y - meterH, meterW, meterH);
        shape.setColor(STABILITY);
        shape.rect(x + 20f, y - meterH, meterW * Math.min(1f, stability / 100f), meterH);

        if (state.ordinal() >= WardenState.DIRECTIVE_CONFLICT.ordinal() && !state.isStoodDown()) {
            shape.setColor(TRACK);
            shape.rect(x + 20f + meterW + 40f, y - meterH, meterW, meterH);
            shape.setColor(DUAL);
            shape.rect(x + 20f + meterW + 40f, y - meterH, meterW * Math.min(1f, dualMeter / 100f), meterH);
        }
        shape.end();
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
