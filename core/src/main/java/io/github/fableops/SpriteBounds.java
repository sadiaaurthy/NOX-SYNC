package io.github.fableops;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import java.util.Arrays;

// Measures the feet (bottom 10% of the body) and the body inside a sprite, so hitboxes follow the art
// Uses the median over all walk frames so the hitbox doesn't change while walking
final class SpriteBounds {

    private static final float FEET_FRACTION = 0.10f;

    // World units, relative to the bottom-left corner of the drawn sprite box.
    final float footX, footY, footW, footH;
    final float bodyX, bodyY, bodyW, bodyH;

    // scales = world units per sheet pixel for each frame
    SpriteBounds(Pixmap pixmap, TextureRegion[] frames, float[] scales, float boxSize) {
        int n = frames.length;
        float[] footCentres = new float[n], footWidths = new float[n], footHeights = new float[n];
        float[] bodyCentres = new float[n], bodyWidths = new float[n], bodyHeights = new float[n];
        float[] bottoms = new float[n];
        int measured = 0;

        for (int i = 0; i < n; i++) {
            TextureRegion frame = frames[i];
            int x0 = frame.getRegionX(), y0 = frame.getRegionY();
            int w = frame.getRegionWidth(), h = frame.getRegionHeight();

            int top = -1, bottom = -1; // image rows, top < bottom
            for (int py = 0; py < h; py++) {
                if (!SpriteSheetSlicer.hasContent(pixmap, false, y0 + py, x0, x0 + w)) continue;
                if (top < 0) top = py;
                bottom = py;
            }
            if (top < 0) continue;

            int feetTop = bottom + 1 - Math.max(1, Math.round((bottom + 1 - top) * FEET_FRACTION));
            int footLeft = w, footRight = -1, bodyLeft = w, bodyRight = -1;
            for (int px = 0; px < w; px++) {
                for (int py = top; py <= bottom; py++) {
                    if (!SpriteSheetSlicer.isOpaque(pixmap, x0 + px, y0 + py)) continue;
                    bodyLeft = Math.min(bodyLeft, px);
                    bodyRight = px;
                    if (py >= feetTop) {
                        footLeft = Math.min(footLeft, px);
                        footRight = px;
                    }
                }
            }

            float s = scales[i];
            float offX = (boxSize - w * s) / 2f;
            float offY = boxSize - h * s;
            footCentres[measured] = offX + (footLeft + footRight + 1) * s / 2f;
            footWidths[measured] = (footRight + 1 - footLeft) * s;
            footHeights[measured] = (bottom + 1 - feetTop) * s;
            bodyCentres[measured] = offX + (bodyLeft + bodyRight + 1) * s / 2f;
            bodyWidths[measured] = (bodyRight + 1 - bodyLeft) * s;
            bodyHeights[measured] = (bottom + 1 - top) * s;
            bottoms[measured] = offY + (h - 1 - bottom) * s;
            measured++;
        }

        footW = median(footWidths, measured);
        footX = median(footCentres, measured) - footW / 2f;
        footY = median(bottoms, measured);
        footH = median(footHeights, measured);
        bodyW = median(bodyWidths, measured);
        bodyX = median(bodyCentres, measured) - bodyW / 2f;
        bodyY = footY;
        bodyH = median(bodyHeights, measured);
    }

    private static float median(float[] values, int count) {
        Arrays.sort(values, 0, count);
        int mid = count / 2;
        return (count % 2 == 1) ? values[mid] : (values[mid - 1] + values[mid]) / 2f;
    }
}
