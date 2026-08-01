package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.List;

import io.github.fableops.level1.network.CodeFragmentPayload;

/**
 * Real on-screen popup for the reactor terminal puzzle (spec 5.5's CodePopupUI).
 * Solid-background panel, 40% of the width of whichever split-screen half the
 * interacting player owns (left half for P1, right half for P2) — rendered only
 * over that half, not spanning the whole screen.
 *
 * Styled to match the JavaFX launcher's palette and panel language (launcher.css) —
 * dark panel, orange accent, magenta eyebrow label, notched corners. ShapeRenderer has
 * no clip-path, so the notch is faked as the union of two rects (a cross shape) with a
 * hand-drawn octagon outline over it — same visual idea as the launcher's stepped cut,
 * one step instead of two.
 */
public class CodePopupUI {

    public interface SubmitListener {
        void onSubmit(int positionIndex, String guess);
    }

    private static final int DIVIDER = 4; // must match Level1Screen's split divider width
    private static final float WIDTH_FRACTION = 0.42f;
    private static final float HEIGHT_FRACTION = 0.65f;
    private static final float PADDING = 26f;
    private static final float NOTCH = 16f;

    private static final Color COLOR_BG      = new Color(0.04f, 0.043f, 0.047f, 0.97f);
    private static final Color COLOR_BORDER  = new Color(0.29f, 0.24f, 0.18f, 1f);  // -fo-line-strong
    private static final Color COLOR_ORANGE  = new Color(1f, 0.54f, 0.24f, 1f);     // -fo-orange
    private static final Color COLOR_ORANGE_DIM = new Color(0.42f, 0.23f, 0.09f, 1f); // -fo-orange-dim
    private static final Color COLOR_MAGENTA = new Color(1f, 0.16f, 0.43f, 1f);     // -fo-magenta
    private static final Color COLOR_CYAN    = new Color(0f, 0.90f, 1f, 1f);        // -fo-cyan
    private static final Color COLOR_DIM     = new Color(0.30f, 0.30f, 0.28f, 1f);  // -fo-text-2
    private static final Color COLOR_TEXT    = new Color(0.93f, 0.93f, 0.91f, 1f);  // -fo-text-0

    private boolean open = false;
    private CodeFragmentPayload payload;
    private int selectedIndex = 0;
    private String currentInput = "";
    private int playerSide = 1; // 1 = left half, 2 = right half
    private SubmitListener listener;

    private final BitmapFont titleFont;
    private final BitmapFont bodyFont;
    private final BitmapFont eyebrowFont;

    public CodePopupUI() {
        titleFont = new BitmapFont();
        titleFont.getData().setScale(1.4f);
        bodyFont = new BitmapFont();
        bodyFont.getData().setScale(1.1f);
        eyebrowFont = new BitmapFont();
        eyebrowFont.getData().setScale(0.85f);
    }

    public void setSubmitListener(SubmitListener listener) {
        this.listener = listener;
    }

    /** playerSide 1 opens over the left split (P1's view), 2 over the right split (P2's view). */
    public void open(CodeFragmentPayload payload, int playerSide) {
        this.payload = payload;
        this.playerSide = playerSide;
        this.selectedIndex = 0;
        this.currentInput = "";
        this.open = true;
    }

    public void close() {
        this.open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /** Swaps in a new view (e.g. after advancing to the next stage) without closing the popup. */
    public void updatePayload(CodeFragmentPayload payload) {
        this.payload = payload;
        this.selectedIndex = 0;
        this.currentInput = "";
    }

    public void handleInput() {
        if (!open || payload == null) return;

        List<Integer> owned = payload.getOwnedPositions();
        if (owned.isEmpty()) return;

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            close();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB) || Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) {
            selectedIndex = (selectedIndex + 1) % owned.size();
            currentInput = "";
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT)) {
            selectedIndex = (selectedIndex - 1 + owned.size()) % owned.size();
            currentInput = "";
        }
        for (int k = Input.Keys.NUM_0; k <= Input.Keys.NUM_9; k++) {
            if (Gdx.input.isKeyJustPressed(k)) {
                currentInput += (k - Input.Keys.NUM_0);
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE) && !currentInput.isEmpty()) {
            currentInput = currentInput.substring(0, currentInput.length() - 1);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !currentInput.isEmpty()) {
            int positionIndex = owned.get(selectedIndex);
            if (listener != null) listener.onSubmit(positionIndex, currentInput);
            currentInput = "";
        }
    }

    public void render(ShapeRenderer shape, SpriteBatch batch) {
        if (!open || payload == null) return;

        // Back-buffer size — matches the physical-pixel space Level1Screen's glViewport
        // splits use, so the panel lands centered in its half instead of drifting off
        // whenever the display has OS-level scaling.
        float screenW = Gdx.graphics.getBackBufferWidth();
        float screenH = Gdx.graphics.getBackBufferHeight();
        float halfW = (screenW - DIVIDER) / 2f;

        float panelW = halfW * WIDTH_FRACTION;
        float panelH = screenH * HEIGHT_FRACTION;

        float halfOriginX = (playerSide == 1) ? 0f : (halfW + DIVIDER);
        float panelX = halfOriginX + (halfW - panelW) / 2f;
        float panelY = (screenH - panelH) / 2f;

        drawNotchedPanel(shape, panelX, panelY, panelW, panelH);

        batch.begin();
        float lineY = panelY + panelH - PADDING;

        eyebrowFont.setColor(COLOR_MAGENTA);
        eyebrowFont.draw(batch, "■ REACTOR TERMINAL", panelX + PADDING, lineY);
        lineY -= 22;

        titleFont.setColor(COLOR_ORANGE);
        titleFont.draw(batch, "STAGE " + payload.getStageNumber(), panelX + PADDING, lineY);
        eyebrowFont.setColor(COLOR_ORANGE_DIM);
        eyebrowFont.draw(batch, "■■□", panelX + panelW - PADDING - 40f, lineY);
        lineY -= 34;

        bodyFont.setColor(COLOR_CYAN);
        for (String line : payload.getDisplayLines()) {
            bodyFont.draw(batch, line, panelX + PADDING, lineY);
            lineY -= 26;
        }

        lineY -= 16;
        List<Integer> owned = payload.getOwnedPositions();
        for (int i = 0; i < owned.size(); i++) {
            boolean selected = (i == selectedIndex);
            bodyFont.setColor(selected ? COLOR_TEXT : COLOR_DIM);
            String prefix = selected ? "> " : "   ";
            String value = selected ? currentInput + "_" : "";
            bodyFont.draw(batch, prefix + "Position " + owned.get(i) + ":  " + value, panelX + PADDING, lineY);
            lineY -= 28;
        }

        bodyFont.setColor(COLOR_DIM);
        bodyFont.draw(batch, "TAB switch   ENTER submit   ESC close", panelX + PADDING, panelY + PADDING);
        batch.end();
    }

    /** Fills + outlines an octagon (rect with corners notched off) instead of a plain rect. */
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
        shape.setColor(COLOR_ORANGE);
        shape.line(x, y + h - NOTCH, x + NOTCH, y + h); // top-left notch, accented
        shape.line(x + w - NOTCH, y, x + w, y + NOTCH); // bottom-right notch, accented
        shape.end();
    }

    public void dispose() {
        titleFont.dispose();
        bodyFont.dispose();
        eyebrowFont.dispose();
    }
}
