package io.github.fableops.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;

// HUD and popups are laid out in 1920x1080 units and scaled to the window,
// so text stays the same size at any resolution. Uses the back buffer size for HiDPI
public final class UiViewport {

    private static final float REF_W = 1920f;
    private static final float REF_H = 1080f;

    // F2 sizes, the bigger ones are for projectors
    private static final float[] SCALE_STEPS = {1f, 1.25f, 1.5f};

    private final OrthographicCamera camera = new OrthographicCamera();
    private int scaleStep = 0;
    private float width = REF_W;
    private float height = REF_H;

    public UiViewport() {
        update();
    }

    // Call on resize
    public void update() {
        float bbW = Gdx.graphics.getBackBufferWidth();
        float bbH = Gdx.graphics.getBackBufferHeight();
        if (bbW <= 0f || bbH <= 0f) return; // minimised window
        float scale = SCALE_STEPS[scaleStep] * Math.min(bbW / REF_W, bbH / REF_H);
        width = bbW / scale;
        height = bbH / scale;
        camera.setToOrtho(false, width, height);
    }

    public void cycleScale() {
        scaleStep = (scaleStep + 1) % SCALE_STEPS.length;
        update();
    }

    public OrthographicCamera camera() { return camera; }

    public float width() { return width; }

    public float height() { return height; }
}
