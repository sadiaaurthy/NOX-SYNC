package io.github.fableops.ui;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/**
 * Rasterizes a string once, via Java2D/AWT system fonts, into a crisp LibGDX Texture —
 * instead of scaling LibGDX's own low-resolution default BitmapFont, which blurs badly
 * once stretched past its native size (the exact bug this replaces). Desktop-only code,
 * but that's the only target this project builds for (core/lwjgl3/launcher — no
 * Android/GWT/iOS module exists in settings.gradle), and java.awt/java.desktop already
 * ships with the JDK — no new Gradle dependency.
 *
 * Every texture is rasterized in flat white; callers tint it to whatever color/hover
 * state they need via SpriteBatch.setColor(...) before drawing, so one texture per
 * unique string covers every color variant that string is ever shown in (e.g. a
 * shortcut-key letter drawn dim normally and bright on hover) without regenerating.
 */
public final class TextTexture {

    public final Texture texture;
    public final int width;
    public final int height;

    private TextTexture(Texture texture, int width, int height) {
        this.texture = texture;
        this.width = width;
        this.height = height;
    }

    /** Renders at the given AWT point size, measuring first so the texture is sized exactly to the glyphs. */
    public static TextTexture render(String text, String family, int style, float pointSize) {
        Font font = new Font(family, style, 1).deriveFont(pointSize);
        return renderWithFont(text, font);
    }

    /**
     * Renders sized so the resulting texture height matches targetHeightPx exactly,
     * regardless of what point size that requires for the actual installed font — a
     * one-time calibration probe (not a per-frame cost) that keeps on-screen sizing
     * consistent even if the requested family isn't installed and AWT substitutes one
     * with different metrics.
     */
    public static TextTexture renderAtHeight(String text, String family, int style, float targetHeightPx) {
        Font probe = new Font(family, style, 1).deriveFont(100f);
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics fm = measure.createGraphics().getFontMetrics(probe);
        float scale = targetHeightPx / fm.getHeight();
        Font finalFont = new Font(family, style, 1).deriveFont(100f * scale);
        return renderWithFont(text, finalFont);
    }

    private static TextTexture renderWithFont(String text, Font font) {
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics metrics = measure.createGraphics().getFontMetrics(font);
        int w = Math.max(1, metrics.stringWidth(text));
        int h = Math.max(1, metrics.getHeight());

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, 0, metrics.getAscent());
        g.dispose();

        Pixmap pixmap = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            for (int x = 0; x < w; x++) {
                int p = argb[rowBase + x];
                int a = (p >>> 24) & 0xFF;
                int r = (p >>> 16) & 0xFF;
                int gr = (p >>> 8) & 0xFF;
                int b = p & 0xFF;
                pixmap.drawPixel(x, y, (r << 24) | (gr << 16) | (b << 8) | a);
            }
        }
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return new TextTexture(texture, w, h);
    }

    public void dispose() {
        texture.dispose();
    }
}
