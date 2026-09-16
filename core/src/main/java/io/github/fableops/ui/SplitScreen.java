package io.github.fableops.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;

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
    public static final float HUD_MARGIN = 26f;

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

    public static void fitCameras(Player p1, Player p2) {
        fitCameras(p1, p2, CAM_H);
    }

    // Keeps camH and adjusts the width, so a 4:3 projector isn't squashed. Levels whose art is
    // drawn from further off pass a taller view, which shows more world without magnifying the
    // art any harder - that is what keeps a wide map sharp instead of mushy
    public static void fitCameras(Player p1, Player p2, float camH) {
        float bbW = Gdx.graphics.getBackBufferWidth();
        float bbH = Gdx.graphics.getBackBufferHeight();
        if (bbW <= 0f || bbH <= 0f) return; // minimised window
        float halfW = (bbW - DIVIDER) / 2f;
        float camW = camH * (halfW / bbH);
        p1.setCameraViewport(camW, camH);
        p2.setCameraViewport(camW, camH);
    }

    // The default font looks blocky when scaled without this
    public static void smoothFont(BitmapFont font) {
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        font.setUseIntegerPositions(false);
    }

    private SplitScreen() {}
}
