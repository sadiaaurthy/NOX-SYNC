package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import io.github.fableops.Player;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level2.loot.LootField;
import io.github.fableops.level2.loot.LootDrop;
import io.github.fableops.level2.network.CoreInteractRequestMessage;
import io.github.fableops.level2.network.CoreStateMessage;
import io.github.fableops.level2.network.LootInteractRequestMessage;
import io.github.fableops.level2.network.LootPickedUpMessage;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.ui.SplitScreen;
import io.github.fableops.ui.UiViewport;

// Level 2: carry the core from the pedestal to the socket, then leave through the exit together
public class Level2Screen implements Screen, SplitScreen.HalfRenderer {

    private static final String TITLE = "Unstable Core Maze";

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shape = new ShapeRenderer();
    private final BitmapFont font = new BitmapFont();
    private final UiViewport ui = new UiViewport();
    private final PlayerInventories inventories = new PlayerInventories();
    private final Level2Map world = new Level2Map();
    private final CoreObject core = new CoreObject(inventories);
    private final LootField loot = new LootField();
    private final Player player1;
    private final Player player2;

    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;
    // null on the client
    private final Level2Controller controller;

    private String localPrompt; // what E does for this machine's player right now, or null
    private boolean exitReached = false;
    private boolean debugCollisionVisible = false; // F1
    private boolean disposed = false;

    public Level2Screen(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession) {
        this.server = server;
        this.client = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.isHost = (server != null);
        this.isDebug = (server == null && client == null);

        font.getData().setScale(SplitScreen.HUD_FONT_SCALE);
        SplitScreen.smoothFont(font);

        player1 = new Player("brawlspritesheet.png", "brawlspritesheetAttacking.png", 0f, 0f,
            Input.Keys.W, Input.Keys.S, Input.Keys.A, Input.Keys.D, world, 1);
        player2 = new Player("hackerspritesheet.png", "hackerspritesheetAttacking.png", 0f, 0f,
            Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT, Input.Keys.RIGHT, world, 2);
        player2.setAlternateRightKey(Input.Keys.L);
        SplitScreen.fitCameras(player1, player2);
        world.placeAtSpawn(player1, true);
        world.placeAtSpawn(player2, false);

        // Replace Level 1's listeners, that screen is disposed
        if (isHost || isDebug) {
            controller = new Level2Controller(hostSession, core, loot, inventories, world, player1, player2);
            if (hostSession != null) hostSession.setListener(controller.asMessageListener());
        } else {
            controller = null;
                        clientSession.setListener((type, body) -> {
                if ("CORE_STATE".equals(type)) {
                    CoreStateMessage message = CoreStateMessage.deserialize(body);
                    Gdx.app.postRunnable(() -> {
                        core.set(message.getState(), message.getCarrierId());
                        if (message.getState() == CoreObject.State.IN_SOCKET) world.openExit();
                    });
                } else if ("LOOT_PICKED_UP".equals(type)) {
                    LootPickedUpMessage message = LootPickedUpMessage.deserialize(body);
                    Gdx.app.postRunnable(() -> loot.applyPickup(message.getLootId(), message.getPlayerId(), inventories));
                }
            });
        }
    }

    @Override
    public void render(float delta) {
        // ESC closes an open inventory first
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !inventories.anyOpen()) {
            Gdx.app.exit();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;
        if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) ui.cycleScale();

        inventories.handleInput(isHost || isDebug, !isHost || isDebug, false, player1, player2);
        core.update(delta);
        loot.update(delta);
        player1.updateVisualState(delta);
        player2.updateVisualState(delta);

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient(delta);
        }

        handleInteraction();
        if (!exitReached && world.isExitOpen()) {
            exitReached = world.isInExit(player1) && world.isInExit(player2);
        }
        SplitScreen.drawHalves(this, player1, player2);
        drawUI();
    }

    // An open inventory freezes only its own player.
    // Attacks have nothing to hit until Level 2 gets its enemies
    private void updateAsDebug(float delta) {
        boolean p1Free = !inventories.isOpen(1);
        boolean p2Free = !inventories.isOpen(2);
        if (p1Free) player1.update(delta); // WASD
        if (p2Free) player2.update(delta); // arrow keys
        if (p1Free && Gdx.input.isKeyPressed(Input.Keys.F)) player1.startAttack();
        if (p2Free && Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) player2.startAttack();
    }

    private void updateAsHost(float delta) {
        boolean p1Free = !inventories.isOpen(1);
        if (p1Free) player1.update(delta);
        PlayerInput p2Input = server.isClientConnected()
            ? server.pollClientInput()
            : new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.UP),
                Gdx.input.isKeyPressed(Input.Keys.DOWN),
                Gdx.input.isKeyPressed(Input.Keys.LEFT),
                Gdx.input.isKeyPressed(Input.Keys.RIGHT),
                Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT));
        player2.applyInput(p2Input, delta);
        if (p1Free && Gdx.input.isKeyPressed(Input.Keys.F)) player1.startAttack();
        if (p2Input.attack) player2.startAttack();
        server.pushState(new WorldState(player1, player2));
    }

    private void updateAsClient(float delta) {
        // The client is its own machine, so its player uses WASD and F.
        client.pushInput(inventories.isOpen(2)
            ? new PlayerInput(false, false, false, false)
            : new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.W),
                Gdx.input.isKeyPressed(Input.Keys.S),
                Gdx.input.isKeyPressed(Input.Keys.A),
                Gdx.input.isKeyPressed(Input.Keys.D),
                Gdx.input.isKeyPressed(Input.Keys.F)));
        client.pollState().applyTo(player1, player2);
    }

    // E takes or places the core. G picks up loot. The client only asks, the host decides
    private void handleInteraction() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            if (isDebug) {
                controller.interactCore(1);
                controller.interactCore(2);
            } else if (isHost) {
                controller.interactCore(1);
            } else if (core.prompt(world, player2, 2) != null) {
                clientSession.send(new CoreInteractRequestMessage());
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.G)) {
            if (isDebug) {
                pickUpLoot(1);
                pickUpLoot(2);
            } else if (isHost) {
                pickUpLoot(1);
            } else {
                LootDrop drop = loot.findReachablePickup(player2, core.getState());
                if (drop != null) clientSession.send(new LootInteractRequestMessage(drop.getId()));
            }
        }
        localPrompt = prompt(isHost || isDebug ? player1 : player2, isHost || isDebug ? 1 : 2);
        if (localPrompt == null && isDebug) localPrompt = prompt(player2, 2);
    }

    private void pickUpLoot(int playerId) {
        Player player = (playerId == 1) ? player1 : player2;
        LootDrop drop = loot.findReachablePickup(player, core.getState());
        if (drop != null) controller.interactLoot(playerId, drop.getId());
    }

    private String prompt(Player player, int playerId) {
        String corePrompt = core.prompt(world, player, playerId);
        return corePrompt != null ? corePrompt : loot.prompt(player, core.getState());
    }

    // Called once per camera by SplitScreen.drawHalves()
    @Override
    public void drawHalf(OrthographicCamera camera) {
        world.render(batch, camera, core.getState() != CoreObject.State.ON_PEDESTAL);
        world.renderOverlays(shape, camera, core.getState());
        if (debugCollisionVisible) world.renderDebugCollision(batch, camera);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        player1.draw(batch);
        player2.draw(batch);
        batch.end();

        drawLoot(camera);
        drawCore(camera);
    }

    // Only drawn in the socket: on the pedestal it's part of the map image, and while carried it's in the inventory
    private void drawCore(OrthographicCamera camera) {
        if (core.getState() != CoreObject.State.IN_SOCKET) return;
        Rectangle socket = world.getSocketZone();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.setProjectionMatrix(camera.combined);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        core.draw(shape, socket.x + socket.width / 2f, socket.y + socket.height / 2f);
        shape.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

       private void drawLoot(OrthographicCamera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        loot.render(batch, core.getState());
        batch.end();
    }

    private void drawUI() {
        OrthographicCamera uiCamera = ui.camera();
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);
        float margin = SplitScreen.HUD_MARGIN;
        float step = SplitScreen.HUD_LINE_STEP;
        float rowY = ui.height() - margin;

        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, TITLE, margin, rowY);
        rowY -= step;
        font.setColor(world.isExitOpen() ? Color.GREEN : Color.LIGHT_GRAY);
        font.draw(batch, objective(), margin, rowY);
        rowY -= step;
        if (localPrompt != null) {
            font.setColor(Color.CYAN);
            font.draw(batch, localPrompt, margin, rowY);
        }
        batch.end();

        SplitScreen.drawHealthBars(shape, ui.width(), rowY - step, player1, player2);
        inventories.render(shape, batch, ui.width(), ui.height(), player1, player2,
            SplitScreen.ACCENT_P1, SplitScreen.ACCENT_P2);
    }

    private String objective() {
        if (exitReached) return "Exit reached. Level 3 isn't built yet.";
        switch (core.getState()) {
            case ON_PEDESTAL: return "Find the Unstable Core on its pedestal and take it.";
            case CARRIED:     return "Carry the core to the reactor socket.";
            default:          return "Exit gate open! Both of you step inside it.";
        }
    }

    @Override
    public void show() {}

    @Override
    public void resize(int w, int h) {
        // Uses the back buffer size instead of w and h (HiDPI)
        ui.update();
        SplitScreen.fitCameras(player1, player2);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {}

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        batch.dispose();
        shape.dispose();
        font.dispose();
        inventories.dispose();
        player1.dispose();
        player2.dispose();
        world.dispose();
        core.dispose();
        loot.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}
