package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

import java.util.List;

import io.github.fableops.level1.network.CodeFragmentPayload;

// Terminal popup, drawn over the interacting player's half of the screen
public class CodePopupUI {

    public interface SubmitListener {
        void onSubmit(int positionIndex, String guess);
    }

    // UI units (see UiViewport)
    private static final float WIDTH_FRACTION = 0.56f;
    private static final float HEIGHT_FRACTION = 0.50f;
    private static final float PADDING = 34f;
    private static final float NOTCH = 20f;
    private static final float GAP_AFTER_EYEBROW = 10f;
    private static final float GAP_AFTER_TITLE = 46f;
    private static final float GAP_BETWEEN_LINES = 10f;
    private static final float GAP_BEFORE_INPUTS = 22f;
    private static final float GAP_BETWEEN_INPUTS = 14f;
    private static final float STAGE_PIP_INSET = 62f;
    private static final float FOOTER_INSET = 24f;

    private static final float TITLE_SCALE = 1.9f;
    private static final float BODY_SCALE = 1.4f;
    private static final float EYEBROW_SCALE = 1.1f;

    private static final Color COLOR_BG         = new Color(0.04f, 0.043f, 0.047f, 0.97f);
    private static final Color COLOR_BORDER     = new Color(0.29f, 0.24f, 0.18f, 1f);
    private static final Color COLOR_ORANGE     = new Color(1f, 0.54f, 0.24f, 1f);
    private static final Color COLOR_ORANGE_DIM = new Color(0.42f, 0.23f, 0.09f, 1f);
    private static final Color COLOR_MAGENTA    = new Color(1f, 0.16f, 0.43f, 1f);
    private static final Color COLOR_CYAN       = new Color(0f, 0.90f, 1f, 1f);
    private static final Color COLOR_DIM        = new Color(0.30f, 0.30f, 0.28f, 1f);
    private static final Color COLOR_TEXT       = new Color(0.93f, 0.93f, 0.91f, 1f);

    private final BitmapFont font;
    private boolean open = false;
    private CodeFragmentPayload payload;
    private int selectedIndex = 0;
    private String currentInput = "";
    private int playerSide = 1; // 1 = left half, 2 = right half
    private SubmitListener listener;
    private String readOnlyEyebrow;
    private String readOnlyTitle;
    private List<String> readOnlyLines;
    // Only false in debug, when the other popup has the keyboard
    private boolean focused = true;

    // The font is shared with the screen, so the scale is set before every draw
    public CodePopupUI(BitmapFont font) {
        this.font = font;
    }

    public void setSubmitListener(SubmitListener listener) {
        this.listener = listener;
    }

    public void open(CodeFragmentPayload payload, int playerSide) {
        this.payload = payload;
        this.readOnlyLines = null;
        this.playerSide = playerSide;
        this.selectedIndex = 0;
        this.currentInput = "";
        this.open = true;
    }

    // Reuses the established terminal presentation for found logs without adding another popup
    // system. Read-only entries close with E, ENTER, or ESC and never expose puzzle inputs.
    public void openReadOnly(String eyebrow, String title, List<String> lines, int playerSide) {
        this.payload = null;
        this.readOnlyEyebrow = eyebrow;
        this.readOnlyTitle = title;
        this.readOnlyLines = lines;
        this.playerSide = playerSide;
        this.open = true;
    }

    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    public void handleInput() {
        if (!open) return;

        if (readOnlyLines != null) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)
                || Gdx.input.isKeyJustPressed(Input.Keys.ENTER)
                || Gdx.input.isKeyJustPressed(Input.Keys.E)) {
                close();
            }
            return;
        }
        if (payload == null) return;

        // Checked first, the Stage 2 legend player has no positions but still needs ESC
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            close();
            return;
        }

        List<Integer> owned = payload.getOwnedPositions();
        if (owned.isEmpty()) return;

        // TAB instead of the arrows, player 2 uses the arrows in debug
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            selectedIndex = (selectedIndex + 1) % owned.size();
            currentInput = "";
        }
        for (int k = Input.Keys.NUM_0; k <= Input.Keys.NUM_9; k++) {
            if (Gdx.input.isKeyJustPressed(k)) currentInput += (k - Input.Keys.NUM_0);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE) && !currentInput.isEmpty()) {
            currentInput = currentInput.substring(0, currentInput.length() - 1);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !currentInput.isEmpty()) {
            if (listener != null) listener.onSubmit(owned.get(selectedIndex), currentInput);
            currentInput = "";
        }
    }

    public void render(ShapeRenderer shape, SpriteBatch batch, float uiWorldW, float uiWorldH) {
        if (!open || (payload == null && readOnlyLines == null)) return;

        float halfW = uiWorldW / 2f;
        float panelW = halfW * WIDTH_FRACTION;
        float panelH = uiWorldH * HEIGHT_FRACTION;
        float panelX = (playerSide == 1 ? 0f : halfW) + (halfW - panelW) / 2f;
        float panelY = (uiWorldH - panelH) / 2f;

        drawNotchedPanel(shape, panelX, panelY, panelW, panelH);

        // ASCII only, the default font has no box-drawing glyphs
        float contentW = panelW - 2 * PADDING;
        float textX = panelX + PADDING;
        float lineY = panelY + panelH - PADDING;

        batch.begin();
        if (readOnlyLines != null) {
            font.setColor(COLOR_MAGENTA);
            lineY -= drawWrapped(batch, readOnlyEyebrow, textX, lineY, contentW, EYEBROW_SCALE)
                + GAP_AFTER_EYEBROW;
            font.setColor(COLOR_ORANGE);
            lineY -= drawWrapped(batch, readOnlyTitle, textX, lineY, contentW, TITLE_SCALE)
                + GAP_AFTER_TITLE;
            font.setColor(COLOR_CYAN);
            for (String line : readOnlyLines) {
                lineY -= drawWrapped(batch, line, textX, lineY, contentW, BODY_SCALE) + GAP_BETWEEN_LINES;
            }
            font.setColor(COLOR_DIM);
            drawWrapped(batch, "E / ENTER / ESC close", textX, panelY + PADDING + FOOTER_INSET,
                contentW, BODY_SCALE);
            batch.end();
            return;
        }

        font.setColor(COLOR_MAGENTA);
        lineY -= drawWrapped(batch, "// REACTOR TERMINAL", textX, lineY, contentW, EYEBROW_SCALE) + GAP_AFTER_EYEBROW;

        font.getData().setScale(TITLE_SCALE);
        font.setColor(COLOR_ORANGE);
        font.draw(batch, "STAGE " + payload.getStageNumber(), textX, lineY);
        font.getData().setScale(EYEBROW_SCALE);
        font.setColor(COLOR_ORANGE_DIM);
        font.draw(batch, "[##-]", panelX + panelW - PADDING - STAGE_PIP_INSET, lineY);
        lineY -= GAP_AFTER_TITLE;

        font.setColor(COLOR_CYAN);
        for (String line : payload.getDisplayLines()) {
            lineY -= drawWrapped(batch, line, textX, lineY, contentW, BODY_SCALE) + GAP_BETWEEN_LINES;
        }

        lineY -= GAP_BEFORE_INPUTS;
        List<Integer> owned = payload.getOwnedPositions();
        for (int i = 0; i < owned.size(); i++) {
            boolean selected = (i == selectedIndex);
            font.setColor(selected ? COLOR_TEXT : COLOR_DIM);
            String text = (selected ? "> " : "   ") + "Position " + owned.get(i) + ":  "
                + (selected ? currentInput + "_" : "");
            lineY -= drawWrapped(batch, text, textX, lineY, contentW, BODY_SCALE) + GAP_BETWEEN_INPUTS;
        }

        font.setColor(focused ? COLOR_DIM : COLOR_ORANGE);
        drawWrapped(batch, focused ? "TAB switch   ENTER submit   ESC close" : "SPACE to type here",
            textX, panelY + PADDING + FOOTER_INSET, contentW, BODY_SCALE);
        batch.end();
    }

    // Returns the height it used
    private float drawWrapped(SpriteBatch batch, String text, float x, float y, float maxWidth, float scale) {
        font.getData().setScale(scale);
        return font.draw(batch, text, x, y, maxWidth, Align.left, true).height;
    }

    // Rectangle with cut corners
    private void drawNotchedPanel(ShapeRenderer shape, float x, float y, float w, float h) {
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(COLOR_BG);
        shape.rect(x, y + NOTCH, w, h - 2 * NOTCH);
        shape.rect(x + NOTCH, y, w - 2 * NOTCH, h);
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(COLOR_BORDER);
        shape.line(x, y + NOTCH, x, y + h - NOTCH);
        shape.line(x, y + h - NOTCH, x + NOTCH, y + h);
        shape.line(x + NOTCH, y + h, x + w - NOTCH, y + h);
        shape.line(x + w - NOTCH, y + h, x + w, y + h - NOTCH);
        shape.line(x + w, y + h - NOTCH, x + w, y + NOTCH);
        shape.line(x + w, y + NOTCH, x + w - NOTCH, y);
        shape.line(x + w - NOTCH, y, x + NOTCH, y);
        shape.line(x + NOTCH, y, x, y + NOTCH);
        // Bright corners on the focused terminal
        shape.setColor(focused ? COLOR_ORANGE : COLOR_DIM);
        shape.line(x, y + h - NOTCH, x + NOTCH, y + h);
        shape.line(x + w - NOTCH, y, x + w, y + NOTCH);
        shape.end();
    }
}
