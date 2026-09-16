package io.github.fableops.level1;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import io.github.fableops.EnemySprites;
import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.SwarmController;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level1.controller.Level1Controller;
import io.github.fableops.level1.controller.Level1Listener;
import io.github.fableops.level1.model.AlertMeter;
import io.github.fableops.level1.network.AlertMeterUpdateMessage;
import io.github.fableops.level1.network.CodeFragmentPayload;
import io.github.fableops.level1.network.EnteredDigitMessage;
import io.github.fableops.level1.network.Level2StartMessage;
import io.github.fableops.level2.Level2Screen;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.messages.EnemyStateMessage;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.story.StoryBeat;
import io.github.fableops.story.StoryGate;
import io.github.fableops.ui.SplitScreen;
import io.github.fableops.ui.UiViewport;
import io.github.fableops.ui.hud.CodePopupUI;
import io.github.fableops.ui.hud.Hud;

// Level 1: solve the terminals, hold both plates, then walk through the exit gate together
// Movement goes through GameServer/GameClient, everything else through the message channel
public class Level1Screen implements Screen, SplitScreen.HalfRenderer {

    private static final float ATTACK_RANGE = 120f;
    private static final int ATTACK_DAMAGE = 15;
    private static final int CONTACT_DAMAGE = 10; // per second of contact
    // Enemy updates are sent 20 times a second, not every frame
    private static final float ENEMY_STATE_INTERVAL = 1f / 20f;
    // A fallen player's death animation plays out before the mission failed window
    private static final float FAILURE_SCENE_DELAY = Player.DEATH_DURATION + 0.4f;
    private static final String ALERT_MAXED = "The alert meter maxed out.";
    private static final String DEBUG_FOCUS_P1 = "SPACE = switch terminal   (typing into: player 1)";
    private static final String DEBUG_FOCUS_P2 = "SPACE = switch terminal   (typing into: player 2)";
    private static final float DARKNESS_MASK_SIZE = 2800f;

    private final Game game;
    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shape = new ShapeRenderer();
    // Shared by the HUD and the terminals, each sets its own scale
    private final BitmapFont font = new BitmapFont();
    private final UiViewport ui = new UiViewport();
    private final PlayerInventories inventories = new PlayerInventories();
    private final Level1Map world = new Level1Map();
    private final Hud hud = new Hud();
    private final EnemySprites enemySprites = new EnemySprites();
    private final SwarmController swarm = new SwarmController(enemySprites);
    private final Texture darknessMask = createDarknessMask();
    private final Player player1;
    private final Player player2;
    // null on the client
    private final Level1Controller controller;
    // Side 1's operator. Side 2 always gets the other one, and Level 2 inherits the pair
    private final Role sideOneRole;

    // The host uses popupP1 and the client popupP2, debug can have both open
    private final CodePopupUI popupP1 = new CodePopupUI(font);
    private final CodePopupUI popupP2 = new CodePopupUI(font);
    private int debugFocusedPlayerId = 1;
    private CodeFragmentPayload viewP1;
    private CodeFragmentPayload viewP2;

    private int alertMeterValue = 0;
    private String alertLabel = "Alert Meter: 0"; // rebuilt only when the value changes
    private boolean reactorUnlocked = false;
    private boolean nearTerminal = false;
    private boolean debugCollisionVisible = false; // F1
    private boolean plateP1Held = false;
    private boolean plateP2Held = false;
    private boolean missionFailed = false;
    private boolean failureScenePending = false;
    private float failureSceneTimer = 0f;
    private String failureCause = "";
    private boolean advancing = false; // guards advanceToLevel2() against running twice
    private boolean disposed = false;

    private float enemyStateTimer = 0f;
    // Client only: the host's swarm as last reported.
    private final List<float[]> remoteEnemiesP1 = new ArrayList<>();
    private final List<float[]> remoteEnemiesP2 = new ArrayList<>();
    // Host only, reused for every enemy snapshot
    private final List<float[]> positionBufferP1 = new ArrayList<>();
    private final List<float[]> positionBufferP2 = new ArrayList<>();
    // Holds the game on a scenario window until both players confirm it
    private final StoryGate story;

    public Level1Screen(Game game, GameServer server, GameClient client, HostSession hostSession,
                        ClientSession clientSession, StoryGate story, Role sideOneRole) {
        this.game = game;
        this.sideOneRole = sideOneRole;
        this.server = server;
        this.client = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.story = story;
        this.isHost = (server != null);
        this.isDebug = (server == null && client == null);

        SplitScreen.smoothFont(font);

        float[] spawnP1 = world.getSpawnP1();
        float[] spawnP2 = world.getSpawnP2();
        player1 = new Player(sideOneRole.sheetName(), spawnP1[0], spawnP1[1],
            Input.Keys.W, Input.Keys.S, Input.Keys.A, Input.Keys.D, world, 1);
        // Arrow keys are only used in debug, a real client sends its own WASD
        player2 = new Player(sideOneRole.other().sheetName(), spawnP2[0], spawnP2[1],
            Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT, Input.Keys.RIGHT, world, 2);
        player2.setAlternateRightKey(Input.Keys.L);
        SplitScreen.fitCameras(player1, player2);

        if (isHost || isDebug) {
            controller = new Level1Controller(hostSession, new PuzzleListener());
            if (hostSession != null) hostSession.setListener(story.wrap(controller.asMessageListener()));
            popupP1.setSubmitListener(controller::submitFromLocalPlayer);
            if (isDebug) {
                popupP2.setSubmitListener((position, guess) ->
                    controller.handleEnteredDigit(new EnteredDigitMessage(position, guess, 2)));
            }
            controller.startLevel();
        } else {
            controller = null;
            // Messages come in on the network thread, so handle them on the render thread
            clientSession.setListener(story.wrap((type, body) ->
                Gdx.app.postRunnable(() -> onHostMessage(type, body))));
            popupP2.setSubmitListener((position, guess) ->
                clientSession.send(new EnteredDigitMessage(position, guess, 2)));
        }
    }

    private static Texture createDarknessMask() {
        int size = 512;
        Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);

        float cx = size / 2f;
        float cy = size / 2f;

        // Player vision radius in world units:
        // Inside 70 world units: 100% visible (the player and their immediate space)
        // Between 70 and 220 world units: smooth feathered falloff
        // Beyond 220 world units: pitch dark station emergency lighting
        float innerWorldRadius = 70f;
        float outerWorldRadius = 220f;

        float innerRadius = (innerWorldRadius / DARKNESS_MASK_SIZE) * size;
        float outerRadius = (outerWorldRadius / DARKNESS_MASK_SIZE) * size;

        float r = 0.02f, g = 0.03f, b = 0.05f;
        float maxDarkness = 0.96f;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - cx;
                float dy = y - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                if (dist <= innerRadius) {
                    pixmap.setColor(r, g, b, 0f);
                } else if (dist >= outerRadius) {
                    pixmap.setColor(r, g, b, maxDarkness);
                } else {
                    float t = (dist - innerRadius) / (outerRadius - innerRadius);
                    float smoothT = 0.5f - 0.5f * (float) Math.cos(t * Math.PI);
                    pixmap.setColor(r, g, b, smoothT * maxDarkness);
                }
                pixmap.drawPixel(x, y);
            }
        }
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }

    private final class PuzzleListener implements Level1Listener {
        // Each stage uses a different terminal, so close the open popup
        @Override
        public void onLocalView(CodeFragmentPayload payload) {
            viewP1 = payload;
            popupP1.close();
        }

        @Override
        public void onRemoteView(CodeFragmentPayload payload) {
            viewP2 = payload;
            popupP2.close();
        }

        @Override
        public void onAlertMeterChanged(int value) {
            setAlertMeter(value);
        }

        @Override
        public void onReactorUnlocked() {
            unlockReactor();
        }

        @Override
        public void onLevelRestart() {
            restartLocalState();
        }

        @Override
        public void onWrongAnswer(int offendingPlayerId) {
            popupP1.close();
            popupP2.close();
            Player offender = (offendingPlayerId == 1) ? player1 : player2;
            swarm.spawnWave(offendingPlayerId, offender.x, offender.y, world);
        }

        @Override
        public void onMissionFailed() {
            // Nobody fell, so the window can come up straight away
            triggerMissionFailed(ALERT_MAXED, 0f);
        }
    }

    // Client only
    private void onHostMessage(String type, String body) {
        switch (type) {
            case "CODE_FRAGMENT":
                viewP2 = CodeFragmentPayload.deserialize(body);
                popupP2.close();
                break;
            case "ALERT_METER_UPDATE":
                setAlertMeter(AlertMeterUpdateMessage.deserialize(body).getValue());
                if (alertMeterValue >= AlertMeter.MAX_VALUE) triggerMissionFailed(ALERT_MAXED, 0f);
                break;
            case "REACTOR_UNLOCK":
                unlockReactor();
                break;
            case "ENEMY_STATE":
                EnemyStateMessage state = EnemyStateMessage.deserialize(body);
                remoteEnemiesP1.clear();
                remoteEnemiesP1.addAll(state.getEnemiesP1());
                remoteEnemiesP2.clear();
                remoteEnemiesP2.addAll(state.getEnemiesP2());
                player1.health = state.getHealthP1();
                player2.health = state.getHealthP2();
                checkForDeath();
                break;
            case "WRONG_ANSWER":
                popupP2.close();
                break;
            case "LEVEL_RESTART":
                restartLocalState();
                break;
            case "LEVEL2_START":
                advanceToLevel2();
                break;
            default:
                break;
        }
    }

    private void setAlertMeter(int value) {
        if (value == alertMeterValue) return;
        alertMeterValue = value;
        alertLabel = "ALERT METER  " + value;
    }

    private void unlockReactor() {
        reactorUnlocked = true;
        world.unlockReactor();
    }

    private void restartLocalState() {
        reactorUnlocked = false;
        world.resetProgress();
        popupP1.close();
        popupP2.close();
        swarm.reset();
        remoteEnemiesP1.clear();
        remoteEnemiesP2.clear();
        setAlertMeter(0);
        missionFailed = false;
        failureScenePending = false;
        failureCause = "";
        resetPlayer(player1, world.getSpawnP1());
        resetPlayer(player2, world.getSpawnP2());
        story.closeFailure();
    }

    private static void resetPlayer(Player player, float[] spawn) {
        player.health = Player.MAX_HEALTH;
        player.resetVisualState();
        player.placeAt(spawn[0], spawn[1]);
    }

    // Host after contact damage, client when the host reports health
    private void checkForDeath() {
        boolean breakerDown = player1.health <= 0f;
        boolean listenerDown = player2.health <= 0f;
        if (!breakerDown && !listenerDown) return;
        triggerMissionFailed(StoryGate.fallen(breakerDown, listenerDown), FAILURE_SCENE_DELAY);
    }

    private void triggerMissionFailed(String cause, float sceneDelay) {
        if (missionFailed) return;
        missionFailed = true;
        failureCause = cause;
        failureScenePending = true;
        failureSceneTimer = sceneDelay;
        popupP1.close();
        popupP2.close();
    }

    // Waits for the death animation, then puts up the mission failed window
    private void showFailureWhenReady(float delta) {
        if (!failureScenePending) return;
        failureSceneTimer -= delta;
        if (failureSceneTimer > 0f) return;
        failureScenePending = false;
        story.showFailure("MAIN SCENARIO #1 — FAILED",
            "The station noticed them before they reached the gate. Not every telling ends at the reactor; "
                + "this one stops here, in the dark, and begins again.",
            new String[][]{
                {"Cause", failureCause},
                {"Penalty", "Terminal progress and the alert meter reset."},
                {"Retry", controller != null ? "Press ENTER to restart the scenario."
                    : "The host restarts the scenario."}
            },
            controller != null ? controller::restartLevel1 : null);
    }

    // The connections carry over to Level 2, so only this screen's own resources are freed.
    // The scenario window goes up first, so Level 2 loads behind it
    private void advanceToLevel2() {
        if (advancing) return;
        advancing = true;
        story.begin(StoryBeat.LEVEL_2);
        if (hostSession != null) hostSession.send(new Level2StartMessage());
        Level2Screen next = new Level2Screen(server, client, hostSession, clientSession, story, sideOneRole);
        disposed = true;
        disposeLocalResources();
        game.setScreen(next);
    }

    @Override
    public void render(float delta) {
        // ESC closes an open terminal or inventory first
        boolean uiPanelOpen = popupP1.isOpen() || popupP2.isOpen() || inventories.anyOpen();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !uiPanelOpen) {
            Gdx.app.exit();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;
        // F2 = UI size, for projectors
        if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) ui.cycleScale();
        // K = skip stage, debug only
        if (isDebug && !missionFailed && Gdx.input.isKeyJustPressed(Input.Keys.K)) controller.skipCurrentStage();

        // Terminals use 1 and 2 as digits, so the inventory keys are blocked while one is open
        inventories.handleInput(isHost || isDebug, !isHost || isDebug,
            missionFailed || popupP1.isOpen() || popupP2.isOpen(), player1, player2);

        player1.updateVisualState(delta);
        player2.updateVisualState(delta);

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient();
        }

        handlePuzzleInteraction();
        plateP1Held = player1.colliderOverlaps(world.getPressurePlateP1());
        plateP2Held = player2.colliderOverlaps(world.getPressurePlateP2());
        // The gate stays open once both plates were held
        if (reactorUnlocked && plateP1Held && plateP2Held) world.openExitGate();
        updateSwarm(delta);
        showFailureWhenReady(delta);
        SplitScreen.drawHalves(this, player1, player2);
        drawUI();
        // Has to be last, advancing disposes this screen
        checkExitGate();
    }

    // The host decides, the client waits for LEVEL2_START
    private void checkExitGate() {
        if (controller == null || missionFailed || !world.isExitGateOpen()) return;
        Rectangle gate = world.getExitGateZone();
        if (player1.colliderOverlaps(gate) && player2.colliderOverlaps(gate)) advanceToLevel2();
    }

    // Only hits when a new swing starts, so holding the button hits once per animation
    private void attack(int side, Player player, boolean pressed) {
        if (pressed && player.startAttack()) {
            swarm.attackNearest(side, player.centreX(), player.centreY(), ATTACK_RANGE, ATTACK_DAMAGE);
        }
    }

    private void updateSwarm(float delta) {
        if (controller == null) return;
        if (!missionFailed) {
            swarm.update(delta, player1, player2, world);
            if (swarm.isTouchingAny(1, player1)) player1.takeDamage(CONTACT_DAMAGE * delta);
            if (swarm.isTouchingAny(2, player2)) player2.takeDamage(CONTACT_DAMAGE * delta);
            checkForDeath();
        }
        if (hostSession == null) return;

        enemyStateTimer += delta;
        if (enemyStateTimer < ENEMY_STATE_INTERVAL) return;
        enemyStateTimer = 0f;
        hostSession.send(new EnemyStateMessage(
            swarm.positions(1, positionBufferP1),
            swarm.positions(2, positionBufferP2),
            player1.health, player2.health));
    }

    // Debug: both players on one keyboard, left mouse button for player 1 and right for player 2
    private void updateAsDebug(float delta) {
        boolean p1Free = !missionFailed && !popupP1.isOpen() && !inventories.isOpen(1);
        boolean p2Free = !missionFailed && !popupP2.isOpen() && !inventories.isOpen(2);
        if (p1Free) player1.update(delta);
        if (p2Free) player2.update(delta);
        attack(1, player1, p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT));
        attack(2, player2, p2Free && Gdx.input.isButtonPressed(Input.Buttons.RIGHT));
    }

    private void updateAsHost(float delta) {
        boolean p1Free = !missionFailed && !popupP1.isOpen() && !inventories.isOpen(1);
        if (p1Free) player1.update(delta);

        // With no client connected, Player 2 plays from this keyboard and mouse.
        PlayerInput p2Input = server.isClientConnected()
            ? server.pollClientInput()
            : new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.UP),
                Gdx.input.isKeyPressed(Input.Keys.DOWN),
                Gdx.input.isKeyPressed(Input.Keys.LEFT),
                Gdx.input.isKeyPressed(Input.Keys.RIGHT),
                Gdx.input.isButtonPressed(Input.Buttons.RIGHT));
        if (!missionFailed) player2.applyInput(p2Input, delta);

        attack(1, player1, p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT));
        attack(2, player2, !missionFailed && p2Input.attack);
        server.pushState(new WorldState(player1, player2));
    }

    private void updateAsClient() {
        // The client is its own machine: WASD to move, right mouse button to attack
        boolean free = !missionFailed && !popupP2.isOpen() && !inventories.isOpen(2);
        client.pushInput(free
            ? new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.W),
                Gdx.input.isKeyPressed(Input.Keys.S),
                Gdx.input.isKeyPressed(Input.Keys.A),
                Gdx.input.isKeyPressed(Input.Keys.D),
                Gdx.input.isButtonPressed(Input.Buttons.RIGHT))
            : new PlayerInput(false, false, false, false));

        client.pollState().applyTo(player1, player2);
    }

    private void handlePuzzleInteraction() {
        if (isDebug) {
            handleDebugPuzzleInteraction();
            return;
        }
        CodePopupUI popup = isHost ? popupP1 : popupP2;
        if (popup.isOpen()) {
            nearTerminal = false;
            popup.handleInput();
            return;
        }
        Player player = isHost ? player1 : player2;
        CodeFragmentPayload view = isHost ? viewP1 : viewP2;
        List<Rectangle> terminals = isHost ? world.getTerminalZonesP1() : world.getTerminalZonesP2();
        nearTerminal = isNearStageTerminal(player, terminals, view);
        if (nearTerminal && Gdx.input.isKeyJustPressed(Input.Keys.E)) popup.open(view, isHost ? 1 : 2);
    }

    // In debug both popups can be open, SPACE switches which one gets the keys
    private void handleDebugPuzzleInteraction() {
        boolean p1Near = isNearStageTerminal(player1, world.getTerminalZonesP1(), viewP1);
        boolean p2Near = isNearStageTerminal(player2, world.getTerminalZonesP2(), viewP2);
        nearTerminal = (p1Near && !popupP1.isOpen()) || (p2Near && !popupP2.isOpen());

        if (Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            boolean openedP1 = false;
            if (p1Near && !popupP1.isOpen()) {
                popupP1.open(viewP1, 1);
                debugFocusedPlayerId = 1;
                openedP1 = true;
            }
            // If one press opens both, start typing into the left one
            if (p2Near && !popupP2.isOpen()) {
                popupP2.open(viewP2, 2);
                if (!openedP1) debugFocusedPlayerId = 2;
            }
        }

        // ESC only closes the focused one
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (popupP1.isOpen() && popupP2.isOpen()) {
                if (debugFocusedPlayerId == 1) popupP1.close();
                else popupP2.close();
                debugFocusedPlayerId = (debugFocusedPlayerId == 1) ? 2 : 1;
            } else {
                popupP1.close();
                popupP2.close();
            }
            return;
        }

        boolean bothOpen = popupP1.isOpen() && popupP2.isOpen();
        if (bothOpen && Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            debugFocusedPlayerId = (debugFocusedPlayerId == 1) ? 2 : 1;
        }
        boolean p1Active = popupP1.isOpen() && (!bothOpen || debugFocusedPlayerId == 1);
        boolean p2Active = popupP2.isOpen() && (!bothOpen || debugFocusedPlayerId == 2);
        popupP1.setFocused(p1Active);
        popupP2.setFocused(p2Active);
        if (p1Active) popupP1.handleInput();
        if (p2Active) popupP2.handleInput();
    }

    // Terminal N-1 is for stage N
    private static boolean isNearStageTerminal(Player player, List<Rectangle> terminals, CodeFragmentPayload view) {
        if (view == null) return false;
        int index = view.getStageNumber() - 1;
        return index >= 0 && index < terminals.size() && player.canReach(terminals.get(index));
    }

    // Called once per camera by SplitScreen.drawHalves()
        @Override
    public void drawHalf(OrthographicCamera camera) {
        world.render(batch, camera);
        world.renderOverlays(shape, camera, plateP1Held, plateP2Held);
        if (debugCollisionVisible) world.renderDebugCollision(batch, camera);

        // Enemies and players in one batch pass
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        if (controller != null) {
            enemySprites.drawAll(batch, camera, swarm.getEnemiesP1());
            enemySprites.drawAll(batch, camera, swarm.getEnemiesP2());
        } else {
            enemySprites.drawRemote(batch, camera, remoteEnemiesP1);
            enemySprites.drawRemote(batch, camera, remoteEnemiesP2);
        }
        player1.draw(batch);
        player2.draw(batch);

        // Blackout spotlight: stays active until all 3 stages are solved or skipped with K
        if (!reactorUnlocked) {
            Player focus = (camera == player1.camera) ? player1 : player2;
            batch.setColor(1f, 1f, 1f, 1f);
            batch.draw(darknessMask, 
                focus.centreX() - DARKNESS_MASK_SIZE / 2f, 
                focus.centreY() - DARKNESS_MASK_SIZE / 2f, 
                DARKNESS_MASK_SIZE, DARKNESS_MASK_SIZE);
        }

        batch.end();
    }

    private void drawUI() {
        OrthographicCamera uiCamera = ui.camera();
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);

        hud.drawBanner(shape, batch, ui, alertLabel, objective(), prompt(),
            alertMeterValue / (float) AlertMeter.MAX_VALUE);
        hud.drawPlayerCards(shape, batch, ui, player1, player2, sideOneRole);
        inventories.render(shape, batch, ui.width(), ui.height(), player1, player2,
            SplitScreen.ACCENT_P1, SplitScreen.ACCENT_P2);
        popupP1.render(shape, batch, ui.width(), ui.height());
        popupP2.render(shape, batch, ui.width(), ui.height());
    }

    // One line under the objective, or nothing
    private String prompt() {
        if (isDebug && popupP1.isOpen() && popupP2.isOpen()) {
            return debugFocusedPlayerId == 1 ? DEBUG_FOCUS_P1 : DEBUG_FOCUS_P2;
        }
        return nearTerminal ? "Press E to interact" : null;
    }

    private String objective() {
        if (world.isExitGateOpen()) return "Exit gate open! Both of you step inside it.";
        if (reactorUnlocked) return "Reactor unlocked! Both of you stand on the pressure plates.";
        return "Find your terminal and solve it together with your partner to unlock the reactor.";
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
        disposeLocalResources();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }

    // Not the connections, Level 2 keeps using them
    private void disposeLocalResources() {
        darknessMask.dispose();
        batch.dispose();
        shape.dispose();
        font.dispose();
        inventories.dispose();
        player1.dispose();
        player2.dispose();
        world.dispose();
        enemySprites.dispose();
        hud.dispose();
    }
}
