package io.github.fableops;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

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

    // Column cuts for one row. Two frames whose slashes touch come out as one wide run,
    // so the widest run gets split at the grid line nearest its middle until there are enough
    static int[] columnsInRow(Pixmap pixmap, int rowTop, int rowBottom, int frames) {
        int width = pixmap.getWidth();
        int[] grid = uniform(width, frames);
        List<int[]> runs = runs(pixmap, true, rowTop, rowBottom, 0);
        while (!runs.isEmpty() && runs.size() < frames) {
            int widest = 0;
            for (int i = 1; i < runs.size(); i++) {
                if (runs.get(i)[1] - runs.get(i)[0] > runs.get(widest)[1] - runs.get(widest)[0]) widest = i;
            }
            int[] run = runs.get(widest);
            int middle = (run[0] + run[1]) / 2;
            int line = -1;
            for (int i = 1; i < frames; i++) {
                boolean inside = grid[i] > run[0] && grid[i] <= run[1];
                if (inside && (line == -1 || Math.abs(grid[i] - middle) < Math.abs(line - middle))) line = grid[i];
            }
            if (line == -1) break;
            runs.set(widest, new int[]{run[0], line - 1});
            runs.add(widest + 1, new int[]{line, run[1]});
        }
        int[] cuts = midpoints(runs, width, frames);
        return cuts != null ? cuts : grid;
    }

    // Visible area of one frame as {left, top, right, bottom}, relative to the frame
    static int[] opaqueBounds(Pixmap pixmap, TextureRegion frame) {
        int left = frame.getRegionWidth(), top = -1, right = -1, bottom = -1;
        for (int py = 0; py < frame.getRegionHeight(); py++) {
            for (int px = 0; px < frame.getRegionWidth(); px++) {
                if (!isOpaque(pixmap, frame.getRegionX() + px, frame.getRegionY() + py)) continue;
                if (top == -1) top = py;
                bottom = py;
                left = Math.min(left, px);
                right = Math.max(right, px);
            }
        }
        return new int[]{left, top, right, bottom};
    }

    private SpriteSheetSlicer() {}
}
