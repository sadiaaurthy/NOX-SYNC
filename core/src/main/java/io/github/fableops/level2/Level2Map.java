package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import java.util.List;

import io.github.fableops.Collidable;
import io.github.fableops.Player;
import io.github.fableops.world.CollisionMask;

// Everything comes from the mask Level2Mapcollision.png:
// 000000 wall, 000FFF floor, FF00FF exit (closed until the core is placed)
// FFFF00 spawn, FFFFFF core pedestal, FEDCBA reactor socket
public class Level2Map implements Collidable {

    private static final byte CLASS_EXIT = 2;
    private static final byte CLASS_SPAWN = 3;
    private static final byte CLASS_CORE = 4;
    private static final byte CLASS_SOCKET = 5;

    // Class 0 has to be void, off-map reads as 0
    private static final int[][] PALETTE = {
        {0, 0, 0}, {0, 15, 255}, {255, 0, 255}, {255, 255, 0}, {255, 255, 255}, {254, 220, 186}
    };

    //                                           void   floor exit   spawn core   socket
    private static final boolean[] WALK_SEALED = {false, true, false, true, false, false};
    private static final boolean[] WALK_OPEN   = {false, true, true,  true, false, false};

    // The old crystal's tip sticks out 7px above the painted pedestal, so copy a bit more than that
    private static final int PEDESTAL_MARGIN = 16;

    private final Texture background;
    private final Texture emptyPedestal;
    private final Rectangle emptyPedestalArea;
    private final CollisionMask mask;
    private final float worldW;
    private final float worldH;
    private final Rectangle spawnZone;
    private final Rectangle exitZone;
    private final Rectangle coreZone;
    private final Rectangle socketZone;
    private boolean exitOpen = false;

    public Level2Map() {
        background = new Texture(Gdx.files.internal("Level2Map.png"));
        worldW = background.getWidth();
        worldH = background.getHeight();
        mask = new CollisionMask("Level2Mapcollision.png", worldW, worldH, PALETTE);

        List<Rectangle>[] zones = mask.findZones();
        spawnZone = singleZone(zones, CLASS_SPAWN, "spawn");
        exitZone = singleZone(zones, CLASS_EXIT, "exit gate");
        coreZone = singleZone(zones, CLASS_CORE, "core pedestal");
        socketZone = singleZone(zones, CLASS_SOCKET, "reactor socket");

        // Only the pedestal part of Level2MapWithoutCore.png is kept, not a second full map
        emptyPedestalArea = new Rectangle(coreZone.x - PEDESTAL_MARGIN, coreZone.y - PEDESTAL_MARGIN,
            coreZone.width + 2 * PEDESTAL_MARGIN, coreZone.height + 2 * PEDESTAL_MARGIN);
        int w = (int) emptyPedestalArea.width;
        int h = (int) emptyPedestalArea.height;
        Pixmap withoutCore = new Pixmap(Gdx.files.internal("Level2MapWithoutCore.png"));
        Pixmap pedestal = new Pixmap(w, h, withoutCore.getFormat());
        // Image rows go top-down but world Y goes bottom-up
        int srcY = (int) (worldH - emptyPedestalArea.y) - h;
        pedestal.drawPixmap(withoutCore, 0, 0, (int) emptyPedestalArea.x, srcY, w, h);
        emptyPedestal = new Texture(pedestal);
        withoutCore.dispose();
        pedestal.dispose();
    }

    private Rectangle singleZone(List<Rectangle>[] zones, byte cls, String what) {
        List<Rectangle> found = zones[cls];
        if (found.isEmpty()) {
            throw new IllegalStateException("Level2Mapcollision.png has no " + what + " painted in "
                + mask.hexOf(cls) + ". Re-export the mask layer of Level2Map.psd at 100% opacity.");
        }
        if (found.size() > 1) {
            Gdx.app.error("Level2Map", "Expected one " + what + " zone (" + mask.hexOf(cls)
                + "), found " + found.size() + "; using the first.");
        }
        return found.get(0);
    }

    // P1 gets the leftmost free spot on the spawn paint, P2 the rightmost
    public void placeAtSpawn(Player player, boolean leftmost) {
        float w = player.colliderWidth();
        float h = player.colliderHeight();
        float middleY = spawnZone.y + spawnZone.height / 2f;
        int firstX = (int) (spawnZone.x - w);
        int lastX = (int) (spawnZone.x + spawnZone.width);
        int step = leftmost ? 1 : -1;
        boolean found = false;
        float spotX = 0f, spotY = 0f;

        for (int x = leftmost ? firstX : lastX; !found && x >= firstX && x <= lastX; x += step) {
            float bestDy = Float.MAX_VALUE;
            for (int y = (int) (spawnZone.y - h); y <= (int) (spawnZone.y + spawnZone.height); y++) {
                if (mask.classAt(x + w / 2f, y + h / 2f) != CLASS_SPAWN) continue;
                if (mask.blocksBox(x, y, w, h, WALK_SEALED)) continue;
                float dy = Math.abs(y + h / 2f - middleY);
                if (dy < bestDy) {
                    bestDy = dy;
                    spotX = x;
                    spotY = y;
                    found = true;
                }
            }
        }
        if (!found) {
            throw new IllegalStateException("The spawn painted in Level2Mapcollision.png has no spot "
                + "a player fits in. Paint more spawn or floor around it.");
        }
        player.placeAt(spotX, spotY);
    }

    // Checks the paint itself, not the exit's bounding box
    public boolean isInExit(Player player) {
        return mask.classAt(player.colliderCentreX(), player.colliderCentreY()) == CLASS_EXIT;
    }

    public Rectangle getCoreZone() { return coreZone; }

    public Rectangle getSocketZone() { return socketZone; }

    public boolean isExitOpen() { return exitOpen; }

    public void openExit() { exitOpen = true; }

    public void closeExit() { exitOpen = false; }

    @Override
    public boolean collides(float x, float y, float w, float h, int playerSide) {
        return mask.blocksBox(x, y, w, h, exitOpen ? WALK_OPEN : WALK_SEALED);
    }

    @Override
    public float getWorldWidth() { return worldW; }

    @Override
    public float getWorldHeight() { return worldH; }

    public void render(SpriteBatch batch, OrthographicCamera camera, boolean coreTaken) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.draw(background, 0, 0, worldW, worldH);
        if (coreTaken) {
            batch.draw(emptyPedestal, emptyPedestalArea.x, emptyPedestalArea.y,
                emptyPedestalArea.width, emptyPedestalArea.height);
        }
        batch.end();
    }

    // Highlights what can be used right now. ShapeRenderer doesn't turn on blending by itself
    public void renderOverlays(ShapeRenderer shape, OrthographicCamera camera, CoreObject.State coreState) {
        Rectangle target = (coreState == CoreObject.State.ON_PEDESTAL) ? coreZone
            : (coreState == CoreObject.State.CARRIED) ? socketZone : null;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.setProjectionMatrix(camera.combined);
        for (ShapeRenderer.ShapeType type : OVERLAY_PASSES) {
            float alpha = (type == ShapeRenderer.ShapeType.Filled) ? 0.22f : 1f;
            shape.begin(type);
            if (target != null) {
                shape.setColor(0.2f, 0.8f, 1f, alpha);
                shape.rect(target.x, target.y, target.width, target.height);
            }
            if (exitOpen) {
                shape.setColor(1f, 0.16f, 0.43f, alpha);
                shape.rect(exitZone.x, exitZone.y, exitZone.width, exitZone.height);
            }
            shape.end();
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private static final ShapeRenderer.ShapeType[] OVERLAY_PASSES = {
        ShapeRenderer.ShapeType.Filled, ShapeRenderer.ShapeType.Line
    };

    // F1 debug overlay
    public void renderDebugCollision(SpriteBatch batch, OrthographicCamera camera) {
        mask.renderDebug(batch, camera);
    }

    public void dispose() {
        background.dispose();
        emptyPedestal.dispose();
        mask.dispose();
    }
}
