package io.github.fableops.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;

// Split-screen code shared by Level 1 and Level 2
public final class SplitScreen {

    // Gap between the halves, in pixels
    public static final int DIVIDER = 4;

    // Camera height in world units, the width depends on the window
    public static final float CAM_H = 720f;

    // Player colours, don't modify these
    public static final Color ACCENT_P1 = new Color(0f, 0.90f, 1f, 1f);
    public static final Color ACCENT_P2 = new Color(1f, 0.16f, 0.43f, 1f);

    // HUD metrics, in UiViewport's virtual units.
    public static final float HUD_FONT_SCALE = 1.4f;
    public static final float HUD_MARGIN = 26f;
    public static final float HUD_LINE_STEP = 34f;
    private static final float BAR_W = 220f;
    private static final float BAR_H = 22f;
    private static final float BAR_GAP = 20f;

    public interface HalfRenderer {
        void drawHalf(OrthographicCamera camera);
    }

    // Screens pass themselves in, so nothing is allocated per frame
    public static void drawHalves(HalfRenderer renderer, Player p1, Player p2) {
        int screenW = Gdx.graphics.getBackBufferWidth();
        int screenH = Gdx.graphics.getBackBufferHeight();
        int half = (screenW - DIVIDER) / 2;

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        Gdx.gl.glViewport(0, 0, half, screenH);
        renderer.drawHalf(p1.camera);
        Gdx.gl.glViewport(half + DIVIDER, 0, half, screenH);
        renderer.drawHalf(p2.camera);

        // Restore the full window for the UI pass that follows.
        Gdx.gl.glViewport(0, 0, screenW, screenH);
    }

    // Keeps CAM_H and adjusts the width, so a 4:3 projector isn't squashed
    public static void fitCameras(Player p1, Player p2) {
        float bbW = Gdx.graphics.getBackBufferWidth();
        float bbH = Gdx.graphics.getBackBufferHeight();
        if (bbW <= 0f || bbH <= 0f) return; // minimised window
        float halfW = (bbW - DIVIDER) / 2f;
        float camW = CAM_H * (halfW / bbH);
        p1.setCameraViewport(camW, CAM_H);
        p2.setCameraViewport(camW, CAM_H);
    }

    // The default font looks blocky when scaled without this
    public static void smoothFont(BitmapFont font) {
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        font.setUseIntegerPositions(false);
    }

    // Both bars in each half, in one ShapeRenderer pass
    public static void drawHealthBars(ShapeRenderer shape, float uiWidth, float barY, Player p1, Player p2) {
        float half = uiWidth / 2f;
        float step = BAR_W + BAR_GAP;
        shape.begin(ShapeRenderer.ShapeType.Filled);
        drawBar(shape, HUD_MARGIN, barY, p1.health, Color.CYAN);
        drawBar(shape, HUD_MARGIN + step, barY, p2.health, Color.MAGENTA);
        drawBar(shape, half + HUD_MARGIN, barY, p1.health, Color.CYAN);
        drawBar(shape, half + HUD_MARGIN + step, barY, p2.health, Color.MAGENTA);
        shape.end();
    }

    private static void drawBar(ShapeRenderer shape, float x, float y, float health, Color color) {
        shape.setColor(Color.DARK_GRAY);
        shape.rect(x, y, BAR_W, BAR_H);
        shape.setColor(color);
        shape.rect(x, y, BAR_W * (health / Player.MAX_HEALTH), BAR_H);
    }

    private SplitScreen() {}
}
