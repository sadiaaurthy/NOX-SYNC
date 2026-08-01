package io.github.fableops.level1;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import java.util.ArrayList;
import java.util.List;

import io.github.fableops.Collidable;

/**
 * Level 1's map — a single illustrated background image (not a Tiled map), with
 * hand-traced floor rectangles for collision instead of a wall blacklist, since
 * the art is mostly void with narrow corridors. Coordinates are a first-pass
 * trace off the concept art — expect to nudge them after playtesting.
 *
 * Y is flipped on load: image pixel rows go top-to-bottom, LibGDX world space
 * goes bottom-to-top, so every rectangle below is defined as (imageY -> worldY).
 */
public class Level1Map implements Collidable {

    public static final float WORLD_W = 2752f;
    public static final float WORLD_H = 1536f;

    private Texture background;

    // Always-open floor — Player 1 side (left room).
    private final List<Rectangle> floorP1 = new ArrayList<>();
    // Always-open floor — Player 2 side (right room, mirrored from P1).
    private final List<Rectangle> floorP2 = new ArrayList<>();
    // Shared center chamber — always physically there, but only reachable once
    // both gates are open (see gateP1Open / gateP2Open).
    private final Rectangle centerChamber;

    private final Rectangle gateP1;
    private final Rectangle gateP2;
    private boolean gateP1Open = false;
    private boolean gateP2Open = false;

    // Union of every traced rectangle regardless of side/gate-open state — used only
    // while INTERNAL_WALLS_ENABLED is false, so "no internal walls between P1/P2" still
    // keeps players inside the building's actual footprint instead of the whole
    // rectangular world border (which includes plenty of black void the art never draws).
    private final List<Rectangle> allTracedFloor = new ArrayList<>();

    public Level1Map() {
        background = new Texture(Gdx.files.internal("Level1Map.png"));

        // --- Player 1 (left) room floor, traced from the art ---
        // Re-traced against the actual Level1Map.png (the original pass was off —
        // doorway rectangles didn't line up with the real door gaps, which is what
        // caused both the "walking through walls" and "getting stuck" reports).
        // Every rectangle now overlaps its neighbor by at least a player's width/height
        // (SIZE=100) at the seam, so the player's hitbox is always fully contained in
        // at least one rectangle during a transition — that overlap margin is what
        // fixes getting stuck at doorways, not just the coordinates themselves.
        addFloorRect(floorP1, 470, 1290, 20, 460);     // top room + drone corridor
        addFloorRect(floorP1, 630, 810, 260, 520);     // doorway: top -> middle room
        addFloorRect(floorP1, 75, 955, 420, 800);      // middle room (white/cyan tiles, terminals)
        addFloorRect(floorP1, 630, 810, 700, 920);     // doorway: middle room -> hallway
        addFloorRect(floorP1, 75, 850, 800, 1010);     // connecting hallway
        addFloorRect(floorP1, 630, 810, 890, 1060);    // doorway: hallway -> bottom room
        addFloorRect(floorP1, 75, 850, 960, 1310);     // bottom server room

        gateP1 = imageRectToWorld(740, 1330, 880, 1260); // locked connector into center

        // --- Player 2 (right) room floor — mirrored horizontally from P1 ---
        for (Rectangle r : floorP1) {
            floorP2.add(mirrorX(r));
        }
        gateP2 = mirrorX(gateP1);

        // --- Shared center reactor chamber ---
        centerChamber = imageRectToWorld(700, 2050, 1200, 1530);

        allTracedFloor.addAll(floorP1);
        allTracedFloor.addAll(floorP2);
        allTracedFloor.add(gateP1);
        allTracedFloor.add(gateP2);
        allTracedFloor.add(centerChamber);
    }

    private void addFloorRect(List<Rectangle> list, float x1, float x2, float yTop, float yBottom) {
        list.add(imageRectToWorld(x1, x2, yTop, yBottom));
    }

    /** Converts an image-pixel rectangle (x1..x2, yTop..yBottom, Y down) to world space (Y up). */
    private Rectangle imageRectToWorld(float x1, float x2, float yTop, float yBottom) {
        float worldY = WORLD_H - yBottom;
        float height = yBottom - yTop;
        return new Rectangle(x1, worldY, x2 - x1, height);
    }

    private Rectangle mirrorX(Rectangle r) {
        float mirroredX = WORLD_W - r.x - r.width;
        return new Rectangle(mirroredX, r.y, r.width, r.height);
    }

    /** Called once Stage 3 is solved — opens both gates into the center chamber. */
    public void openGates() {
        gateP1Open = true;
        gateP2Open = true;
    }

    // TEMPORARY, per explicit request: no per-side restriction (P1 isn't confined to
    // floorP1, P2 isn't confined to floorP2, gates don't need to be solved) — but movement
    // is still confined to the union of every traced rectangle, not the whole rectangular
    // world border, so players can't wander into the black void between/around the
    // building's wings. Flip back to true once internal walls come back for real.
    private static final boolean INTERNAL_WALLS_ENABLED = false;

    @Override
    public boolean collides(float x, float y, float w, float h, int playerSide) {
        Rectangle box = new Rectangle(x, y, w, h);

        if (!INTERNAL_WALLS_ENABLED) {
            for (Rectangle r : allTracedFloor) if (r.contains(box)) return false;
            return true;
        }

        List<Rectangle> ownFloor = (playerSide == 1) ? floorP1 : floorP2;
        Rectangle ownGate = (playerSide == 1) ? gateP1 : gateP2;
        boolean ownGateOpen = (playerSide == 1) ? gateP1Open : gateP2Open;

        for (Rectangle r : ownFloor) if (r.contains(box)) return false;
        if (ownGateOpen && (ownGate.contains(box) || centerChamber.contains(box))) return false;

        return true; // outside this player's own walkable rectangles = solid
    }

    @Override
    public float getWorldWidth() { return WORLD_W; }

    @Override
    public float getWorldHeight() { return WORLD_H; }

    // Spawn points — inside the top room on each side.
    public float[] getSpawnP1() { return new float[]{700f, 1200f}; }
    public float[] getSpawnP2() { return new float[]{1952f, 1200f}; }

    // Terminal positions in the white/cyan middle room — matches the 3 panels in the
    // art. Placement only for now; Terminal.interact() wiring is a separate step.
    public float[][] getTerminalSpotsP1() {
        return new float[][] {
            imagePointToWorld(150, 560),
            imagePointToWorld(480, 560),
            imagePointToWorld(820, 560)
        };
    }

    public float[][] getTerminalSpotsP2() {
        float[][] p1Spots = getTerminalSpotsP1();
        float[][] mirrored = new float[p1Spots.length][2];
        for (int i = 0; i < p1Spots.length; i++) {
            mirrored[i][0] = WORLD_W - p1Spots[i][0] - 100f;
            mirrored[i][1] = p1Spots[i][1];
        }
        return mirrored;
    }

    private float[] imagePointToWorld(float imageX, float imageY) {
        return new float[]{imageX, WORLD_H - imageY};
    }

    public void render(SpriteBatch batch, ShapeRenderer shape, OrthographicCamera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(background, 0, 0, WORLD_W, WORLD_H);
        batch.end();

        // Visible markers at each terminal so they're actually findable in-game.
        shape.setProjectionMatrix(camera.combined);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0f, 0.9f, 1f, 0.85f);
        for (float[] spot : getTerminalSpotsP1()) drawTerminalMarker(shape, spot);
        for (float[] spot : getTerminalSpotsP2()) drawTerminalMarker(shape, spot);
        shape.end();
    }

    private void drawTerminalMarker(ShapeRenderer shape, float[] spot) {
        float centerX = spot[0] + 50f;
        float centerY = spot[1] + 50f;
        shape.circle(centerX, centerY, 22f, 24);
    }

    public void dispose() {
        background.dispose();
    }
}