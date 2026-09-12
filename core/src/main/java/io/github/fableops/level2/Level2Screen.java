package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.LobbyScreen;
import io.github.fableops.Main;
import io.github.fableops.Player;
import io.github.fableops.level2.network.CoreInteractRequestMessage;
import io.github.fableops.level2.network.CoreStateMessage;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;

/**
 * Level 2 screen — Unstable Core Maze. Same host/client/debug pattern as Level1Screen,
 * on Level2Map (pixel-mask collision) instead of Level1Map. Movement still rides the
 * same GameServer/GameClient (unchanged); Core pickup/drop rides the same generic
 * HostSession/ClientSession channel Level1Screen used for its puzzle.
 *
 * This is the exploration/combat-hooks/Core foundation only — no enemy spawning, no
 * altar placement, no mission-failed/restart flow yet. Those are explicitly deferred
 * (loot, timer, pressure plates, Level 3) pending review.
 */
public class Level2Screen implements Screen {

    private SpriteBatch batch;
    private ShapeRenderer shape;
    private BitmapFont font;
    private OrthographicCamera uiCamera;

    private Player player1;
    private Player player2;
    private Level2Map world;
    private CoreObject core;

    private final Main game;
    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;

    private Level2Controller controller;      // host only
    private Level2Controller debugController; // debug only — same class, hostSession=null

    private boolean nearInteractableP1 = false;
    private boolean nearInteractableP2 = false;
    private String interactPromptP1 = "";
    private String interactPromptP2 = "";

    private boolean debugCollisionVisible = false; // F1 toggles the mask overlay
    private boolean disposed = false;
    private boolean returnedToMenu = false; // guards returnToMainMenu() against running twice

    private static final int DIVIDER = 4;
    private static final float CAM_W = 640f; // matches Level1Screen's 1.5x zoom
    private static final float CAM_H = 720f;

    public Level2Screen(Main game, GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession) {
        this.game = game;
        this.server = server;
        this.client = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.isHost = (server != null);
        this.isDebug = (server == null && client == null);

        batch = new SpriteBatch();
        shape = new ShapeRenderer();
        font = new BitmapFont();
        smoothFont(font);

        uiCamera = new OrthographicCamera(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        uiCamera.position.set(Gdx.graphics.getBackBufferWidth() / 2f, Gdx.graphics.getBackBufferHeight() / 2f, 0);
        uiCamera.update();

        world = new Level2Map();

        // Placeholder Core spawn — the plaza just south of the map's central crystal
        // pedestal art. Final placement (and the altar it eventually goes to) is a
        // future step; this is only the foundation.
        float[] corePos = world.findWalkableWorldPoint(700, 550, 400);
        core = new CoreObject(corePos[0], corePos[1]);

        float[] spawnP1 = world.getSpawnP1();
        float[] spawnP2 = world.getSpawnP2();

        player1 = new Player(
            spawnP1[0], spawnP1[1],
            new Color(0.20f, 0.55f, 0.75f, 1f),
            new Color(0.00f, 0.95f, 0.95f, 1f),
            Input.Keys.W, Input.Keys.S,
            Input.Keys.A, Input.Keys.D,
            world, CAM_W, CAM_H, 1
        );

        player2 = new Player(
            spawnP2[0], spawnP2[1],
            new Color(0.38f, 0.18f, 0.65f, 1f),
            new Color(0.90f, 0.25f, 0.85f, 1f),
            Input.Keys.UP,   Input.Keys.DOWN,
            Input.Keys.LEFT, Input.Keys.RIGHT,
            world, CAM_W, CAM_H, 2
        );

        // Same sprite files Level1Screen uses — filenames are unchanged, per spec.
        player1.setTexture("brawlspritesheet.png");
        player2.setTexture("brawlspritesheet.png");
        player2.setAlternateRightKey(Input.Keys.L);

        if (isHost && hostSession != null) {
            setupHost();
        } else if (!isHost && !isDebug && clientSession != null) {
            setupClient();
        } else if (isDebug) {
            setupDebug();
        }
    }

    private void setupHost() {
        controller = new Level2Controller(hostSession, core);
        hostSession.setListener(controller.asMessageListener());
        controller.broadcastState(); // initial sync so a joined client sees the real Core position immediately
    }

    private void setupClient() {
        clientSession.setListener((type, body) -> {
            if ("CORE_STATE".equals(type)) {
                CoreStateMessage message = CoreStateMessage.deserialize(body);
                core.applyRemoteState(message.getState(), message.getX(), message.getY(), message.getCarrierPlayerId());
            }
        });
    }

    private void setupDebug() {
        debugController = new Level2Controller(null, core);
    }

    /**
     * No pause/mission-failed flow exists yet for Level 2, so ESC always leaves for the
     * main menu — same single navigation path Level1Screen.returnToMainMenu() uses, so
     * return-to-lobby stays centralized in one place per screen rather than scattered
     * exit-app calls. Guarded so a second ESC in the same/later frame can't set a screen
     * twice or double-dispose this one.
     */
    private void returnToMainMenu() {
        if (returnedToMenu) return;
        returnedToMenu = true;
        game.setScreen(new LobbyScreen(game));
        dispose();
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            returnToMainMenu();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;

        core.update(delta);

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient(delta);
        }

        handleInteraction();
        drawWorld();
        drawUI();
    }

    private void updateAsDebug(float delta) {
        player1.update(delta); // WASD
        player2.update(delta); // arrow keys

        if (core.getState() == CoreObject.State.CARRIED) {
            Player carrier = (core.getCarrierPlayerId() == 1) ? player1 : player2;
            debugController.followCarrier(carrier.x, carrier.y);
        }
    }

    private void updateAsHost(float delta) {
        player1.update(delta);

        PlayerInput p2Input;
        if (server.isClientConnected()) {
            p2Input = server.pollClientInput();
        } else {
            p2Input = new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.UP),
                Gdx.input.isKeyPressed(Input.Keys.DOWN),
                Gdx.input.isKeyPressed(Input.Keys.LEFT),
                Gdx.input.isKeyPressed(Input.Keys.RIGHT)
            );
        }
        player2.applyInput(p2Input, delta);

        if (core.getState() == CoreObject.State.CARRIED) {
            Player carrier = (core.getCarrierPlayerId() == 1) ? player1 : player2;
            controller.followCarrier(carrier.x, carrier.y);
        }

        server.pushState(new WorldState(
            player1.x, player1.y,
            player2.x, player2.y
        ));
    }

    private void updateAsClient(float delta) {
        PlayerInput myInput = new PlayerInput(
            Gdx.input.isKeyPressed(Input.Keys.W),
            Gdx.input.isKeyPressed(Input.Keys.S),
            Gdx.input.isKeyPressed(Input.Keys.A),
            Gdx.input.isKeyPressed(Input.Keys.D)
        );
        client.pushInput(myInput);

        WorldState state = client.pollState();
        player1.x = state.p1x;
        player1.y = state.p1y;
        player2.x = state.p2x;
        player2.y = state.p2y;

        player1.updateCamera();
        player2.updateCamera();
    }

    private void handleInteraction() {
        if (isDebug) {
            handleDebugInteraction();
            return;
        }

        Player localPlayer = isHost ? player1 : player2;
        int localPlayerId = isHost ? 1 : 2;
        boolean inRange = core.isInRange(localPlayer.x, localPlayer.y, Player.SIZE);
        String prompt = inRange ? core.getInteractionPrompt(localPlayerId) : "";
        boolean canAct = inRange && !prompt.isEmpty();

        if (isHost) {
            nearInteractableP1 = canAct;
            interactPromptP1 = prompt;
        } else {
            nearInteractableP2 = canAct;
            interactPromptP2 = prompt;
        }

        if (canAct && Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            if (isHost) {
                controller.tryInteract(1);
            } else {
                clientSession.send(new CoreInteractRequestMessage(2));
            }
        }
    }

    // Both players share one keyboard in Debug — P1 takes priority if both happen to be
    // in range at once, same first-come convention Level1Screen's debug terminal focus uses.
    private void handleDebugInteraction() {
        boolean p1InRange = core.isInRange(player1.x, player1.y, Player.SIZE);
        boolean p2InRange = core.isInRange(player2.x, player2.y, Player.SIZE);
        interactPromptP1 = p1InRange ? core.getInteractionPrompt(1) : "";
        interactPromptP2 = p2InRange ? core.getInteractionPrompt(2) : "";
        nearInteractableP1 = !interactPromptP1.isEmpty();
        nearInteractableP2 = !interactPromptP2.isEmpty();

        if (!Gdx.input.isKeyJustPressed(Input.Keys.E)) return;
        if (nearInteractableP1) {
            debugController.tryInteract(1);
        } else if (nearInteractableP2) {
            debugController.tryInteract(2);
        }
    }

    private void drawWorld() {
        int screenW = Gdx.graphics.getBackBufferWidth();
        int screenH = Gdx.graphics.getBackBufferHeight();
        int half    = (screenW - DIVIDER) / 2;

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // left half — P1 camera
        Gdx.gl.glViewport(0, 0, half, screenH);
        drawHalf(player1.camera);

        // right half — P2 camera
        Gdx.gl.glViewport(half + DIVIDER, 0, half, screenH);
        drawHalf(player2.camera);

        Gdx.gl.glViewport(0, 0, screenW, screenH);
    }

    private void drawHalf(OrthographicCamera camera) {
        world.render(batch, camera);
        if (debugCollisionVisible) world.renderDebugCollision(batch, camera);

        shape.setProjectionMatrix(camera.combined);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        core.draw(shape);
        shape.end();

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        player1.draw(batch);
        player2.draw(batch);
        batch.end();
    }

    private void drawHealthBar(float x, float y, float health, Color color) {
        float width = 150f, height = 16f;
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(Color.DARK_GRAY);
        shape.rect(x, y, width, height);
        shape.setColor(color);
        shape.rect(x, y, width * (health / Player.MAX_HEALTH), height);
        shape.end();
    }

    private void drawUI() {
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);

        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, "Unstable Core Maze", 20, Gdx.graphics.getBackBufferHeight() - 20);
        String prompt = isHost ? interactPromptP1
            : (isDebug ? "" : interactPromptP2);
        if (!prompt.isEmpty()) {
            font.setColor(Color.CYAN);
            font.draw(batch, prompt, 20, Gdx.graphics.getBackBufferHeight() - 45);
        }
        if (isDebug) {
            if (!interactPromptP1.isEmpty()) {
                font.setColor(Color.CYAN);
                font.draw(batch, "P1: " + interactPromptP1, 20, Gdx.graphics.getBackBufferHeight() - 45);
            }
            if (!interactPromptP2.isEmpty()) {
                font.setColor(Color.MAGENTA);
                font.draw(batch, "P2: " + interactPromptP2, 20, Gdx.graphics.getBackBufferHeight() - 70);
            }
        }
        batch.end();

        // Drawn once per split-screen half — same reasoning as Level1Screen's own bars.
        float half = (Gdx.graphics.getBackBufferWidth() - DIVIDER) / 2f;
        float barY = Gdx.graphics.getBackBufferHeight() - 115;
        drawHealthBar(20, barY, player1.health, Color.CYAN);
        drawHealthBar(190, barY, player2.health, Color.MAGENTA);
        drawHealthBar(half + DIVIDER + 20, barY, player1.health, Color.CYAN);
        drawHealthBar(half + DIVIDER + 190, barY, player2.health, Color.MAGENTA);
    }

    private static void smoothFont(BitmapFont f) {
        f.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        f.setUseIntegerPositions(false);
    }

    @Override
    public void show() {}

    @Override
    public void resize(int w, int h) {
        uiCamera.setToOrtho(false, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
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
        player1.dispose();
        player2.dispose();
        world.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}
