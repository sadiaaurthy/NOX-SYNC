package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

import java.util.List;

import io.github.fableops.level1.network.CodeFragmentPayload;

/**
 * Real on-screen popup for the reactor terminal puzzle (spec 5.5's CodePopupUI).
 * Solid-background panel, sized as a fraction of whichever split-screen half the
 * interacting player owns (left half for P1, right half for P2) — rendered only
 * over that half, not spanning the whole screen.
 *
 * Laid out in the caller's virtual UI space rather than in back-buffer pixels, so the
 * panel and its text hold the same proportions at any window size, display resolution
 * or OS scaling factor. See the size constants below and Level1Screen.UI_REF_W/H.
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

    // All sizes below are in Level1Screen's virtual UI units (see its UI_REF_W/UI_REF_H),
    // never in back-buffer pixels — that is what keeps this panel and its text the same
    // proportion of the screen on a 1366x768 laptop, a 1080p monitor and a 4K display at
    // any OS scaling factor. render() is handed the current virtual size by the caller.
    //
    // The height fits the worst-case stage (Stage 2's 5 display lines plus 3 input rows,
    // and Stage 3's 4 plus 4) with a little slack. It used to reserve 65% of the screen
    // height for content that never needed it, which is what made the panel read as
    // mostly empty even before the scaling problem.
    private static final float WIDTH_FRACTION = 0.56f;
    private static final float HEIGHT_FRACTION = 0.50f;
    private static final float PADDING = 34f;
    private static final float NOTCH = 20f;

    // Vertical rhythm, also virtual units — kept next to the font scales they depend on.
    private static final float GAP_AFTER_EYEBROW = 10f;
    private static final float GAP_AFTER_TITLE = 46f;
    private static final float GAP_BETWEEN_LINES = 10f;
    private static final float GAP_BEFORE_INPUTS = 22f;
    private static final float GAP_BETWEEN_INPUTS = 14f;
    private static final float STAGE_PIP_INSET = 62f;
    private static final float FOOTER_INSET = 24f;

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
    // Only meaningful in Debug mode, where both popups can be open on one keyboard.
    // Host/client each own a single popup and leave this true for its whole lifetime.
    private boolean focused = true;

    private final BitmapFont titleFont;
    private final BitmapFont bodyFont;
    private final BitmapFont eyebrowFont;

    /**
     * Font scales are in virtual units too: the default BitmapFont is ~15px tall, so 1.4
     * is ~21 units of a 1080-unit-tall design space. Because that space is fixed, these
     * stay a constant share of screen height instead of the fixed pixel counts they used
     * to be — the direct cause of text rendering too small on high-resolution displays.
     * Linear filtering keeps the upscaled bitmap glyphs from going blocky, matching what
     * Level1Screen already does to its own fonts.
     */
    public CodePopupUI() {
        titleFont = new BitmapFont();
        titleFont.getData().setScale(1.9f);
        bodyFont = new BitmapFont();
        bodyFont.getData().setScale(1.4f);
        eyebrowFont = new BitmapFont();
        eyebrowFont.getData().setScale(1.1f);
        smoothFont(titleFont);
        smoothFont(bodyFont);
        smoothFont(eyebrowFont);
    }

    private static void smoothFont(BitmapFont f) {
        f.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        f.setUseIntegerPositions(false);
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

    /** Marks this terminal as the one currently receiving keystrokes (Debug mode). */
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    public void handleInput() {
        if (!open || payload == null) return;

        // ESC is checked before the "nothing to submit" guard below — Stage 2's
        // legend-holder owns no positions, and returning early left them unable to
        // close their own popup at all.
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            close();
            return;
        }

        List<Integer> owned = payload.getOwnedPositions();
        if (owned.isEmpty()) return;

        // TAB only — arrow keys stay reserved for Player 2's movement, which shares one
        // keyboard with this popup in Debug mode.
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            selectedIndex = (selectedIndex + 1) % owned.size();
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

    /**
     * @param uiWorldW virtual width of the caller's UI space (Level1Screen.uiWorldW)
     * @param uiWorldH virtual height of the caller's UI space (Level1Screen.uiWorldH)
     */
    public void render(ShapeRenderer shape, SpriteBatch batch, float uiWorldW, float uiWorldH) {
        if (!open || payload == null) return;

        // Virtual UI space, supplied by the caller — the same space its camera projects,
        // so the panel lands centered in its split-screen half on any resolution or OS
        // scaling factor. The 4px world divider is a physical-pixel gap between the two
        // glViewports and is sub-unit once scaled, so the halves are simply uiWorldW/2.
        float halfW = uiWorldW / 2f;

        float panelW = halfW * WIDTH_FRACTION;
        float panelH = uiWorldH * HEIGHT_FRACTION;

        float halfOriginX = (playerSide == 1) ? 0f : halfW;
        float panelX = halfOriginX + (halfW - panelW) / 2f;
        float panelY = (uiWorldH - panelH) / 2f;

        drawNotchedPanel(shape, panelX, panelY, panelW, panelH);

        // Every line is drawn wrapped to the panel's inner width, and lineY advances by
        // the laid-out height, so long strings wrap inside the panel instead of running
        // off its right edge. All label text is ASCII — the default BitmapFont has no
        // glyphs for box-drawing characters and renders them as empty squares.
        float contentW = panelW - 2 * PADDING;

        batch.begin();
        float lineY = panelY + panelH - PADDING;

        eyebrowFont.setColor(COLOR_MAGENTA);
        lineY -= drawWrapped(batch, eyebrowFont, "// REACTOR TERMINAL",
            panelX + PADDING, lineY, contentW) + GAP_AFTER_EYEBROW;

        titleFont.setColor(COLOR_ORANGE);
        titleFont.draw(batch, "STAGE " + payload.getStageNumber(), panelX + PADDING, lineY);
        eyebrowFont.setColor(COLOR_ORANGE_DIM);
        eyebrowFont.draw(batch, "[##-]", panelX + panelW - PADDING - STAGE_PIP_INSET, lineY);
        lineY -= GAP_AFTER_TITLE;

        bodyFont.setColor(COLOR_CYAN);
        for (String line : payload.getDisplayLines()) {
            lineY -= drawWrapped(batch, bodyFont, line, panelX + PADDING, lineY, contentW)
                + GAP_BETWEEN_LINES;
        }

        lineY -= GAP_BEFORE_INPUTS;
        List<Integer> owned = payload.getOwnedPositions();
        for (int i = 0; i < owned.size(); i++) {
            boolean selected = (i == selectedIndex);
            bodyFont.setColor(selected ? COLOR_TEXT : COLOR_DIM);
            String prefix = selected ? "> " : "   ";
            String value = selected ? currentInput + "_" : "";
            lineY -= drawWrapped(batch, bodyFont,
                prefix + "Position " + owned.get(i) + ":  " + value,
                panelX + PADDING, lineY, contentW) + GAP_BETWEEN_INPUTS;
        }

        bodyFont.setColor(focused ? COLOR_DIM : COLOR_ORANGE);
        String footer = focused
            ? "TAB switch   ENTER submit   ESC close"
            : "SPACE to type here";
        drawWrapped(batch, bodyFont, footer, panelX + PADDING, panelY + PADDING + FOOTER_INSET, contentW);
        batch.end();
    }

    /** Draws text wrapped to maxWidth and returns the height it consumed. */
    private static float drawWrapped(SpriteBatch batch, BitmapFont font,
                                     String text, float x, float y, float maxWidth) {
        GlyphLayout layout = font.draw(batch, text, x, y, maxWidth, Align.left, true);
        return layout.height;
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
        // The focused terminal keeps the bright accent; an unfocused one drops to the
        // dim border colour so it's obvious at a glance which popup the keyboard drives.
        shape.setColor(focused ? COLOR_ORANGE : COLOR_DIM);
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
