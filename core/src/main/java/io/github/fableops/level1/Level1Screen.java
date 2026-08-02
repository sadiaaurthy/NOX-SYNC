package io.github.fableops.level1;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import java.util.ArrayList;
import java.util.List;

import io.github.fableops.Enemy;
import io.github.fableops.Player;
import io.github.fableops.SwarmController;
import io.github.fableops.level1.controller.Level1Controller;
import io.github.fableops.level1.controller.Level1Listener;
import io.github.fableops.level1.model.AlertMeter;
import io.github.fableops.level1.network.AlertMeterUpdateMessage;
import io.github.fableops.level1.network.CodeFragmentPayload;
import io.github.fableops.level1.network.EnemyStateMessage;
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
    private boolean debugCollisionVisible = false; // F1 toggles collision rectangle overlay
    private boolean level1Complete = false; // set true when both players reach the exit gate
    private BitmapFont bannerFont;
    private final SwarmController swarmController = new SwarmController();
    // Client-only mirror of the host's swarm — the client never simulates enemies
    // itself, it just renders whatever ENEMY_STATE last reported.
    private final List<float[]> remoteEnemiesP1 = new ArrayList<>();
    private final List<float[]> remoteEnemiesP2 = new ArrayList<>();
    private boolean missionFailed = false;
    private String missionFailedReason = "";
    private static final float ATTACK_RANGE = 70f;
    private static final int ATTACK_DAMAGE = 15;
    private static final int CONTACT_DAMAGE = 10;

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
        bannerFont=new BitmapFont();
        bannerFont.getData().setScale(3f);
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
            Input.Keys.LEFT, Input.Keys.L,
            world, CAM_W, CAM_H, 2
        );

        // hackerspritesheet.png continues the existing "Hacker" naming for P2;
        // brawlspritesheet.png takes the remaining slot for P1.
        player1.setTexture("brawlspritesheet.png");
        player2.setTexture("hackerspritesheet.png");

        if (isHost && hostSession != null) {
            setupHostPuzzle();
        } else if (!isHost && !isDebug && clientSession != null) {
            setupClientPuzzle();
        } else if (isDebug) {
            setupDebugPuzzle();
        }
    }

    // Shared by every failure trigger (alert-max on host/debug, alert-max/health detected
    // independently on the client) so the popups can't stay open feeding in more input
    // once the mission is over, and so the banner only ever shows the first cause.
    private void triggerMissionFailed(String reason) {
        if (missionFailed) return;
        missionFailed = true;
        missionFailedReason = reason;
        popupP1.close();
        popupP2.close();
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

            public void onWrongAnswer(int offendingPlayerId)
            {
                float x=  (offendingPlayerId == 1) ? player1.x : player2.x;
                float y=  (offendingPlayerId == 1) ? player1.y : player2.y;
                swarmController.spawnWave(offendingPlayerId, x, y, world);

            }

            @Override
            public void onReactorUnlocked() {
                reactorUnlocked = true;
                world.openGates();
            }

            @Override

            public void onMissionFailed()
            {
                triggerMissionFailed("Alert Meter maxed out!");
            }

            @Override
            public void onLevelRestart() {
                reactorUnlocked = false;
                popupP1.close();
                swarmController.reset();
                player1.health = Player.MAX_HEALTH;
                player2.health = Player.MAX_HEALTH;
                missionFailed = false;
                missionFailedReason = "";
                float[] spawnP1 = world.getSpawnP1();
                float[] spawnP2 = world.getSpawnP2();
                player1.x = spawnP1[0]; player1.y = spawnP1[1];
                player2.x = spawnP2[0]; player2.y = spawnP2[1];
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
                    if (alertMeterValue >= AlertMeter.MAX_VALUE) triggerMissionFailed("Alert Meter maxed out!");
                    break;
                case "REACTOR_UNLOCK":
                    reactorUnlocked = true;
                    world.openGates();
                    break;
                case "ENEMY_STATE":
                    EnemyStateMessage enemyState = EnemyStateMessage.deserialize(body);
                    remoteEnemiesP1.clear();
                    remoteEnemiesP1.addAll(enemyState.getEnemiesP1());
                    remoteEnemiesP2.clear();
                    remoteEnemiesP2.addAll(enemyState.getEnemiesP2());
                    player1.health = enemyState.getHealthP1();
                    player2.health = enemyState.getHealthP2();
                    if (player1.health <= 0 || player2.health <= 0) triggerMissionFailed("A player was eliminated!");
                    break;
                case "LEVEL_RESTART":
                    reactorUnlocked = false;
                    popupP2.close();
                    remoteEnemiesP1.clear();
                    remoteEnemiesP2.clear();
                    player1.health = Player.MAX_HEALTH;
                    player2.health = Player.MAX_HEALTH;
                    missionFailed = false;
                    missionFailedReason = "";
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
            public void onWrongAnswer(int offendingPlayerId) {
                float x = (offendingPlayerId == 1) ? player1.x : player2.x;
                float y = (offendingPlayerId == 1) ? player1.y : player2.y;
                swarmController.spawnWave(offendingPlayerId, x, y, world);
            }

            @Override
            public void onReactorUnlocked() {
                reactorUnlocked = true;
                world.openGates();
            }

            @Override

            public void onMissionFailed()
            {
                triggerMissionFailed("Alert Meter maxed out!");
            }

            @Override
            public void onLevelRestart() {
                reactorUnlocked = false;
                popupP1.close();
                popupP2.close();
                swarmController.reset();
                player1.health = Player.MAX_HEALTH;
                player2.health = Player.MAX_HEALTH;
                missionFailed = false;
                missionFailedReason = "";
                float[] spawnP1 = world.getSpawnP1();
                float[] spawnP2 = world.getSpawnP2();
                player1.x = spawnP1[0]; player1.y = spawnP1[1];
                player2.x = spawnP2[0]; player2.y = spawnP2[1];
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
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;

        // Only the host/debug side actually owns the puzzle state, so only they can
        // acknowledge the banner and trigger a real restart; a joined client just waits
        // for the resulting LEVEL_RESTART message.
        if (missionFailed && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            if (isHost) controller.restartLevel1();
            else if (isDebug) debugController.restartLevel1();
        }

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient(delta);
        }

        handlePuzzleInteraction();
        checkLevel1Complete();
        updateSwarm(delta);
        drawWorld();
        drawUI();
    }

    // Host and Debug simulate the swarm locally (host-authoritative); a joined client
    // never simulates, it only renders whatever the host last sent via ENEMY_STATE.
    private void updateSwarm(float delta) {
        if ((isHost || isDebug) && !missionFailed) {
            swarmController.update(delta, player1, player2, world);

            // Contact damage-per-second while touching, not per-frame, so it's framerate independent.
            if (swarmController.isTouchingAny(1, player1.x, player1.y, Player.SIZE, Player.SIZE)) {
                player1.takeDamage(CONTACT_DAMAGE * delta);
            }
            if (swarmController.isTouchingAny(2, player2.x, player2.y, Player.SIZE, Player.SIZE)) {
                player2.takeDamage(CONTACT_DAMAGE * delta);
            }
            if (player1.health <= 0 || player2.health <= 0) {
                triggerMissionFailed("A player was eliminated!");
            }
        }
        if (isHost && hostSession != null) {
            hostSession.send(new EnemyStateMessage(
                toPositions(swarmController.getEnemiesP1()),
                toPositions(swarmController.getEnemiesP2()),
                player1.health,
                player2.health
            ));
        }
    }

    private List<float[]> toPositions(List<Enemy> enemies) {
        List<float[]> positions = new ArrayList<>(enemies.size());
        for (Enemy e : enemies) positions.add(new float[]{e.x, e.y});
        return positions;
    }

    private void checkLevel1Complete()
    {
        if(level1Complete) return; // already completed, no need to check again
        Rectangle exitZone= world.getExitGateZone();
        Rectangle p1Box= new Rectangle(player1.x, player1.y, Player.SIZE, Player.SIZE);
        Rectangle p2Box= new Rectangle(player2.x, player2.y, Player.SIZE, Player.SIZE);
        if(exitZone.overlaps(p1Box) && exitZone.overlaps(p2Box))
        {
            level1Complete=true;
        }
    }

    // D mode — no network, both players local on one window
    private void updateAsDebug(float delta) {
        if (!popupP1.isOpen() && !missionFailed) {
            player1.update(delta); // WASD
        }
        if (!popupP2.isOpen() && !missionFailed) {
            player2.update(delta); // arrow keys
        }
    }

    private void updateAsHost(float delta) {
        if (!popupP1.isOpen() && !missionFailed) {
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

        if (!missionFailed) {
            player2.applyInput(p2Input, delta);
        }

        server.pushState(new WorldState(
            player1.x, player1.y,
            player2.x, player2.y
        ));
    }

    private void updateAsClient(float delta) {
        // Client is a separate physical device — no keyboard conflict, so WASD like P1.
        PlayerInput myInput = (popupP2.isOpen() || missionFailed)
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

        nearTerminal = isNearStageTerminal(localPlayer, terminals, myStageView);
        if (nearTerminal && Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            myPopup.open(myStageView, isHost ? 1 : 2);
        }
    }

    // Debug mode drives both terminals from one keyboard, so both popups can be open
    // at once (otherwise a solo tester could never satisfy a stage that needs both
    // players' inputs). SPACE swaps which one currently reads the keyboard when both
    // are open — every other key stays reserved for whichever popup is focused, so
    // typing a digit never leaks into the other terminal's input.
    private void handleDebugPuzzleInteraction() {
        boolean p1Near = isNearStageTerminal(player1, world.getTerminalSpotsP1(), debugP1View);
        boolean p2Near = isNearStageTerminal(player2, world.getTerminalSpotsP2(), debugP2View);
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

    // Each of the 3 terminal spots is dedicated to one stage (matches the 3 physical
    // console panels drawn in the art) — being near terminal 0 only ever opens Stage 1,
    // terminal 1 only Stage 2, terminal 2 only Stage 3, instead of any terminal working
    // for whichever stage happens to be active.
    private boolean isNearStageTerminal(Player player, float[][] terminals, CodeFragmentPayload view) {
        if (view == null) return false;
        int index = view.getStageNumber() - 1;
        if (index < 0 || index >= terminals.length) return false;

        float[] spot = terminals[index];
        float dx = (player.x + Player.SIZE / 2f) - (spot[0] + Player.SIZE / 2f);
        float dy = (player.y + Player.SIZE / 2f) - (spot[1] + Player.SIZE / 2f);
        return dx * dx + dy * dy <= INTERACT_RANGE * INTERACT_RANGE;
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
        if (debugCollisionVisible) world.renderDebugCollision(shape, player1.camera);
        drawEnemies(player1.camera);

        batch.setProjectionMatrix(player1.camera.combined);
        batch.begin();
        player1.draw(batch);
        player2.draw(batch);
        batch.end();

        // right half — P2 camera
        Gdx.gl.glViewport(half + DIVIDER, 0, half, screenH);
        world.render(batch, shape, player2.camera);
        if (debugCollisionVisible) world.renderDebugCollision(shape, player2.camera);
        drawEnemies(player2.camera);

        batch.setProjectionMatrix(player2.camera.combined);
        batch.begin();
        player1.draw(batch);
        player2.draw(batch);
        batch.end();

        Gdx.gl.glViewport(0, 0, screenW, screenH);
    }

    private void drawEnemies(OrthographicCamera camera) {
        shape.setProjectionMatrix(camera.combined);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        if (isHost || isDebug) {
            for (Enemy e : swarmController.getEnemiesP1()) e.draw(shape);
            for (Enemy e : swarmController.getEnemiesP2()) e.draw(shape);
        } else {
            shape.setColor(Color.RED);
            for (float[] pos : remoteEnemiesP1) shape.rect(pos[0], pos[1], Enemy.SIZE, Enemy.SIZE);
            for (float[] pos : remoteEnemiesP2) shape.rect(pos[0], pos[1], Enemy.SIZE, Enemy.SIZE);
        }
        shape.end();
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

        // Drawn once per split-screen half — otherwise both bars land inside the left
        // half only, since the window is split but this UI pass uses absolute screen coords.
        float half = (Gdx.graphics.getBackBufferWidth() - DIVIDER) / 2f;
        float barY = Gdx.graphics.getBackBufferHeight() - 115;
        drawHealthBar(20, barY, player1.health, Color.CYAN);
        drawHealthBar(190, barY, player2.health, Color.MAGENTA);
        drawHealthBar(half + DIVIDER + 20, barY, player1.health, Color.CYAN);
        drawHealthBar(half + DIVIDER + 190, barY, player2.health, Color.MAGENTA);

        popupP1.render(shape, batch);
        popupP2.render(shape, batch);

        if(level1Complete) drawLevelCompleteBanner();
        if(missionFailed) drawMissionFailedBanner();
    }

    private void drawLevelCompleteBanner()
    {
        float screenW= Gdx.graphics.getBackBufferWidth();
        float screenH= Gdx.graphics.getBackBufferHeight();

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0f, 0f, 0f, 0.75f);
        shape.rect(0,0, screenW, screenH);
        shape.end();

        String msg="Level 1 Complete!\n\n" +
                "Both players reached the exit gate.\n\n" +
                "Press ESC to exit.";

        GlyphLayout layout= new GlyphLayout(bannerFont, msg);
        batch.begin();
        bannerFont.setColor(Color.GREEN);
        bannerFont.draw(batch, layout, (screenW-layout.width)/2f, (screenH+layout.height)/2f);
        batch.end();
    }

    private void drawMissionFailedBanner()
    {
        float screenW= Gdx.graphics.getBackBufferWidth();
        float screenH= Gdx.graphics.getBackBufferHeight();

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0f, 0f, 0f, 0.75f);
        shape.rect(0,0, screenW, screenH);
        shape.end();

        String restartHint = (isHost || isDebug) ? "Press ENTER to restart." : "Waiting for host to restart...";
        String msg = "Mission Failed!\n\n" + missionFailedReason + "\n\n" + restartHint;

        GlyphLayout layout= new GlyphLayout(bannerFont, msg);
        batch.begin();
        bannerFont.setColor(Color.RED);
        bannerFont.draw(batch, layout, (screenW-layout.width)/2f, (screenH+layout.height)/2f);
        batch.end();
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
        bannerFont.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}