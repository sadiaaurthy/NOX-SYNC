package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

// A one-shot in-level story beat: eyebrow/title/body/hint, centred over the whole window (not per
// half - it's a moment both players read together), styled like CodePopupUI's notched panel.
// Non-modal: whatever is running underneath (the turn system) keeps going while this is up.
// Dismissed by either player's confirm key, or on its own after AUTO_DISMISS seconds
public class StoryBanner {

    private static final float AUTO_DISMISS = 7f;

    private static final float WIDTH_FRACTION = 0.5f;
    private static final float PADDING = 34f;
    private static final float NOTCH = 20f;
    private static final float GAP_AFTER_EYEBROW = 10f;
    private static final float GAP_AFTER_TITLE = 20f;
    private static final float GAP_BEFORE_HINT = 24f;

    private static final float TITLE_SCALE = 1.9f;
    private static final float BODY_SCALE = 1.4f;
    private static final float EYEBROW_SCALE = 1.1f;

    private static final Color COLOR_BG         = new Color(0.04f, 0.043f, 0.047f, 0.97f);
    private static final Color COLOR_BORDER     = new Color(0.29f, 0.24f, 0.18f, 1f);
    private static final Color COLOR_ORANGE     = new Color(1f, 0.54f, 0.24f, 1f);
    private static final Color COLOR_MAGENTA    = new Color(1f, 0.16f, 0.43f, 1f);
    private static final Color COLOR_CYAN       = new Color(0f, 0.90f, 1f, 1f);
    private static final Color COLOR_TEXT       = new Color(0.93f, 0.93f, 0.91f, 1f);

    private final BitmapFont font;
    private boolean open = false;
    private String eyebrow = "";
    private String title = "";
    private String body = "";
    private String hint = "";
    private float timer = 0f;

    public StoryBanner(BitmapFont font) {
        this.font = font;
    }

    public void show(String eyebrow, String title, String body, String hint) {
        this.eyebrow = eyebrow;
        this.title = title;
        this.body = body;
        this.hint = hint;
        this.timer = AUTO_DISMISS;
        this.open = true;
    }

    public boolean isOpen() { return open; }

    public void close() { open = false; }

    public void update(float delta) {
        if (!open) return;
        timer -= delta;
        if (timer <= 0f) open = false;
    }

    // Either player's confirm key dismisses it early. Purely local, never networked
    public void handleInput() {
        if (!open) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER) || Gdx.input.isKeyJustPressed(Input.Keys.E)
            || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            open = false;
        }
    }

    public void render(ShapeRenderer shape, SpriteBatch batch, float uiWorldW, float uiWorldH) {
        if (!open) return;

        float panelW = uiWorldW * WIDTH_FRACTION;
        float contentW = panelW - 2 * PADDING;

        font.getData().setScale(BODY_SCALE);
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        layout.setText(font, body, COLOR_TEXT, contentW, Align.left, true);
        float bodyH = layout.height;

        float panelH = 2 * PADDING + GAP_AFTER_EYEBROW + GAP_AFTER_TITLE + bodyH + GAP_BEFORE_HINT + 60f;
        float panelX = (uiWorldW - panelW) / 2f;
        float panelY = (uiWorldH - panelH) / 2f;

        drawNotchedPanel(shape, panelX, panelY, panelW, panelH);

        float textX = panelX + PADDING;
        float lineY = panelY + panelH - PADDING;

        batch.begin();
        font.getData().setScale(EYEBROW_SCALE);
        font.setColor(COLOR_MAGENTA);
        font.draw(batch, eyebrow, textX, lineY);
        lineY -= GAP_AFTER_EYEBROW + 22f;

        font.getData().setScale(TITLE_SCALE);
        font.setColor(COLOR_ORANGE);
        font.draw(batch, title, textX, lineY, contentW, Align.left, true);
        lineY -= GAP_AFTER_TITLE + 30f;

        font.getData().setScale(BODY_SCALE);
        font.setColor(COLOR_TEXT);
        font.draw(batch, body, textX, lineY, contentW, Align.left, true);
        lineY -= bodyH + GAP_BEFORE_HINT;

        font.setColor(COLOR_CYAN);
        font.draw(batch, hint, textX, lineY, contentW, Align.left, true);
        batch.end();
    }

    private void drawNotchedPanel(ShapeRenderer shape, float x, float y, float w, float h) {
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);

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
        shape.end();

        Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
    }
}
