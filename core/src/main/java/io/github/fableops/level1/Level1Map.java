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
import io.github.fableops.world.CollisionMask;

// Collision, terminals and the exit gate all come from Level1Mapcollision.png
public class Level1Map implements Collidable {

    public static final float WORLD_W = 2752f;
    public static final float WORLD_H = 1536f;

    private static final byte CLASS_GATE = 4;
    private static final byte CLASS_TERM = 6;

    // void, reactor room, P1 wing, P2 wing, gate, plate, terminal
    // Terminal (00FF11) and plate (00FF00) are very close, so the mask has to be exported flat
    private static final int[][] PALETTE = {
        {0, 0, 0}, {255, 255, 255}, {0, 255, 255}, {255, 0, 255},
        {255, 255, 0}, {0, 255, 0}, {0, 255, 17}
    };

    // Each player is sealed into their own wing until all three stages are solved. The reactor
    // room in the middle is shut to both of them until then, and the gate out of it only opens
    // once both plates are held
    //                                              void   room   P1     P2     gate   plate  term
    private static final boolean[] WALK_P1_LOCKED = {false, false, true,  false, false, true, true};
    private static final boolean[] WALK_P2_LOCKED = {false, false, false, true,  false, true, true};
    private static final boolean[] WALK_UNLOCKED  = {false, true,  true,  true,  false, true, true};
    private static final boolean[] WALK_GATE_OPEN = {false, true,  true,  true,  true,  true, true};

    private static final int STAGE_COUNT = 3;

    // Terminal highlight: yellow at 50%
    private static final float GLOW_R = 1f;
    private static final float GLOW_G = 0.95f;
    private static final float GLOW_B = 0.1f;
    private static final float GLOW_ALPHA = 0.5f;

    private static final float[] SPAWN_P1 = {700f, 1200f};
    private static final float[] SPAWN_P2 = {1952f, 1200f};

    private final Texture background;
    private final CollisionMask mask;
    private final Rectangle exitGateZone;
    // The plates aren't painted in the mask yet
    private final Rectangle pressurePlateP1;
    private final Rectangle pressurePlateP2;
    // Ordered by stage
    private final List<Rectangle> terminalZonesP1 = new ArrayList<>();
    private final List<Rectangle> terminalZonesP2 = new ArrayList<>();

    private boolean exitGateOpen = false;
    private boolean reactorUnlocked = false;

    public Level1Map() {
        background = new Texture(Gdx.files.internal("Level1Map.png"));
        mask = new CollisionMask("Level1Mapcollision.png", WORLD_W, WORLD_H, PALETTE);

        List<Rectangle>[] zones = mask.findZones();
        splitTerminalZones(zones[CLASS_TERM]);
        if (zones[CLASS_GATE].isEmpty()) {
            throw new IllegalStateException("Level1Mapcollision.png has no exit gate painted in "
                + mask.hexOf(CLASS_GATE) + ".");
        }
        exitGateZone = zones[CLASS_GATE].get(0);

        // The two wired consoles flanking the reactor.
        pressurePlateP1 = imageRectToWorld(1078, 1248, 1190, 1280);
        pressurePlateP2 = imageRectToWorld(1552, 1722, 1190, 1280);
    }

    // Image coordinates (Y down) to world coordinates (Y up)
    private static Rectangle imageRectToWorld(float x1, float x2, float yTop, float yBottom) {
        return new Rectangle(x1, WORLD_H - yBottom, x2 - x1, yBottom - yTop);
    }

    // Ordered from the outside in, so stage 1 is the terminal furthest from the reactor
    private void splitTerminalZones(List<Rectangle> terminals) {
        for (Rectangle zone : terminals) {
            if (zone.x + zone.width / 2f < WORLD_W / 2f) terminalZonesP1.add(zone);
            else terminalZonesP2.add(zone);
        }
        terminalZonesP1.sort(Comparator.comparingDouble(r -> r.x));
        terminalZonesP2.sort(Comparator.comparingDouble(r -> -r.x));

        if (terminalZonesP1.size() != STAGE_COUNT || terminalZonesP2.size() != STAGE_COUNT) {
            Gdx.app.error("Level1Map", "Expected " + STAGE_COUNT + " terminal zones per side ("
                + mask.hexOf(CLASS_TERM) + "), found " + terminalZonesP1.size() + " for P1 and "
                + terminalZonesP2.size() + " for P2. Stages without a terminal cannot be opened. "
                + "Check the mask export is flat and every console is painted.");
        }
    }

    public float[] getSpawnP1() { return SPAWN_P1; }

    public float[] getSpawnP2() { return SPAWN_P2; }

    public List<Rectangle> getTerminalZonesP1() { return terminalZonesP1; }

    public List<Rectangle> getTerminalZonesP2() { return terminalZonesP2; }

    public Rectangle getPressurePlateP1() { return pressurePlateP1; }

    public Rectangle getPressurePlateP2() { return pressurePlateP2; }

    public Rectangle getExitGateZone() { return exitGateZone; }

    public boolean isExitGateOpen() { return exitGateOpen; }

    public void openExitGate() { exitGateOpen = true; }

    public void unlockReactor() { reactorUnlocked = true; }

    public void resetProgress() {
        reactorUnlocked = false;
        exitGateOpen = false;
    }

    @Override
    public boolean collides(float x, float y, float w, float h, int playerSide) {
        boolean[] walkable = exitGateOpen ? WALK_GATE_OPEN
            : reactorUnlocked ? WALK_UNLOCKED
            : (playerSide == 2) ? WALK_P2_LOCKED : WALK_P1_LOCKED;
        return mask.blocksBox(x, y, w, h, walkable);
    }

    @Override
    public float getWorldWidth() { return WORLD_W; }

    @Override
    public float getWorldHeight() { return WORLD_H; }

    public void render(SpriteBatch batch, OrthographicCamera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(background, 0, 0, WORLD_W, WORLD_H);
        batch.end();
    }

    // One filled and one line pass for everything, since each begin/end is a flush
    // ShapeRenderer doesn't turn on blending by itself
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
        drawZone(shape, pressurePlateP1);
        shape.setColor(1f, 0.16f, 0.43f, p2Standing ? 0.45f : 0.18f);
        drawZone(shape, pressurePlateP2);
        if (exitGateOpen) {
            shape.setColor(0.2f, 1f, 0.4f, 0.25f);
            drawZone(shape, exitGateZone);
        }
        shape.end();

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(GLOW_R, GLOW_G, GLOW_B, 1f);
        drawZones(shape, terminalZonesP1);
        drawZones(shape, terminalZonesP2);
        shape.setColor(0f, 0.9f, 1f, 1f);
        drawZone(shape, pressurePlateP1);
        shape.setColor(1f, 0.16f, 0.43f, 1f);
        drawZone(shape, pressurePlateP2);
        if (exitGateOpen) {
            shape.setColor(0.2f, 1f, 0.4f, 1f);
            drawZone(shape, exitGateZone);
        }
        shape.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private static void drawZones(ShapeRenderer shape, List<Rectangle> zones) {
        for (int i = 0; i < zones.size(); i++) drawZone(shape, zones.get(i));
    }

    private static void drawZone(ShapeRenderer shape, Rectangle zone) {
        shape.rect(zone.x, zone.y, zone.width, zone.height);
    }

    // F1 debug overlay
    public void renderDebugCollision(SpriteBatch batch, OrthographicCamera camera) {
        mask.renderDebug(batch, camera);
    }

    public void dispose() {
        background.dispose();
        mask.dispose();
    }
}
