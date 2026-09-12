package io.github.fableops.level1;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.fableops.Collidable;

/**
 * Level 1's map — a single illustrated background image, with collision read per-pixel
 * from a painted mask (see {@link CollisionMask}) rather than hand-traced rectangles.
 *
 * The rectangle lists this class used to carry are gone entirely. They could only
 * approximate the art, needed a player-width overlap at every room seam to avoid
 * invisible walls, and imposed a minimum corridor width. Overlapping-rectangle
 * bookkeeping doesn't exist any more: the painted colour at a point *is* the answer.
 *
 * Y is flipped throughout: image rows run top-to-bottom, world space bottom-to-top.
 */
public class Level1Map implements Collidable {

    public static final float WORLD_W = 2752f;
    public static final float WORLD_H = 1536f;

    /**
     * Fraction of the hitbox sampled per axis. A 3x3 grid (corners, edge midpoints and
     * centre) is enough to stop a player squeezing through a wall thinner than their
     * body, while still letting them slide along it — Player moves each axis separately,
     * so a blocked X still permits the Y step.
     */
    private static final float[] SAMPLE_FRACTIONS = {0f, 0.5f, 1f};

    /** Level 1 has three puzzle stages, so three terminals per side. */
    private static final int STAGE_COUNT = 3;

    // Terminal highlight: bright yellow at 50% opacity, per the art direction. The mask
    // paints them neon green (00FF11) purely so they're distinguishable while authoring —
    // the mask is never shown to players, so the two colours are unrelated by design.
    private static final float GLOW_R = 1f;
    private static final float GLOW_G = 0.95f;
    private static final float GLOW_B = 0.1f;
    private static final float GLOW_ALPHA = 0.5f;

    private final Texture background;
    private final CollisionMask mask;

    // Still explicit rectangles rather than mask colours. Terminals now come from the mask
    // (see splitTerminalZones), but the plates and the exit gate zone are not painted yet —
    // CLASS_PLATE has zero pixels in the current export, so reading them from the mask
    // would silently produce no plates at all.
    private final Rectangle exitGateZone;
    private final Rectangle pressurePlateP1;
    private final Rectangle pressurePlateP2;

    private boolean exitGateOpen = false;
    private boolean reactorUnlocked = false;

    /**
     * The painted terminal zones, and the centre of each. Both are read out of the mask
     * at construction instead of being hand-typed: the coordinates used to be literals
     * that had drifted ~60 units away from where the art actually put the consoles, which
     * left Stage 1's terminal permanently outside interaction range. Painting them is now
     * the single source of truth — move the paint and the game follows.
     */
    private final List<Rectangle> terminalZonesP1 = new ArrayList<>();
    private final List<Rectangle> terminalZonesP2 = new ArrayList<>();

    public Level1Map() {
        background = new Texture(Gdx.files.internal("Level1Map.png"));
        mask = new CollisionMask("Level1Mapcollision.png", WORLD_W, WORLD_H);

        splitTerminalZones();

        exitGateZone = imageRectToWorld(1280, 1470, 1470, 1536);
        // The two wired consoles flanking the reactor, used as the pressure plates.
        pressurePlateP1 = imageRectToWorld(1078, 1248, 1190, 1280);
        pressurePlateP2 = imageRectToWorld(1552, 1722, 1190, 1280);
    }

    public Rectangle getPressurePlateP1() { return pressurePlateP1; }

    public Rectangle getPressurePlateP2() { return pressurePlateP2; }

    /** Called once both players are stood on their plates at the same time. */
    public void openExitGate() { exitGateOpen = true; }

    /**
     * Called once Stage 3 is solved. Opens the gate-coloured floor and, just as
     * importantly, drops the wing restriction: from here both players may walk the cyan
     * wing, the magenta wing and the shared room alike. Until this point each is sealed
     * into their own side, which is what makes the puzzle need two people.
     */
    public void unlockReactor() { reactorUnlocked = true; }

    /**
     * Converts a rectangle given in image-pixel coordinates (Y down) to world space
     * (Y up). These constants were authored against the original full-size art, whose
     * pixel dimensions matched WORLD_W x WORLD_H exactly, so they are already in world
     * units — unlike the collision mask, which is sampled by ratio and so may be any size.
     */
    private Rectangle imageRectToWorld(float x1, float x2, float yTop, float yBottom) {
        return new Rectangle(x1, WORLD_H - yBottom, x2 - x1, yBottom - yTop);
    }

    @Override
    public boolean collides(float x, float y, float w, float h, int playerSide) {
        // Any sampled point landing on a non-walkable surface blocks the whole move.
        for (float fx : SAMPLE_FRACTIONS) {
            float sampleX = x + fx * (w - 1f);
            for (float fy : SAMPLE_FRACTIONS) {
                float sampleY = y + fy * (h - 1f);
                if (!mask.isWalkable(sampleX, sampleY, playerSide, reactorUnlocked)) return true;
            }
        }
        return false;
    }

    @Override
    public float getWorldWidth() { return WORLD_W; }

    @Override
    public float getWorldHeight() { return WORLD_H; }

    // Spawn points — inside the top room on each side.
    public float[] getSpawnP1() { return new float[]{700f, 1200f}; }
    public float[] getSpawnP2() { return new float[]{1952f, 1200f}; }

    // Terminal positions in the white/cyan middle room — matches the 3 panels in the art.
    // Built once in the constructor: these never change, but the getters are called from
    // both the render loop (twice, once per split-screen camera) and the interaction
    // check, so rebuilding the arrays on every call was allocating ~30 short-lived
    // arrays per frame purely to hand back constants.
    /**
     * The painted terminal zones in world space, ordered by stage.
     *
     * Handed out as bounds rather than centres deliberately. A console is painted into
     * the wall it hangs on, so its centre sits ~45 units inside solid rock that no player
     * can ever stand in — measuring interaction range from there charges the player for
     * the console's own depth. Callers measure to the nearest point on the rectangle.
     */
    public List<Rectangle> getTerminalZonesP1() { return terminalZonesP1; }

    public List<Rectangle> getTerminalZonesP2() { return terminalZonesP2; }

    /**
     * Sorts the painted terminal blobs into each player's wing and orders them by stage.
     *
     * Stage N uses terminal index N-1, and the ordering runs outward-in on both sides:
     * P1 left-to-right, P2 right-to-left. That mirrors the original hardcoded layout, so
     * players still start at the terminal furthest from the reactor and work inward.
     */
    private void splitTerminalZones() {
        List<Rectangle> all = mask.findZones(CollisionMask.CLASS_TERM);
        for (Rectangle zone : all) {
            if (zone.x + zone.width / 2f < WORLD_W / 2f) terminalZonesP1.add(zone);
            else terminalZonesP2.add(zone);
        }
        terminalZonesP1.sort(Comparator.comparingDouble(r -> r.x));
        terminalZonesP2.sort(Comparator.comparingDouble(r -> -r.x));

        // A miscounted mask means unreachable stages, which is otherwise a silent and
        // very confusing failure — so say so loudly at load rather than at play time.
        if (terminalZonesP1.size() != STAGE_COUNT || terminalZonesP2.size() != STAGE_COUNT) {
            Gdx.app.error("Level1Map", "Expected " + STAGE_COUNT + " painted terminal zones per side ("
                + CollisionMask.describeClass(CollisionMask.CLASS_TERM) + "), found "
                + terminalZonesP1.size() + " for P1 and " + terminalZonesP2.size() + " for P2. "
                + "Stages without a terminal cannot be opened. Check the mask export is flat "
                + "(no layer opacity or blending) and that every console is painted.");
        }
    }

    /**
     * Squared distance from a point to the nearest point on a rectangle, 0 when inside.
     * Squared so callers can compare against a squared range without a sqrt per frame.
     */
    public static float distanceSquaredToZone(Rectangle zone, float px, float py) {
        float dx = Math.max(Math.max(zone.x - px, 0f), px - (zone.x + zone.width));
        float dy = Math.max(Math.max(zone.y - py, 0f), py - (zone.y + zone.height));
        return dx * dx + dy * dy;
    }

    /** The illustrated background. Overlays are a separate pass — see renderOverlays(). */
    public void render(SpriteBatch batch, OrthographicCamera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(background, 0, 0, WORLD_W, WORLD_H);
        batch.end();
    }

    /**
     * Every interactive marker on the map: the yellow wash over each painted terminal
     * zone, and both pressure plates lit up while a player stands on one. Without these
     * they are all just background art with no sign that anything can be used.
     *
     * Terminals and plates share one filled pass and one line pass rather than opening
     * four of their own. Every ShapeRenderer begin/end flushes the pipeline and rebinds
     * the shader, and this runs once per split-screen camera — so four passes cost eight
     * flushes a frame to draw roughly a dozen rectangles.
     *
     * Zones are drawn at their actual painted bounds rather than as fixed-size markers, so
     * a highlight always matches whatever is in the mask. Blending is enabled explicitly:
     * ShapeRenderer does not manage it, and without this the 50% alpha would silently
     * render as solid wherever a previous SpriteBatch had left blending disabled.
     */
    public void renderOverlays(ShapeRenderer shape, OrthographicCamera camera,
                               boolean p1Standing, boolean p2Standing) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.setProjectionMatrix(camera.combined);

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(GLOW_R, GLOW_G, GLOW_B, GLOW_ALPHA);
        drawZones(shape, terminalZonesP1);
        drawZones(shape, terminalZonesP2);
        shape.setColor(0f, 0.9f, 1f, p1Standing ? 0.45f : 0.18f);
        shape.rect(pressurePlateP1.x, pressurePlateP1.y, pressurePlateP1.width, pressurePlateP1.height);
        shape.setColor(1f, 0.16f, 0.43f, p2Standing ? 0.45f : 0.18f);
        shape.rect(pressurePlateP2.x, pressurePlateP2.y, pressurePlateP2.width, pressurePlateP2.height);
        shape.end();

        // Solid outlines at full alpha keep the edges crisp — a 50% fill alone reads as a
        // vague smear against the lit floor underneath.
        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(GLOW_R, GLOW_G, GLOW_B, 1f);
        drawZones(shape, terminalZonesP1);
        drawZones(shape, terminalZonesP2);
        shape.setColor(0f, 0.9f, 1f, 1f);
        shape.rect(pressurePlateP1.x, pressurePlateP1.y, pressurePlateP1.width, pressurePlateP1.height);
        shape.setColor(1f, 0.16f, 0.43f, 1f);
        shape.rect(pressurePlateP2.x, pressurePlateP2.y, pressurePlateP2.width, pressurePlateP2.height);
        if (exitGateOpen) {
            shape.setColor(0.2f, 1f, 0.4f, 1f);
            shape.rect(exitGateZone.x, exitGateZone.y, exitGateZone.width, exitGateZone.height);
        }
        shape.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Indexed loop — the enhanced-for over an ArrayList allocates an Iterator per call. */
    private static void drawZones(ShapeRenderer shape, List<Rectangle> zones) {
        for (int i = 0; i < zones.size(); i++) {
            Rectangle zone = zones.get(i);
            shape.rect(zone.x, zone.y, zone.width, zone.height);
        }
    }

    /**
     * Overlays the collision mask itself on the world, so painted surfaces can be
     * compared directly against the art. Replaces the old rectangle outlines, which no
     * longer exist — what you see here is exactly what collides() reads.
     */
    public void renderDebugCollision(SpriteBatch batch, OrthographicCamera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.setColor(1f, 1f, 1f, 0.5f);
        batch.draw(mask.getDebugTexture(), 0, 0, WORLD_W, WORLD_H);
        batch.setColor(1f, 1f, 1f, 1f);
        batch.end();
    }

    public void dispose() {
        background.dispose();
        mask.dispose();
    }
}
