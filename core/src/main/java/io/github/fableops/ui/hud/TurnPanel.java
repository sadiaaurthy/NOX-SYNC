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

// One shared Warden encounter menu. Both operators remain visible in the original combined panel;
// the inventory rows extend the columns without creating a second menu architecture.
public class TurnPanel {

    public enum InventoryCategory {
        SIDEARM("SIDE ARM"),
        SHIELD("SHIELD"),
        MEDKIT("MEDKIT"),
        TNT("TNT");

        private final String label;

        InventoryCategory(String label) { this.label = label; }

        public String label() { return label; }
    }

    private static final float PANEL_W = 900f;
    private static final float PANEL_H = 600f;
    private static final float TOP_Y = 840f;
    private static final float COLUMN_W = 410f;
    private static final float ROW_H = 28f;

    private static final Color PANEL = new Color(0.03f, 0.04f, 0.05f, 0.88f);
    private static final Color EDGE = new Color(1f, 1f, 1f, 0.1f);
    private static final Color TRACK = new Color(0.15f, 0.15f, 0.17f, 1f);
    private static final Color TEXT = new Color(0.94f, 0.94f, 0.91f, 1f);
    private static final Color DIM = new Color(0.6f, 0.6f, 0.58f, 1f);
    private static final Color CYAN = new Color(0.35f, 0.92f, 1f, 1f);
    private static final Color WARNING = new Color(1f, 0.34f, 0.58f, 1f);
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
                       Role sideOneRole, String p1CallSign, String p2CallSign,
                       int p1Selected, int p2Selected, boolean p1Confirmed, boolean p2Confirmed,
                       PlayerActionType[] p1Actions, PlayerActionType[] p2Actions,
                       int activeMenuSide, boolean soloControl,
                       boolean authorizationAllowed, String authorizationLockReason,
                       int p1InventorySelected, int p2InventorySelected,
                       boolean p1InventoryFocus, boolean p2InventoryFocus,
                       int[] p1Quantities, int[] p2Quantities) {
        float x = (ui.width() - PANEL_W) / 2f;
        float y = TOP_Y - PANEL_H;

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, PANEL_W, PANEL_H);
        shape.setColor(EDGE);
        shape.rect(x, y + PANEL_H - 1f, PANEL_W, 1f);
        shape.rect(x + PANEL_W / 2f, y + 45f, 1f, PANEL_H - 120f);
        shape.end();
        drawMeters(shape, x, y + PANEL_H - 51f, stability, directiveConflict, dualMeter);
        blend(false);

        Role p1Role = sideOneRole;
        Role p2Role = sideOneRole.other();
        batch.begin();
        font.getData().setScale(1.0f);
        font.setColor(TEXT);
        font.draw(batch, "WARDEN STATUS: " + state.name().replace('_', ' '),
            x + 20f, y + PANEL_H - 13f);
        font.setColor(CYAN);
        font.draw(batch, "PLAYER TURN - PRESS ESC TO CLOSE", x + 390f, y + PANEL_H - 13f,
            PANEL_W - 410f, Align.right, false);

        font.getData().setScale(0.78f);
        font.setColor(DIM);
        font.draw(batch, "CONTAINMENT STABILITY", x + 20f, y + PANEL_H - 34f);
        font.draw(batch, "DIRECTIVE CONFLICT", x + 310f, y + PANEL_H - 34f);
        font.draw(batch, "AUTHORIZATION", x + 600f, y + PANEL_H - 34f);

        drawColumn(batch, x + 20f, y + PANEL_H - 82f, 1, p1Role, p1CallSign,
            p1Actions, p1Selected, p1Confirmed, activeMenuSide == 1,
            p1InventorySelected, p1InventoryFocus, p1Quantities, authorizationAllowed);
        drawColumn(batch, x + PANEL_W / 2f + 20f, y + PANEL_H - 82f, 2, p2Role, p2CallSign,
            p2Actions, p2Selected, p2Confirmed, activeMenuSide == 2,
            p2InventorySelected, p2InventoryFocus, p2Quantities, authorizationAllowed);

        if (!authorizationAllowed) {
            font.getData().setScale(0.96f);
            font.setColor(WARNING);
            font.draw(batch, authorizationLockReason, x + 20f, y + 50f,
                PANEL_W - 40f, Align.center, false);
        }
        font.getData().setScale(0.88f);
        font.setColor(DIM);
        String controls = soloControl
            ? "W/S SELECT   I ACTIONS/INVENTORY   ENTER CONFIRM   TAB PLAYER   ESC CLOSE"
            : "W/S SELECT   I ACTIONS/INVENTORY   ENTER CONFIRM   ESC CLOSE";
        font.draw(batch, controls, x + 20f, y + 14f, PANEL_W - 40f, Align.center, false);
        batch.end();
    }

    private void drawColumn(SpriteBatch batch, float x, float topY, int side, Role role,
                            String callSign, PlayerActionType[] actions, int actionSelected,
                            boolean confirmed, boolean active, int inventorySelected,
                            boolean inventoryFocus, int[] quantities,
                            boolean authorizationAllowed) {
        font.getData().setScale(1.45f);
        font.setColor(active && !confirmed ? CYAN : TEXT);
        font.draw(batch, (active && !confirmed ? "> " : "  ") + "PLAYER " + side + ": "
            + role.name() + " " + callSign, x, topY);
        if (confirmed) {
            font.setColor(GREEN);
            font.draw(batch, "READY", x + COLUMN_W - 76f, topY);
        }

        float rowY = topY - ROW_H;
        font.getData().setScale(0.9f);
        font.setColor(DIM);
        font.draw(batch, "PLAYER ACTIONS", x, rowY);
        rowY -= ROW_H;

        font.getData().setScale(1.16f);
        for (int i = 0; i < actions.length; i++) {
            boolean disabled = actions[i] == PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT
                && !authorizationAllowed;
            boolean selected = active && !confirmed && !inventoryFocus && i == actionSelected;
            font.getData().setScale(selected ? 1.26f : 1.16f);
            font.setColor(disabled ? DIM : selected ? CYAN : TEXT);
            font.draw(batch, (selected ? "> " : "  ") + actions[i].label()
                + (disabled ? " [LOCKED]" : ""), x, rowY);
            rowY -= ROW_H;
        }

        // Every Turn Menu is PLAYER ACTIONS above PLAYER INVENTORY, whatever the enemies are doing
        rowY -= ROW_H * 0.45f;
        font.getData().setScale(1.0f);
        font.setColor(DIM);
        font.draw(batch, "PLAYER INVENTORY", x, rowY);
        rowY -= ROW_H;

        InventoryCategory[] categories = InventoryCategory.values();
        font.getData().setScale(1.16f);
        for (int i = 0; i < categories.length; i++) {
            boolean selected = active && !confirmed && inventoryFocus && i == inventorySelected;
            font.getData().setScale(selected ? 1.26f : 1.16f);
            font.setColor(selected ? CYAN : quantities[i] > 0 ? TEXT : DIM);
            font.draw(batch, (selected ? "> " : "  ") + categories[i].label()
                + " x" + quantities[i], x, rowY);
            rowY -= ROW_H;
        }
    }

    private void drawMeters(ShapeRenderer shape, float x, float y, float stability,
                            float directiveConflict, float dualMeter) {
        float meterW = 260f;
        float meterH = 10f;
        shape.begin(ShapeRenderer.ShapeType.Filled);
        drawMeter(shape, x + 20f, y - meterH, meterW, meterH, stability, STABILITY);
        drawMeter(shape, x + 310f, y - meterH, meterW, meterH, directiveConflict, CONFLICT);
        drawMeter(shape, x + 600f, y - meterH, meterW, meterH, dualMeter, DUAL);
        shape.end();
    }

    private static void drawMeter(ShapeRenderer shape, float x, float y, float width, float height,
                                  float value, Color fill) {
        shape.setColor(TRACK);
        shape.rect(x, y, width, height);
        shape.setColor(fill);
        shape.rect(x, y, width * Math.min(1f, Math.max(0f, value) / 100f), height);
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
