package io.github.fableops.level1;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Game;
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
import com.badlogic.gdx.utils.Align;

import io.github.fableops.Enemy;
import io.github.fableops.EnemySprites;
import io.github.fableops.Player;
import io.github.fableops.SwarmController;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level1.controller.Level1Controller;
import io.github.fableops.level1.controller.Level1Listener;
import io.github.fableops.level1.model.AlertMeter;
import io.github.fableops.level1.network.AlertMeterUpdateMessage;
import io.github.fableops.level1.network.CodeFragmentPayload;
import io.github.fableops.level1.network.EnemyStateMessage;
import io.github.fableops.level1.network.EnteredDigitMessage;
import io.github.fableops.level1.network.Level2StartMessage;
import io.github.fableops.level2.Level2Screen;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.ui.SplitScreen;
import io.github.fableops.ui.UiViewport;
import io.github.fableops.ui.hud.CodePopupUI;

// Level 1: solve the terminals, hold both plates, then walk through the exit gate together
// Movement goes through GameServer/GameClient, everything else through the message channel
public class Level1Screen implements Screen, SplitScreen.HalfRenderer {

    private static final float ATTACK_RANGE = 120f;
    private static final int ATTACK_DAMAGE = 15;
    private static final float ATTACK_COOLDOWN = 0.35f;
    private static final int CONTACT_DAMAGE = 10; // per second of contact
    // Enemy updates are sent 20 times a second, not every frame
    private static final float ENEMY_STATE_INTERVAL = 1f / 20f;

    private static final float BANNER_TITLE_SCALE = 3.0f;
    private static final float BANNER_TEXT_SCALE = 1.6f;
    private static final Color COLOR_PANEL_BG = new Color(0.04f, 0.043f, 0.047f, 0.97f);
    private static final Color COLOR_PANEL_BORDER = new Color(0.29f, 0.24f, 0.18f, 1f);
    private static final Color COLOR_TEXT = new Color(0.93f, 0.93f, 0.91f, 1f);
    private static final Color COLOR_DIM = new Color(0.45f, 0.45f, 0.42f, 1f);
    private static final String DEBUG_FOCUS_P1 = "SPACE = switch terminal   (typing into: player 1)";
    private static final String DEBUG_FOCUS_P2 = "SPACE = switch terminal   (typing into: player 2)";

    private final Game game;
    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shape = new ShapeRenderer();
    // Shared by the HUD, the terminals and the banner, each sets its own scale
    private final BitmapFont font = new BitmapFont();
    private final UiViewport ui = new UiViewport();
    private final PlayerInventories inventories = new PlayerInventories();
    private final Level1Map world = new Level1Map();
    private final EnemySprites enemySprites = new EnemySprites();
    private final SwarmController swarm = new SwarmController(enemySprites);
    private final Player player1;
    private final Player player2;
    // null on the client
    private final Level1Controller controller;

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
    private String missionFailedReason = "";
    private boolean advancing = false; // guards advanceToLevel2() against running twice
    private boolean disposed = false;

    private float attackCooldownP1 = 0f;
    private float attackCooldownP2 = 0f;
    private float enemyStateTimer = 0f;
    // Client only: the host's swarm as last reported.
    private final List<float[]> remoteEnemiesP1 = new ArrayList<>();
    private final List<float[]> remoteEnemiesP2 = new ArrayList<>();
    // Host only, reused for every enemy snapshot
    private final List<float[]> positionBufferP1 = new ArrayList<>();
    private final List<float[]> positionBufferP2 = new ArrayList<>();

    public Level1Screen(Game game, GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession) {
        this.game = game;
        this.server = server;
        this.client = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.isHost = (server != null);
        this.isDebug = (server == null && client == null);

        SplitScreen.smoothFont(font);

        float[] spawnP1 = world.getSpawnP1();
        float[] spawnP2 = world.getSpawnP2();
        player1 = new Player("brawlspritesheet.png", spawnP1[0], spawnP1[1],
            Input.Keys.W, Input.Keys.S, Input.Keys.A, Input.Keys.D, world, 1);
        // Arrow keys are only used in debug, a real client sends its own WASD
        player2 = new Player("hackerspritesheet.png", spawnP2[0], spawnP2[1],
            Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT, Input.Keys.RIGHT, world, 2);
        player2.setAlternateRightKey(Input.Keys.L);
        SplitScreen.fitCameras(player1, player2);

        if (isHost || isDebug) {
            controller = new Level1Controller(hostSession, new PuzzleListener());
            if (hostSession != null) hostSession.setListener(controller.asMessageListener());
            popupP1.setSubmitListener(controller::submitFromLocalPlayer);
            if (isDebug) {
                popupP2.setSubmitListener((position, guess) ->
                    controller.handleEnteredDigit(new EnteredDigitMessage(position, guess, 2)));
            }
            controller.startLevel();
        } else {
            controller = null;
            // Messages come in on the network thread, so handle them on the render thread
            clientSession.setListener((type, body) -> Gdx.app.postRunnable(() -> onHostMessage(type, body)));
            popupP2.setSubmitListener((position, guess) ->
                clientSession.send(new EnteredDigitMessage(position, guess, 2)));
        }
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
            triggerMissionFailed("Alert Meter maxed out!");
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
                if (alertMeterValue >= AlertMeter.MAX_VALUE) triggerMissionFailed("Alert Meter maxed out!");
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
                if (player1.health <= 0 || player2.health <= 0) triggerMissionFailed("A player was eliminated!");
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
        alertLabel = "Alert Meter: " + value;
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
        attackCooldownP1 = 0f;
        attackCooldownP2 = 0f;
        setAlertMeter(0);
        missionFailed = false;
        missionFailedReason = "";
        resetPlayer(player1, world.getSpawnP1());
        resetPlayer(player2, world.getSpawnP2());
    }

    private static void resetPlayer(Player player, float[] spawn) {
        player.health = Player.MAX_HEALTH;
        player.resetVisualState();
        player.placeAt(spawn[0], spawn[1]);
    }

    private void triggerMissionFailed(String reason) {
        if (missionFailed) return;
        missionFailed = true;
        missionFailedReason = reason;
        popupP1.close();
        popupP2.close();
    }

    // The connections carry over to Level 2, so only this screen's own resources are freed
    private void advanceToLevel2() {
        if (advancing) return;
        advancing = true;
        if (hostSession != null) hostSession.send(new Level2StartMessage());
        Level2Screen next = new Level2Screen(server, client, hostSession, clientSession);
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
        // Only the host restarts, the client waits for LEVEL_RESTART
        if (missionFailed && controller != null && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            controller.restartLevel1();
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
            updateAsClient(delta);
        }

        handlePuzzleInteraction();
        plateP1Held = player1.colliderOverlaps(world.getPressurePlateP1());
        plateP2Held = player2.colliderOverlaps(world.getPressurePlateP2());
        // The gate stays open once both plates were held
        if (reactorUnlocked && plateP1Held && plateP2Held) world.openExitGate();
        updateSwarm(delta);
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

    // Returns the new cooldown
    private float resolveAttack(int side, Player player, boolean attacking, float cooldown) {
        if (!attacking || cooldown > 0f) return Math.max(0f, cooldown);
        player.triggerAttackVisual();
        swarm.attackNearest(side, player.centreX(), player.centreY(), ATTACK_RANGE, ATTACK_DAMAGE);
        return ATTACK_COOLDOWN;
    }

    private void updateSwarm(float delta) {
        if (controller == null) return;
        if (!missionFailed) {
            swarm.update(delta, player1, player2, world);
            if (swarm.isTouchingAny(1, player1)) player1.takeDamage(CONTACT_DAMAGE * delta);
            if (swarm.isTouchingAny(2, player2)) player2.takeDamage(CONTACT_DAMAGE * delta);
            if (player1.health <= 0 || player2.health <= 0) triggerMissionFailed("A player was eliminated!");
        }
        if (hostSession == null) return;

        enemyStateTimer += delta;
        if (enemyStateTimer < ENEMY_STATE_INTERVAL) return;
        enemyStateTimer = 0f;
        hostSession.send(new EnemyStateMessage(
            toPositions(swarm.getEnemiesP1(), positionBufferP1),
            toPositions(swarm.getEnemiesP2(), positionBufferP2),
            player1.health, player2.health));
    }

    // Reuses the buffer, send() turns it into a string straight away
    private static List<float[]> toPositions(List<Enemy> enemies, List<float[]> buffer) {
        buffer.clear();
        for (int i = 0; i < enemies.size(); i++) buffer.add(enemies.get(i).getPosition());
        return buffer;
    }

    // Debug: both players on one keyboard
    private void updateAsDebug(float delta) {
        boolean p1Free = !missionFailed && !popupP1.isOpen() && !inventories.isOpen(1);
        boolean p2Free = !missionFailed && !popupP2.isOpen() && !inventories.isOpen(2);
        if (p1Free) player1.update(delta);
        if (p2Free) player2.update(delta);
        attackCooldownP1 = resolveAttack(1, player1,
            p1Free && Gdx.input.isKeyPressed(Input.Keys.F), attackCooldownP1 - delta);
        attackCooldownP2 = resolveAttack(2, player2,
            p2Free && Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT), attackCooldownP2 - delta);
    }

    private void updateAsHost(float delta) {
        boolean p1Free = !missionFailed && !popupP1.isOpen() && !inventories.isOpen(1);
        if (p1Free) player1.update(delta);

        // With no client connected, Player 2 plays from this keyboard.
        PlayerInput p2Input = server.isClientConnected()
            ? server.pollClientInput()
            : new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.UP),
                Gdx.input.isKeyPressed(Input.Keys.DOWN),
                Gdx.input.isKeyPressed(Input.Keys.LEFT),
                Gdx.input.isKeyPressed(Input.Keys.RIGHT),
                Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT));
        if (!missionFailed) player2.applyInput(p2Input, delta);

        attackCooldownP1 = resolveAttack(1, player1,
            p1Free && Gdx.input.isKeyPressed(Input.Keys.F), attackCooldownP1 - delta);
        attackCooldownP2 = resolveAttack(2, player2, !missionFailed && p2Input.attack, attackCooldownP2 - delta);
        server.pushState(new WorldState(player1.x, player1.y, player2.x, player2.y));
    }

    private void updateAsClient(float delta) {
        // The client is its own machine, so its player uses WASD and F.
        boolean free = !missionFailed && !popupP2.isOpen() && !inventories.isOpen(2);
        client.pushInput(free
            ? new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.W),
                Gdx.input.isKeyPressed(Input.Keys.S),
                Gdx.input.isKeyPressed(Input.Keys.A),
                Gdx.input.isKeyPressed(Input.Keys.D),
                Gdx.input.isKeyPressed(Input.Keys.F))
            : new PlayerInput(false, false, false, false));

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
            drawSwarm(camera, swarm.getEnemiesP1());
            drawSwarm(camera, swarm.getEnemiesP2());
        } else {
            // A client only knows positions, so it draws a standing frame.
            drawRemoteSwarm(camera, remoteEnemiesP1);
            drawRemoteSwarm(camera, remoteEnemiesP2);
        }
        player1.draw(batch);
        player2.draw(batch);
        batch.end();
    }

    private void drawSwarm(OrthographicCamera camera, List<Enemy> enemies) {
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            if (isOnScreen(camera, e.x, e.y)) e.draw(batch, enemySprites);
        }
    }

    private void drawRemoteSwarm(OrthographicCamera camera, List<float[]> positions) {
        for (int i = 0; i < positions.size(); i++) {
            float[] pos = positions.get(i);
            if (isOnScreen(camera, pos[0], pos[1])) {
                enemySprites.draw(batch, enemySprites.walkFrame(0, 0f), pos[0], pos[1]);
            }
        }
    }

    // Skip enemies that are outside this camera's view
    private static boolean isOnScreen(OrthographicCamera camera, float x, float y) {
        float margin = EnemySprites.DRAW_SIZE;
        return Math.abs(x - camera.position.x) <= camera.viewportWidth / 2f + margin
            && Math.abs(y - camera.position.y) <= camera.viewportHeight / 2f + margin;
    }

    private void drawUI() {
        OrthographicCamera uiCamera = ui.camera();
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);
        float margin = SplitScreen.HUD_MARGIN;
        float step = SplitScreen.HUD_LINE_STEP;
        float rowY = ui.height() - margin;

        font.getData().setScale(SplitScreen.HUD_FONT_SCALE);
        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, alertLabel, margin, rowY);
        rowY -= step;
        font.setColor(reactorUnlocked ? Color.GREEN : Color.LIGHT_GRAY);
        font.draw(batch, objective(), margin, rowY);
        rowY -= step;
        if (nearTerminal) {
            font.setColor(Color.CYAN);
            font.draw(batch, "Press E to interact", margin, rowY);
        }
        rowY -= step;
        if (isDebug && popupP1.isOpen() && popupP2.isOpen()) {
            font.setColor(Color.ORANGE);
            font.draw(batch, debugFocusedPlayerId == 1 ? DEBUG_FOCUS_P1 : DEBUG_FOCUS_P2, margin, rowY);
        }
        batch.end();

        SplitScreen.drawHealthBars(shape, ui.width(), rowY - step, player1, player2);
        inventories.render(shape, batch, ui.width(), ui.height(), player1, player2,
            SplitScreen.ACCENT_P1, SplitScreen.ACCENT_P2);
        popupP1.render(shape, batch, ui.width(), ui.height());
        popupP2.render(shape, batch, ui.width(), ui.height());
        if (missionFailed) drawMissionFailedBanner();
    }

    private String objective() {
        if (world.isExitGateOpen()) return "Exit gate open! Both of you step inside it.";
        if (reactorUnlocked) return "Reactor unlocked! Both of you stand on the pressure plates.";
        return "Find your terminal and solve it together with your partner to unlock the reactor.";
    }

    // Keep this text ASCII, the default font has no dash glyphs
    private void drawMissionFailedBanner() {
        float screenW = ui.width();
        float screenH = ui.height();
        float panelW = Math.min(980f, screenW * 0.6f);
        float panelH = 420f;
        float panelX = (screenW - panelW) / 2f;
        float panelY = (screenH - panelH) / 2f;
        float pad = 52f;
        float notch = 22f;
        Color accent = SplitScreen.ACCENT_P2;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0f, 0f, 0f, 0.82f);
        shape.rect(0, 0, screenW, screenH);
        shape.setColor(COLOR_PANEL_BG);
        shape.rect(panelX, panelY + notch, panelW, panelH - 2 * notch);
        shape.rect(panelX + notch, panelY, panelW - 2 * notch, panelH);
        shape.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        shape.begin(ShapeRenderer.ShapeType.Line);
        shape.setColor(COLOR_PANEL_BORDER);
        shape.line(panelX, panelY + notch, panelX, panelY + panelH - notch);
        shape.line(panelX, panelY + panelH - notch, panelX + notch, panelY + panelH);
        shape.line(panelX + notch, panelY + panelH, panelX + panelW - notch, panelY + panelH);
        shape.line(panelX + panelW - notch, panelY + panelH, panelX + panelW, panelY + panelH - notch);
        shape.line(panelX + panelW, panelY + panelH - notch, panelX + panelW, panelY + notch);
        shape.line(panelX + panelW, panelY + notch, panelX + panelW - notch, panelY);
        shape.line(panelX + panelW - notch, panelY, panelX + notch, panelY);
        shape.line(panelX + notch, panelY, panelX, panelY + notch);
        shape.setColor(accent);
        shape.line(panelX, panelY + panelH - notch, panelX + notch, panelY + panelH);
        shape.line(panelX + panelW - notch, panelY, panelX + panelW, panelY + notch);
        shape.end();

        float textX = panelX + pad;
        float contentW = panelW - 2 * pad;
        float lineY = panelY + panelH - pad;
        batch.begin();
        font.getData().setScale(BANNER_TEXT_SCALE);
        font.setColor(accent);
        font.draw(batch, "// CRITICAL FAILURE", textX, lineY, contentW, Align.left, true);
        lineY -= 54;
        font.getData().setScale(BANNER_TITLE_SCALE);
        font.draw(batch, "MISSION FAILED", textX, lineY, contentW, Align.left, false);
        lineY -= 98;
        font.getData().setScale(BANNER_TEXT_SCALE);
        font.setColor(COLOR_TEXT);
        lineY -= font.draw(batch, missionFailedReason, textX, lineY, contentW, Align.left, true).height + 33;
        font.setColor(COLOR_DIM);
        font.draw(batch, controller != null
                ? "ESC = Main Menu\nENTER = Restart Level"
                : "ESC = Main Menu\nWaiting for host to restart...",
            textX, lineY, contentW, Align.left, true);
        batch.end();
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
        batch.dispose();
        shape.dispose();
        font.dispose();
        inventories.dispose();
        player1.dispose();
        player2.dispose();
        world.dispose();
        enemySprites.dispose();
    }
}
