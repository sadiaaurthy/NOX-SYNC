package io.github.fableops;

import com.badlogic.gdx.graphics.Pixmap;

import java.util.ArrayList;
import java.util.List;

// Finds the frames in a sprite sheet by looking for the transparent gaps between them
final class SpriteSheetSlicer {

    // Ignores faint anti-aliasing pixels
    private static final int ALPHA_THRESHOLD = 20;

    // Runs of visible pixels along x (horizontal) or y, only looking inside [bandStart, bandEnd)
    // Gaps of gapTolerance lines or less don't split a run
    static List<int[]> runs(Pixmap pixmap, boolean horizontal, int bandStart, int bandEnd, int gapTolerance) {
        int length = horizontal ? pixmap.getWidth() : pixmap.getHeight();
        List<int[]> runs = new ArrayList<>();
        int start = -1, last = -1;
        for (int i = 0; i < length; i++) {
            if (!hasContent(pixmap, horizontal, i, bandStart, bandEnd)) continue;
            if (start == -1) {
                start = i;
            } else if (i - last - 1 > gapTolerance) {
                runs.add(new int[]{start, last});
                start = i;
            }
            last = i;
        }
        if (start != -1) runs.add(new int[]{start, last});
        return runs;
    }

    static boolean hasContent(Pixmap pixmap, boolean horizontal, int line, int bandStart, int bandEnd) {
        for (int j = bandStart; j < bandEnd; j++) {
            if (horizontal ? isOpaque(pixmap, line, j) : isOpaque(pixmap, j, line)) return true;
        }
        return false;
    }

    static boolean isOpaque(Pixmap pixmap, int x, int y) {
        return (pixmap.getPixel(x, y) & 0xFF) > ALPHA_THRESHOLD;
    }

    // Cuts in the middle of each gap, null if the number of runs is wrong
    static int[] midpoints(List<int[]> runs, int length, int expected) {
        if (runs.size() != expected) return null;
        int[] boundaries = new int[expected + 1];
        boundaries[expected] = length;
        for (int i = 1; i < expected; i++) {
            boundaries[i] = (runs.get(i - 1)[1] + runs.get(i)[0] + 1) / 2;
        }
        return boundaries;
    }

    // Fallback when the sheet can't be measured
    static int[] uniform(int length, int count) {
        int[] boundaries = new int[count + 1];
        for (int i = 0; i <= count; i++) {
            boundaries[i] = Math.round(i * length / (float) count);
        }
        return boundaries;
    }

    private SpriteSheetSlicer() {}
}
