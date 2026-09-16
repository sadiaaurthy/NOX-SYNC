package io.github.fableops.ui.hud;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;

import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.ui.SplitScreen;
import io.github.fableops.ui.UiViewport;

// The in-game HUD: one operator card per half, and a shared banner between them.
// Uses the same pixel font as the inventory panels, so the HUD matches the rest of the game
public final class Hud {

    private static final float MARGIN = 26f;

    // Operator card
    private static final float CARD_W = 316f;
    private static final float CARD_H = 84f;
    private static final float CARD_PAD = 14f;
    private static final float STRIPE_W = 5f;
    private static final float BAR_H = 14f;

    // Banner
    private static final float BANNER_PAD = 20f;
    private static final float BANNER_MIN_W = 520f;
    private static final float METER_W = 240f;
    private static final float METER_H = 7f;

    private static final float TITLE_SCALE = 2.0f;
    private static final float BODY_SCALE = 1.5f;
    private static final float LABEL_SCALE = 1.3f;

    private static final Color PANEL = new Color(0.03f, 0.04f, 0.05f, 0.82f);
    private static final Color EDGE = new Color(1f, 1f, 1f, 0.09f);
    private static final Color TRACK = new Color(0.15f, 0.15f, 0.17f, 1f);
    private static final Color TEXT = new Color(0.94f, 0.94f, 0.91f, 1f);
    private static final Color DIM = new Color(0.66f, 0.66f, 0.63f, 1f);
    private static final Color CYAN = new Color(0.35f, 0.92f, 1f, 1f);
    private static final Color LOW = new Color(1f, 0.30f, 0.34f, 1f);
    private static final Color ALERT = new Color(1f, 0.62f, 0.22f, 1f);

    // Below this the bar turns red
    private static final float LOW_HEALTH = 0.3f;

    private final BitmapFont font = new BitmapFont(Gdx.files.internal("pixel.fnt"));
    // Reused every frame, measuring text would otherwise allocate
    private final GlyphLayout layout = new GlyphLayout();

    private String healthTextP1 = "";
    private String healthTextP2 = "";
    private int shownP1 = -1;
    private int shownP2 = -1;

    public Hud() {
        // A pixel font must not be filtered, that blur is what made the old HUD look soft
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        font.setUseIntegerPositions(false);
    }

    // Each half shows only its own operator, on the outer edge, so the middle stays clear
    // for the banner and neither player reads the other's bar by mistake
    public void drawPlayerCards(ShapeRenderer shape, SpriteBatch batch, UiViewport ui,
                                Player p1, Player p2, Role sideOneRole) {
        float top = ui.height() - MARGIN - CARD_H;
        float rightX = ui.width() - MARGIN - CARD_W;

        int healthP1 = Math.max(0, Math.round(p1.health));
        int healthP2 = Math.max(0, Math.round(p2.health));
        if (healthP1 != shownP1) {
            shownP1 = healthP1;
            healthTextP1 = healthP1 + "%";
        }
        if (healthP2 != shownP2) {
            shownP2 = healthP2;
            healthTextP2 = healthP2 + "%";
        }

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        cardPlate(shape, MARGIN, top, SplitScreen.ACCENT_P1, p1.health);
        cardPlate(shape, rightX, top, SplitScreen.ACCENT_P2, p2.health);
        shape.end();
        blend(false);

        batch.begin();
        cardText(batch, MARGIN, top, sideOneRole.callSign(), "PLAYER 1", healthTextP1);
        cardText(batch, rightX, top, sideOneRole.other().callSign(), "PLAYER 2", healthTextP2);
        batch.end();
    }

    private static void cardPlate(ShapeRenderer shape, float x, float y, Color accent, float health) {
        shape.setColor(PANEL);
        shape.rect(x, y, CARD_W, CARD_H);
        shape.setColor(EDGE);
        shape.rect(x, y + CARD_H - 1f, CARD_W, 1f);
        shape.setColor(accent);
        shape.rect(x, y, STRIPE_W, CARD_H);

        float barX = x + CARD_PAD + STRIPE_W;
        float barW = CARD_W - 2 * CARD_PAD - STRIPE_W;
        float fraction = Math.max(0f, health / Player.MAX_HEALTH);
        shape.setColor(TRACK);
        shape.rect(barX, y + CARD_PAD, barW, BAR_H);
        shape.setColor(fraction <= LOW_HEALTH ? LOW : accent);
        shape.rect(barX, y + CARD_PAD, barW * fraction, BAR_H);
    }

    private void cardText(SpriteBatch batch, float x, float y, String callSign, String side, String health) {
        float textX = x + CARD_PAD + STRIPE_W;
        float width = CARD_W - 2 * CARD_PAD - STRIPE_W;

        font.getData().setScale(LABEL_SCALE);
        font.setColor(DIM);
        font.draw(batch, side, textX, y + CARD_H - CARD_PAD);
        font.draw(batch, health, textX, y + CARD_H - CARD_PAD, width, Align.right, false);

        font.getData().setScale(BODY_SCALE);
        font.setColor(TEXT);
        font.draw(batch, callSign, textX, y + CARD_H - CARD_PAD - 22f);
    }

    // Centred at the top, between the two cards. meter < 0 hides the alert bar
    public void drawBanner(ShapeRenderer shape, SpriteBatch batch, UiViewport ui,
                           String title, String objective, String prompt, float meter) {
        // Never wide enough to reach a card, however long the objective gets
        float maxW = ui.width() - 2f * (MARGIN + CARD_W + 20f);
        float width = Math.max(BANNER_MIN_W, measure(title, TITLE_SCALE) + 2f * BANNER_PAD);
        width = Math.min(width, maxW);
        float textW = width - 2f * BANNER_PAD;

        // Long lines wrap rather than run past the panel, so the height has to follow the text
        float titleH = measureWrapped(title, TITLE_SCALE, textW);
        float objectiveH = measureWrapped(objective, BODY_SCALE, textW);
        float promptH = (prompt == null) ? 0f : measureWrapped(prompt, BODY_SCALE, textW) + 10f;
        float meterH = (meter >= 0f) ? METER_H + 12f : 0f;
        float height = 2f * BANNER_PAD + titleH + 8f + objectiveH + promptH + meterH;

        float x = (ui.width() - width) / 2f;
        float y = ui.height() - MARGIN - height;

        blend(true);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(PANEL);
        shape.rect(x, y, width, height);
        shape.setColor(EDGE);
        shape.rect(x, y + height - 1f, width, 1f);
        if (meter >= 0f) {
            float meterX = x + (width - METER_W) / 2f;
            shape.setColor(TRACK);
            shape.rect(meterX, y + BANNER_PAD, METER_W, METER_H);
            shape.setColor(meter >= 0.7f ? LOW : ALERT);
            shape.rect(meterX, y + BANNER_PAD, METER_W * Math.min(1f, meter), METER_H);
        }
        shape.end();
        blend(false);

        float textX = x + BANNER_PAD;
        float textY = y + height - BANNER_PAD;
        batch.begin();
        font.getData().setScale(TITLE_SCALE);
        font.setColor(TEXT);
        font.draw(batch, title, textX, textY, textW, Align.center, true);
        textY -= titleH + 8f;
        font.getData().setScale(BODY_SCALE);
        font.setColor(DIM);
        font.draw(batch, objective, textX, textY, textW, Align.center, true);
        if (prompt != null) {
            textY -= objectiveH + 10f;
            font.setColor(CYAN);
            font.draw(batch, prompt, textX, textY, textW, Align.center, true);
        }
        batch.end();
    }

    // Height this text needs once wrapped into the panel
    private float measureWrapped(String text, float scale, float targetWidth) {
        font.getData().setScale(scale);
        layout.setText(font, text, TEXT, targetWidth, Align.center, true);
        return layout.height;
    }

    private float measure(String text, float scale) {
        font.getData().setScale(scale);
        layout.setText(font, text);
        return layout.width;
    }

    private static void blend(boolean on) {
        if (on) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    // The gun HUD draws with the same font, so it matches the cards
    public BitmapFont font() {
        return font;
    }

    public void dispose() {
        font.dispose();
    }
}
