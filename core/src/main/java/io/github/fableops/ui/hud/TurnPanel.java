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
// PLAYER_TURN, the operators' RESOLUTION, and the Warden's defensive response. All three narrative
// meters stay visible so progression never reads like hidden boss health.
public class TurnPanel {

    private static final float PANEL_W = 900f;
    private static final float PANEL_H = 330f;
    private static final float TOP_Y = 840f;
    private static final float COLUMN_W = 380f;
    private static final float ROW_H = 29f;

    private static final Color PANEL = new Color(0.03f, 0.04f, 0.05f, 0.85f);
    private static final Color EDGE = new Color(1f, 1f, 1f, 0.1f);
    private static final Color TRACK = new Color(0.15f, 0.15f, 0.17f, 1f);
    private static final Color TEXT = new Color(0.94f, 0.94f, 0.91f, 1f);
    private static final Color DIM = new Color(0.6f, 0.6f, 0.58f, 1f);
    private static final Color CYAN = new Color(0.35f, 0.92f, 1f, 1f);
    private static final Color MAGENTA = new Color(1f, 0.16f, 0.43f, 1f);
    private static final Color GREEN = new Color(0.45f, 0.95f, 0.55f, 1f);
    private static final Color STABILITY = new Color(0.35f, 0.92f, 1f, 1f);
    private static final Color CONFLICT = new Color(1f, 0.16f, 0.43f, 1f);
    private static final Color DUAL = new Color(1f, 0.7f, 0.25f, 1f);

    private final BitmapFont font;

    public TurnPanel(BitmapFont font) {
        this.font = font;
    }

    public void render(ShapeRenderer shape, SpriteBatch batch, UiViewport ui, TurnManager.Phase phase,
                       WardenState state, float stability, float directiveConflict, float dualMeter,
                       Role sideOneRole,
                       String p1CallSign, String p2CallSign, int p1Selected, int p2Selected,
                       boolean p1Confirmed, boolean p2Confirmed,
                       PlayerActionType[] p1Actions, PlayerActionType[] p2Actions,
                       int activeMenuSide, boolean soloControl,
                       String wardenLine, String breakerLine, String listenerLine) {
        if (phase != TurnManager.Phase.PLAYER_TURN) {
            renderCompact(shape, batch, ui, phase, wardenLine, breakerLine, listenerLine);
            return;
        }
        float x = (ui.width() - PANEL_W) / 2f;
        float y = TOP_Y - PANEL_H;

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, PANEL_W, PANEL_H);
        shape.setColor(EDGE);
        shape.rect(x, y + PANEL_H - 1f, PANEL_W, 1f);
        shape.end();

        drawMeters(shape, x, y + PANEL_H - 51f, stability, directiveConflict, dualMeter);
        blend(false);

        boolean p1IsBreaker = sideOneRole == Role.BREAKER;
        String breakerCallSign = p1IsBreaker ? p1CallSign : p2CallSign;
        String listenerCallSign = p1IsBreaker ? p2CallSign : p1CallSign;
        int breakerSelected = p1IsBreaker ? p1Selected : p2Selected;
        int listenerSelected = p1IsBreaker ? p2Selected : p1Selected;
        boolean breakerConfirmed = p1IsBreaker ? p1Confirmed : p2Confirmed;
        boolean listenerConfirmed = p1IsBreaker ? p2Confirmed : p1Confirmed;
        PlayerActionType[] breakerActions = p1IsBreaker ? p1Actions : p2Actions;
        PlayerActionType[] listenerActions = p1IsBreaker ? p2Actions : p1Actions;
        boolean breakerActive = p1IsBreaker ? activeMenuSide == 1 : activeMenuSide == 2;
        boolean listenerActive = p1IsBreaker ? activeMenuSide == 2 : activeMenuSide == 1;

        batch.begin();
        font.getData().setScale(0.78f);
        font.setColor(TEXT);
        font.draw(batch, "WARDEN STATUS: " + state.name().replace('_', ' '), x + 20f, y + PANEL_H - 13f);
        font.setColor(phase == TurnManager.Phase.WARDEN_TURN ? MAGENTA : CYAN);
        font.draw(batch, phaseLabel(phase, sideOneRole, activeMenuSide, p1Confirmed, p2Confirmed),
            x + 390f, y + PANEL_H - 13f, PANEL_W - 410f, Align.right, false);
        font.setColor(DIM);
        font.getData().setScale(0.65f);
        font.draw(batch, "CONTAINMENT STABILITY (RESTORE)", x + 20f, y + PANEL_H - 34f);
        font.draw(batch, "DIRECTIVE CONFLICT (REDUCE)", x + 310f, y + PANEL_H - 34f);
        font.draw(batch, "AUTHORIZATION PROGRESS (BUILD)", x + 600f, y + PANEL_H - 34f);

        switch (phase) {
            case PLAYER_TURN: {
                drawColumn(batch, x + 20f, y + PANEL_H - 82f, "BREAKER — " + breakerCallSign,
                    breakerActions, breakerSelected, breakerConfirmed, breakerActive);
                drawColumn(batch, x + PANEL_W / 2f + 20f, y + PANEL_H - 82f, "LISTENER — " + listenerCallSign,
                    listenerActions, listenerSelected, listenerConfirmed, listenerActive);
                font.getData().setScale(0.72f);
                font.setColor(DIM);
                String controls = soloControl
                    ? "Select: W/S or arrows   Breaker: LMB   Listener: RMB   TAB: switch column"
                    : "Select an action. Breaker confirms with LMB; Listener confirms with RMB.";
                font.draw(batch, controls, x + 20f, y + 15f, PANEL_W - 40f, Align.center, false);
                break;
            }
            case RESOLUTION: {
                float actionY = y + PANEL_H - 94f;
                font.getData().setScale(1.2f);
                font.setColor(TEXT);
                actionY -= font.draw(batch, breakerLine, x + 20f, actionY,
                    PANEL_W - 40f, Align.left, true).height + 9f;
                actionY -= font.draw(batch, listenerLine, x + 20f, actionY,
                    PANEL_W - 40f, Align.left, true).height + 16f;
                font.setColor(CYAN);
                font.draw(batch, "OPERATOR ACTIONS EXECUTE", x + 20f, actionY,
                    PANEL_W - 40f, Align.center, false);
                break;
            }
            case WARDEN_TURN: {
                float lineY = y + PANEL_H - 105f;
                font.getData().setScale(1.3f);
                font.setColor(MAGENTA);
                lineY -= font.draw(batch, wardenLine, x + 20f, lineY,
                    PANEL_W - 40f, Align.center, true).height + 32f;
                font.getData().setScale(0.9f);
                font.setColor(DIM);
                font.draw(batch, "ROUND COMPLETE", x + 20f, lineY, PANEL_W - 40f, Align.center, false);
                break;
            }
            default:
                break;
        }
        batch.end();
    }

    // During animation and the Warden response, leave the battlefield visible. The full action
    // list returns only when the next PLAYER_TURN begins.
    private void renderCompact(ShapeRenderer shape, SpriteBatch batch, UiViewport ui,
                               TurnManager.Phase phase, String wardenLine,
                               String breakerLine, String listenerLine) {
        float height = phase == TurnManager.Phase.RESOLUTION ? 116f : 92f;
        float x = (ui.width() - PANEL_W) / 2f;
        float y = TOP_Y - height;

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, PANEL_W, height);
        shape.setColor(phase == TurnManager.Phase.WARDEN_TURN ? MAGENTA : CYAN);
        shape.rect(x, y + height - 4f, PANEL_W, 4f);
        shape.end();
        blend(false);

        batch.begin();
        font.getData().setScale(1.05f);
        font.setColor(phase == TurnManager.Phase.WARDEN_TURN ? MAGENTA : CYAN);
        String title = phase == TurnManager.Phase.RESOLUTION
            ? "ACTION EXECUTING..."
            : "WARDEN RESPONSE";
        font.draw(batch, title, x + 18f, y + height - 18f);

        font.getData().setScale(0.78f);
        font.setColor(TEXT);
        if (phase == TurnManager.Phase.RESOLUTION) {
            font.draw(batch, breakerLine, x + 18f, y + height - 48f,
                PANEL_W / 2f - 28f, Align.left, true);
            font.draw(batch, listenerLine, x + PANEL_W / 2f + 10f, y + height - 48f,
                PANEL_W / 2f - 28f, Align.left, true);
        } else {
            font.draw(batch, wardenLine, x + 18f, y + height - 49f,
                PANEL_W - 36f, Align.center, true);
        }
        batch.end();
    }

    private void drawColumn(SpriteBatch batch, float x, float topY, String callSign, PlayerActionType[] actions,
                            int selected, boolean confirmed, boolean active) {
        int count = actions.length;

        font.getData().setScale(1.4f);
        font.setColor(active && !confirmed ? CYAN : TEXT);
        font.draw(batch, (active && !confirmed ? "> " : "  ") + callSign, x, topY);
        if (confirmed) {
            font.setColor(GREEN);
            font.draw(batch, "READY", x + COLUMN_W - 90f, topY);
        }

        float rowY = topY - ROW_H;
        font.getData().setScale(1.15f);
        for (int i = 0; i < count; i++) {
            String label = actions[i].label();
            boolean isSelected = (i == selected);
            font.setColor(confirmed ? DIM : (active && isSelected ? CYAN : DIM));
            String prefix = (!confirmed && active && isSelected) ? "> " : "   ";
            font.draw(batch, prefix + label, x, rowY);
            rowY -= ROW_H;
        }
    }

    private void drawMeters(ShapeRenderer shape, float x, float y, float stability,
                            float directiveConflict, float dualMeter) {
        float meterW = 260f;
        float meterH = 10f;

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(TRACK);
        shape.rect(x + 20f, y - meterH, meterW, meterH);
        shape.setColor(STABILITY);
        shape.rect(x + 20f, y - meterH, meterW * Math.min(1f, stability / 100f), meterH);

        shape.setColor(TRACK);
        shape.rect(x + 310f, y - meterH, meterW, meterH);
        shape.setColor(CONFLICT);
        shape.rect(x + 310f, y - meterH, meterW * Math.min(1f, directiveConflict / 100f), meterH);

        shape.setColor(TRACK);
        shape.rect(x + 600f, y - meterH, meterW, meterH);
        shape.setColor(DUAL);
        shape.rect(x + 600f, y - meterH, meterW * Math.min(1f, dualMeter / 100f), meterH);
        shape.end();
    }

    private static String phaseLabel(TurnManager.Phase phase, Role sideOneRole, int activeMenuSide,
                                     boolean p1Confirmed, boolean p2Confirmed) {
        switch (phase) {
            case PLAYER_TURN:
                if (p1Confirmed && p2Confirmed) return "PLAYER TURN — ACTIONS LOCKED";
                Role active = activeMenuSide == 1 ? sideOneRole : sideOneRole.other();
                return "PLAYER TURN — CURRENT OPERATOR: " + active.name();
            case RESOLUTION:
                return "RESOLUTION — OPERATORS ACT";
            case WARDEN_TURN:
                return "WARDEN TURN — DEFENSIVE RESPONSE";
            default:
                return phase.name();
        }
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
