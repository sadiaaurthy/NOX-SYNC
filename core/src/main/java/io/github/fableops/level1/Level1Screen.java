package io.github.fableops.level1;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.Player;
import io.github.fableops.level1.controller.Level1Controller;
import io.github.fableops.level1.controller.Level1Listener;
import io.github.fableops.level1.network.AlertMeterUpdateMessage;
import io.github.fableops.level1.network.CodeFragmentPayload;
import io.github.fableops.level1.network.EnteredDigitMessage;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.ui.hud.CodePopupUI;

/**
 * Level 1 screen — same host/client/debug pattern as GameScreen, on Level1Map
 * instead of WorldMap, at 1.5x zoom.
 *
 * Movement still rides GameServer/GameClient (unchanged). The reactor puzzle
 * rides the separate HostSession/ClientSession channel in Host/Join modes.
 * Debug mode runs the puzzle fully locally (no network) via a second
 * Level1Controller with hostSession=null — either player's terminal can be
 * interacted with, since one process is driving both.
 */
public class Level1Screen implements Screen {

    private SpriteBatch batch;
    private ShapeRenderer shape;
    private BitmapFont font;
    private OrthographicCamera uiCamera;

    private Player player1;
    private Player player2;
    private Level1Map world;

    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;

    private Level1Controller controller; // host only
    private Level1Controller debugController; // debug only — same class, hostSession=null

    // Two independent popups, always. Host only ever opens popupP1 (host IS player 1
    // locally); client only ever opens popupP2 (client IS player 2 locally). Debug mode
    // is the one case both can be open at once, since a single person is driving both
    // terminals — debugFocusedPlayerId then decides which one currently reads the keyboard.
    private final CodePopupUI popupP1 = new CodePopupUI();
    private final CodePopupUI popupP2 = new CodePopupUI();
    private int debugFocusedPlayerId = 1;

    // Local puzzle state — populated via Level1Listener (host) or clientSession messages (client).
    private volatile CodeFragmentPayload myStageView;
    // Debug-mode-only: both players' views, since one process drives both terminals locally.
    private volatile CodeFragmentPayload debugP1View;
    private volatile CodeFragmentPayload debugP2View;
    private volatile int alertMeterValue = 0;
    private volatile boolean reactorUnlocked = false;
    private boolean nearTerminal = false; // drives the "Press E to interact" prompt

    private static final int DIVIDER = 4;
    private static final float CAM_W = 640f; // 1.5x zoom (960 / 1.5)
    private static final float CAM_H = 720f; // 1.5x zoom (1080 / 1.5)
    private static final float INTERACT_RANGE = 80f;

    public Level1Screen(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession) {
        this.server  = server;
        this.client  = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.isHost  = (server != null);
        this.isDebug = (server == null && client == null);

        batch = new SpriteBatch();
        shape = new ShapeRenderer();
        font = new BitmapFont();
        // Back-buffer size, not getWidth()/getHeight() — those are logical points and
        // diverge from the physical pixels glViewport() needs whenever the display has
        // OS-level scaling (125%/150% etc.), which was leaving stale content on screen.
        uiCamera = new OrthographicCamera(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        uiCamera.position.set(Gdx.graphics.getBackBufferWidth() / 2f, Gdx.graphics.getBackBufferHeight() / 2f, 0);
        uiCamera.update();

        world = new Level1Map();

        float[] spawnP1 = world.getSpawnP1();
        float[] spawnP2 = world.getSpawnP2();

        // P1 — always WASD
        player1 = new Player(
            spawnP1[0], spawnP1[1],
            new Color(0.20f, 0.55f, 0.75f, 1f),
            new Color(0.00f, 0.95f, 0.95f, 1f),
            Input.Keys.W, Input.Keys.S,
            Input.Keys.A, Input.Keys.D,
            world, CAM_W, CAM_H, 1
        );

        // P2 — WASD on its own device when networked, arrow keys only in Debug (shared keyboard)
        player2 = new Player(
            spawnP2[0], spawnP2[1],
            new Color(0.38f, 0.18f, 0.65f, 1f),
            new Color(0.90f, 0.25f, 0.85f, 1f),
            Input.Keys.UP,   Input.Keys.DOWN,
            Input.Keys.LEFT, Input.Keys.RIGHT,
            world, CAM_W, CAM_H, 2
        );

        player1.setTexture("Walking.png", "Running.png");
        player2.setTexture("Hacker_walking.png", "Hacker_run.png");

        if (isHost && hostSession != null) {
            setupHostPuzzle();
        } else if (!isHost && !isDebug && clientSession != null) {
            setupClientPuzzle();
        } else if (isDebug) {
            setupDebugPuzzle();
        }
    }

    private void setupHostPuzzle() {
        Level1Listener listener = new Level1Listener() {
            @Override
            public void onLocalView(CodeFragmentPayload payload) {
                myStageView = payload;
                if (popupP1.isOpen()) popupP1.updatePayload(payload);
            }

            @Override
            public void onRemoteView(CodeFragmentPayload payload) {
                // Host doesn't need P2's view — the client fetches it over the network.
            }

            @Override
            public void onAlertMeterChanged(int value) {
                alertMeterValue = value;
            }

            @Override
            public void onDigitAccepted(int positionIndex) {
                // no extra feedback yet
            }

            @Override
            public void onReactorUnlocked() {
                reactorUnlocked = true;
                world.openGates();
            }

            @Override
            public void onLevelRestart() {
                reactorUnlocked = false;
                popupP1.close();
            }
        };

        controller = new Level1Controller(hostSession, listener);
        hostSession.setListener(controller.asMessageListener());
        popupP1.setSubmitListener((positionIndex, guess) -> controller.submitFromLocalPlayer(positionIndex, guess));
        controller.startLevel();
    }

    private void setupClientPuzzle() {
        clientSession.setListener((type, body) -> {
            switch (type) {
                case "CODE_FRAGMENT":
                    CodeFragmentPayload payload = CodeFragmentPayload.deserialize(body);
                    myStageView = payload;
                    if (popupP2.isOpen()) popupP2.updatePayload(payload);
                    break;
                case "ALERT_METER_UPDATE":
                    alertMeterValue = AlertMeterUpdateMessage.deserialize(body).getValue();
                    break;
                case "REACTOR_UNLOCK":
                    reactorUnlocked = true;
                    world.openGates();
                    break;
                case "LEVEL_RESTART":
                    reactorUnlocked = false;
                    popupP2.close();
                    break;
                case "DIGIT_ACCEPTED":
                    // no extra feedback yet
                    break;
                default:
                    break;
            }
        });
        popupP2.setSubmitListener((positionIndex, guess) ->
            clientSession.send(new EnteredDigitMessage(positionIndex, guess, 2))
        );
    }

    private void setupDebugPuzzle() {
        Level1Listener listener = new Level1Listener() {
            @Override
            public void onLocalView(CodeFragmentPayload payload) {
                debugP1View = payload;
                if (popupP1.isOpen()) popupP1.updatePayload(payload);
            }

            @Override
            public void onRemoteView(CodeFragmentPayload payload) {
                debugP2View = payload;
                if (popupP2.isOpen()) popupP2.updatePayload(payload);
            }

            @Override
            public void onAlertMeterChanged(int value) {
                alertMeterValue = value;
            }

            @Override
            public void onDigitAccepted(int positionIndex) {
                // no extra feedback yet
            }

            @Override
            public void onReactorUnlocked() {
                reactorUnlocked = true;
                world.openGates();
            }

            @Override
            public void onLevelRestart() {
                reactorUnlocked = false;
                popupP1.close();
                popupP2.close();
            }
        };

        debugController = new Level1Controller(null, listener); // no HostSession — fully local
        popupP1.setSubmitListener((positionIndex, guess) ->
            debugController.submitFromLocalPlayer(positionIndex, guess));
        popupP2.setSubmitListener((positionIndex, guess) ->
            debugController.handleEnteredDigit(new EnteredDigitMessage(positionIndex, guess, 2)));
        debugController.startLevel();
    }

    @Override
    public void render(float delta) {
        boolean anyPopupOpen = popupP1.isOpen() || popupP2.isOpen();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !anyPopupOpen) Gdx.app.exit();

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient(delta);
        }

        handlePuzzleInteraction();
        drawWorld();
        drawUI();
    }

    // D mode — no network, both players local on one window
    private void updateAsDebug(float delta) {
        if (!popupP1.isOpen()) {
            player1.update(delta); // WASD
        }
        if (!popupP2.isOpen()) {
            player2.update(delta); // arrow keys
        }
    }

    private void updateAsHost(float delta) {
        if (!popupP1.isOpen()) {
            player1.update(delta);
        }

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

        server.pushState(new WorldState(
            player1.x, player1.y,
            player2.x, player2.y
        ));
    }

    private void updateAsClient(float delta) {
        // Client is a separate physical device — no keyboard conflict, so WASD like P1.
        PlayerInput myInput = popupP2.isOpen()
            ? new PlayerInput(false, false, false, false)
            : new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.W),
                Gdx.input.isKeyPressed(Input.Keys.S),
                Gdx.input.isKeyPressed(Input.Keys.A),
                Gdx.input.isKeyPressed(Input.Keys.D)
            );
        client.pushInput(myInput);

        // apply state received from host
        WorldState state = client.pollState();
        player1.x = state.p1x;
        player1.y = state.p1y;
        player2.x = state.p2x;
        player2.y = state.p2y;

        player1.updateCamera();
        player2.updateCamera();
    }

    private void handlePuzzleInteraction() {
        if (isDebug) {
            handleDebugPuzzleInteraction();
            return;
        }

        CodePopupUI myPopup = isHost ? popupP1 : popupP2;
        if (myPopup.isOpen()) {
            myPopup.handleInput();
            return;
        }

        Player localPlayer = isHost ? player1 : player2;
        float[][] terminals = isHost ? world.getTerminalSpotsP1() : world.getTerminalSpotsP2();

        nearTerminal = isNearAnyTerminal(localPlayer, terminals);
        if (nearTerminal && myStageView != null && Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            myPopup.open(myStageView, isHost ? 1 : 2);
        }
    }

    // Debug mode drives both terminals from one keyboard, so both popups can be open
    // at once (otherwise a solo tester could never satisfy a stage that needs both
    // players' inputs). SPACE swaps which one currently reads the keyboard when both
    // are open — every other key stays reserved for whichever popup is focused, so
    // typing a digit never leaks into the other terminal's input.
    private void handleDebugPuzzleInteraction() {
        boolean p1Near = isNearAnyTerminal(player1, world.getTerminalSpotsP1());
        boolean p2Near = isNearAnyTerminal(player2, world.getTerminalSpotsP2());
        nearTerminal = (p1Near && !popupP1.isOpen()) || (p2Near && !popupP2.isOpen());

        if (!popupP1.isOpen() && p1Near && debugP1View != null && Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            popupP1.open(debugP1View, 1);
            debugFocusedPlayerId = 1;
        }
        if (!popupP2.isOpen() && p2Near && debugP2View != null && Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            popupP2.open(debugP2View, 2);
            debugFocusedPlayerId = 2;
        }

        boolean bothOpen = popupP1.isOpen() && popupP2.isOpen();
        if (bothOpen && Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            debugFocusedPlayerId = (debugFocusedPlayerId == 1) ? 2 : 1;
        }

        if (popupP1.isOpen() && (!bothOpen || debugFocusedPlayerId == 1)) popupP1.handleInput();
        if (popupP2.isOpen() && (!bothOpen || debugFocusedPlayerId == 2)) popupP2.handleInput();
    }

    private boolean isNearAnyTerminal(Player player, float[][] terminals) {
        for (float[] spot : terminals) {
            float dx = (player.x + Player.SIZE / 2f) - (spot[0] + Player.SIZE / 2f);
            float dy = (player.y + Player.SIZE / 2f) - (spot[1] + Player.SIZE / 2f);
            if (dx * dx + dy * dy <= INTERACT_RANGE * INTERACT_RANGE) {
                return true;
            }
        }
        return false;
    }

    private void drawWorld() {
        int screenW = Gdx.graphics.getBackBufferWidth();
        int screenH = Gdx.graphics.getBackBufferHeight();
        int half    = (screenW - DIVIDER) / 2;

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // left half — P1 camera
        Gdx.gl.glViewport(0, 0, half, screenH);
        world.render(batch, shape, player1.camera);

        batch.setProjectionMatrix(player1.camera.combined);
        batch.begin();
        player1.draw(batch);
        player2.draw(batch);
        batch.end();

        // right half — P2 camera
        Gdx.gl.glViewport(half + DIVIDER, 0, half, screenH);
        world.render(batch, shape, player2.camera);

        batch.setProjectionMatrix(player2.camera.combined);
        batch.begin();
        player1.draw(batch);
        player2.draw(batch);
        batch.end();

        Gdx.gl.glViewport(0, 0, screenW, screenH);
    }

    private void drawUI() {
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);

        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, "Alert Meter: " + alertMeterValue, 20, Gdx.graphics.getBackBufferHeight() - 20);
        if (reactorUnlocked) {
            font.setColor(Color.GREEN);
            font.draw(batch, "Reactor unlocked! Find the exit gate.", 20, Gdx.graphics.getBackBufferHeight() - 45);
        } else {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, "Find your terminal and solve it together with your partner to unlock the reactor.",
                20, Gdx.graphics.getBackBufferHeight() - 45);
        }
        if (nearTerminal) {
            font.setColor(Color.CYAN);
            font.draw(batch, "Press E to interact", 20, Gdx.graphics.getBackBufferHeight() - 70);
        }
        if (isDebug && popupP1.isOpen() && popupP2.isOpen()) {
            font.setColor(Color.ORANGE);
            font.draw(batch, "SPACE — switch terminal (focus: player " + debugFocusedPlayerId + ")",
                20, Gdx.graphics.getBackBufferHeight() - 95);
        }
        batch.end();

        popupP1.render(shape, batch);
        popupP2.render(shape, batch);
    }

    @Override
    public void show() {}

    @Override
    public void resize(int w, int h) {
        // Ignore the passed logical w/h — re-read the back buffer directly so this
        // stays in the same physical-pixel space as the glViewport calls in drawWorld().
        uiCamera.setToOrtho(false, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {}

    @Override
    public void dispose() {
        batch.dispose();
        shape.dispose();
        font.dispose();
        popupP1.dispose();
        popupP2.dispose();
        player1.dispose();
        player2.dispose();
        world.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}