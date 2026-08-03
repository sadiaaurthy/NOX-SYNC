package io.github.fableops.level1;

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
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Align;

import java.util.ArrayList;
import java.util.List;

import io.github.fableops.Enemy;
import io.github.fableops.EnemySprites;
import io.github.fableops.Player;
import io.github.fableops.ResultsScreen;
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
    private boolean level1Complete = false; // set true when both players hold their plates
    private BitmapFont bannerFont;
    private BitmapFont subFont; // eyebrow / body / hint text on the end-of-level panels

    // Shared with the launcher's palette (launcher.css) so both screens read as one game.
    private static final Color COLOR_PANEL_BG     = new Color(0.04f, 0.043f, 0.047f, 0.97f);
    private static final Color COLOR_PANEL_BORDER = new Color(0.29f, 0.24f, 0.18f, 1f);
    private static final Color COLOR_MAGENTA      = new Color(1f, 0.16f, 0.43f, 1f);
    private static final Color COLOR_CYAN         = new Color(0f, 0.90f, 1f, 1f);
    private static final Color COLOR_TEXT         = new Color(0.93f, 0.93f, 0.91f, 1f);
    private static final Color COLOR_DIM          = new Color(0.45f, 0.45f, 0.42f, 1f);
    private final SwarmController swarmController = new SwarmController();
    // Client-only mirror of the host's swarm — the client never simulates enemies
    // itself, it just renders whatever ENEMY_STATE last reported.
    private final List<float[]> remoteEnemiesP1 = new ArrayList<>();
    private final List<float[]> remoteEnemiesP2 = new ArrayList<>();
    private boolean missionFailed = false;
    private String missionFailedReason = "";
    // Recomputed every frame so the plate glow and the completion check agree.
    private boolean plateP1Held = false;
    private boolean plateP2Held = false;
    private final Rectangle plateProbe = new Rectangle(); // reused, avoids per-frame garbage
    private boolean resultsShown = false;          // result handed over exactly once
    private boolean resultsHandledExternally = false; // true = JavaFX window took it
    private static final float ATTACK_RANGE = 120f;
    private static final int ATTACK_DAMAGE = 15;
    private static final float ATTACK_COOLDOWN = 0.35f; // seconds between swings
    private float attackCooldownP1 = 0f;
    private float attackCooldownP2 = 0f;
    private EnemySprites enemySprites;
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
        bannerFont = new BitmapFont();
        bannerFont.getData().setScale(2.2f);
        subFont = new BitmapFont();
        subFont.getData().setScale(1.25f);
        // The built-in font is a small bitmap; upscaling it with the default Nearest
        // filter is what made the end-of-level text look blocky. Linear filtering plus
        // sub-pixel positioning smooths it. A truly crisp result needs a real TTF via
        // gdx-freetype, which isn't a dependency here.
        smoothFont(bannerFont);
        smoothFont(subFont);
        smoothFont(font);
        // Back-buffer size, not getWidth()/getHeight() — those are logical points and
        // diverge from the physical pixels glViewport() needs whenever the display has
        // OS-level scaling (125%/150% etc.), which was leaving stale content on screen.
        uiCamera = new OrthographicCamera(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        uiCamera.position.set(Gdx.graphics.getBackBufferWidth() / 2f, Gdx.graphics.getBackBufferHeight() / 2f, 0);
        uiCamera.update();

        world = new Level1Map();
        enemySprites = new EnemySprites();

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

        // hackerspritesheet.png continues the existing "Hacker" naming for P2;
        // brawlspritesheet.png takes the remaining slot for P1.
        player1.setTexture("brawlspritesheet.png");
        player2.setTexture("hackerspritesheet.png");

        // L also moves P2 right, alongside the right arrow. Only has any effect in Debug
        // mode, since that's the only mode where P2 reads this keyboard (host polls arrow
        // keys directly, and a joined client drives P2 over the network with WASD).
        player2.setAlternateRightKey(Input.Keys.L);

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
                // Close rather than swap contents in place: each stage lives at its own
                // terminal, so clearing the popup is what forces the walk to the next one.
                popupP1.close();
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
                // Any wrong answer — whichever side caused it — closes the host's own
                // terminal too, not just the offending player's. Host only ever owns
                // popupP1, so this is unconditional rather than keyed on offendingPlayerId.
                popupP1.close();
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
                resultsShown = false;
                resultsHandledExternally = false;
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
                    popupP2.close(); // new stage = new terminal, see host listener
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
                    resultsShown = false;
                    resultsHandledExternally = false;
                    missionFailedReason = "";
                    break;
                case "DIGIT_ACCEPTED":
                    // no extra feedback yet
                    break;
                case "WRONG_ANSWER":
                    // UI sync only — close regardless of which side offended. The client
                    // never spawns its own wave; the host is the sole enemy authority and
                    // already queued the wave via its own ENEMY_STATE-driven simulation.
                    popupP2.close();
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
                popupP1.close(); // new stage = new terminal, see host listener
            }

            @Override
            public void onRemoteView(CodeFragmentPayload payload) {
                debugP2View = payload;
                popupP2.close(); // new stage = new terminal, see host listener
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
                // Debug drives both terminals from one keyboard/process — a wrong answer
                // on either side closes both popups in the same frame.
                popupP1.close();
                popupP2.close();
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
                resultsShown = false;
                resultsHandledExternally = false;
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

        // K = skip the current stage. Debug mode only; there's no controller to drive it
        // on a joined client, and it would desync a real host/client match.
        if (isDebug && !missionFailed && Gdx.input.isKeyJustPressed(Input.Keys.K)) {
            debugController.skipCurrentStage();
        }

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
        plateP1Held = isOnOwnPlate(player1, world.getPressurePlateP1());
        plateP2Held = isOnOwnPlate(player2, world.getPressurePlateP2());
        checkLevel1Complete();
        presentResultsOnce();
        updateSwarm(delta);
        drawWorld();
        drawUI();
    }

    /**
     * Turns a held attack key into one swing per cooldown. Host-authoritative: only the
     * host/debug side actually resolves hits, a joined client just reports the keypress.
     * Without this the swarm had no counterplay at all — attackNearest() existed but was
     * never called, so a single wrong digit meant taking contact damage until death.
     */
    private void resolveAttacks(float delta, boolean p1Attacking, boolean p2Attacking) {
        attackCooldownP1 = Math.max(0f, attackCooldownP1 - delta);
        attackCooldownP2 = Math.max(0f, attackCooldownP2 - delta);

        if (p1Attacking && attackCooldownP1 <= 0f) {
            swarmController.attackNearest(1, player1.x + Player.SIZE / 2f, player1.y + Player.SIZE / 2f,
                ATTACK_RANGE, ATTACK_DAMAGE);
            attackCooldownP1 = ATTACK_COOLDOWN;
        }
        if (p2Attacking && attackCooldownP2 <= 0f) {
            swarmController.attackNearest(2, player2.x + Player.SIZE / 2f, player2.y + Player.SIZE / 2f,
                ATTACK_RANGE, ATTACK_DAMAGE);
            attackCooldownP2 = ATTACK_COOLDOWN;
        }
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

    /**
     * Hands the end-of-level result to the launcher's JavaFX results window, once.
     * If nothing is registered (game started without the launcher, so no JavaFX toolkit
     * is running) this reports false and the in-game panel is drawn instead.
     */
    private void presentResultsOnce() {
        if (resultsShown) return;
        if (!missionFailed && !level1Complete) return;
        resultsShown = true;
        resultsHandledExternally = ResultsScreen.show(
            level1Complete,
            level1Complete ? "Both plates held. The exit gate is open." : missionFailedReason);
    }

    private static void smoothFont(BitmapFont f) {
        f.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        f.setUseIntegerPositions(false);
    }

    /** True while that player is stood on their own pressure plate beside the reactor. */
    private boolean isOnOwnPlate(Player player, Rectangle plate) {
        return plate.overlaps(plateProbe.set(player.x, player.y, Player.SIZE, Player.SIZE));
    }

    private void checkLevel1Complete()
    {
        if(level1Complete) return; // already completed, no need to check again
        // Both players holding their own plate at the same time opens the exit gate and
        // ends the level. Replaces the old "both stand in the exit doorway" check, which
        // sat on the gate itself and so was never actually reachable.
        if (plateP1Held && plateP2Held) {
            world.openExitGate();
            level1Complete = true;
        }
    }

    // D mode — no network, both players local on one window
    private void updateAsDebug(float delta) {
        boolean p1Free = !popupP1.isOpen() && !missionFailed;
        boolean p2Free = !popupP2.isOpen() && !missionFailed;
        if (p1Free) player1.update(delta); // WASD
        if (p2Free) player2.update(delta); // arrow keys

        resolveAttacks(delta,
            p1Free && Gdx.input.isKeyPressed(Input.Keys.F),
            p2Free && Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT));
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
                Gdx.input.isKeyPressed(Input.Keys.RIGHT),
                Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
            );
        }

        if (!missionFailed) {
            player2.applyInput(p2Input, delta);
        }

        resolveAttacks(delta,
            !popupP1.isOpen() && !missionFailed && Gdx.input.isKeyPressed(Input.Keys.F),
            !missionFailed && p2Input.attack);

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
                Gdx.input.isKeyPressed(Input.Keys.D),
                Gdx.input.isKeyPressed(Input.Keys.F)
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

        boolean ePressed = Gdx.input.isKeyJustPressed(Input.Keys.E);
        boolean openedP1ThisFrame = false;
        if (!popupP1.isOpen() && p1Near && debugP1View != null && ePressed) {
            popupP1.open(debugP1View, 1);
            debugFocusedPlayerId = 1;
            openedP1ThisFrame = true;
        }
        if (!popupP2.isOpen() && p2Near && debugP2View != null && ePressed) {
            popupP2.open(debugP2View, 2);
            // Both players stand at mirrored terminals, so one E press usually opens both.
            // Don't let the right terminal steal focus in that case — start on the left one
            // so a single keypress lands somewhere predictable, then SPACE to swap.
            if (!openedP1ThisFrame) debugFocusedPlayerId = 2;
        }

        // ESC closes only the focused terminal, not every open one — dismissing your own
        // popup shouldn't wipe the other player's screen. Focus falls to whatever remains.
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (popupP1.isOpen() && popupP2.isOpen()) {
                if (debugFocusedPlayerId == 1) {
                    popupP1.close();
                    debugFocusedPlayerId = 2;
                } else {
                    popupP2.close();
                    debugFocusedPlayerId = 1;
                }
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

        // A popup that's the only one open always takes input, whatever the focus id says.
        boolean p1Active = popupP1.isOpen() && (!bothOpen || debugFocusedPlayerId == 1);
        boolean p2Active = popupP2.isOpen() && (!bothOpen || debugFocusedPlayerId == 2);
        popupP1.setFocused(p1Active);
        popupP2.setFocused(p2Active);
        if (p1Active) popupP1.handleInput();
        if (p2Active) popupP2.handleInput();
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
        world.renderPressurePlates(shape, player1.camera, plateP1Held, plateP2Held);
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
        world.renderPressurePlates(shape, player2.camera, plateP1Held, plateP2Held);
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
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        if (isHost || isDebug) {
            for (Enemy e : swarmController.getEnemiesP1()) e.draw(batch, enemySprites);
            for (Enemy e : swarmController.getEnemiesP2()) e.draw(batch, enemySprites);
        } else {
            // The client only receives positions, not per-enemy animation state, so it
            // renders a fixed frame rather than guessing a facing or death progress.
            for (float[] pos : remoteEnemiesP1) drawRemoteEnemy(pos);
            for (float[] pos : remoteEnemiesP2) drawRemoteEnemy(pos);
        }
        batch.end();
    }

    private void drawRemoteEnemy(float[] pos) {
        float draw = 120f;
        float offset = (Enemy.SIZE - draw) / 2f;
        batch.draw(enemySprites.walkFrame(0, 0f), pos[0] + offset, pos[1] + offset, draw, draw);
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
            font.draw(batch, "SPACE = switch terminal   (typing into: player " + debugFocusedPlayerId + ")",
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

        // Skipped when the JavaFX results window is showing the outcome instead.
        if (!resultsHandledExternally) {
            if (level1Complete) drawLevelCompleteBanner();
            if (missionFailed) drawMissionFailedBanner();
        }
    }

    private void drawLevelCompleteBanner() {
        drawBanner("// REACTOR SECURED", "LEVEL 1 COMPLETE", COLOR_CYAN,
            "Both plates held. The exit gate is open.",
            "Press ESC to exit.");
    }

    private void drawMissionFailedBanner() {
        drawBanner("// CRITICAL FAILURE", "MISSION FAILED", COLOR_MAGENTA,
            missionFailedReason,
            (isHost || isDebug) ? "Press ENTER to restart." : "Waiting for host to restart...");
    }

    /**
     * Shared end-of-level panel, styled to the launcher's language: dimmed backdrop, dark
     * notched panel, magenta eyebrow, accent title, ASCII only (the default BitmapFont has
     * no box-drawing or dash glyphs and renders them as empty squares).
     */
    private void drawBanner(String eyebrow, String title, Color titleColor, String body, String hint) {
        float screenW = Gdx.graphics.getBackBufferWidth();
        float screenH = Gdx.graphics.getBackBufferHeight();
        float panelW = Math.min(760f, screenW * 0.6f);
        float panelH = 320f;
        float panelX = (screenW - panelW) / 2f;
        float panelY = (screenH - panelH) / 2f;
        float pad = 40f;
        float notch = 18f;

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0f, 0f, 0f, 0.82f);
        shape.rect(0, 0, screenW, screenH);
        shape.setColor(COLOR_PANEL_BG);
        shape.rect(panelX, panelY + notch, panelW, panelH - 2 * notch);
        shape.rect(panelX + notch, panelY, panelW - 2 * notch, panelH);
        shape.end();

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
        shape.setColor(titleColor);
        shape.line(panelX, panelY + panelH - notch, panelX + notch, panelY + panelH);
        shape.line(panelX + panelW - notch, panelY, panelX + panelW, panelY + notch);
        shape.end();

        float contentW = panelW - 2 * pad;
        float lineY = panelY + panelH - pad;

        batch.begin();
        subFont.setColor(COLOR_MAGENTA);
        subFont.draw(batch, eyebrow, panelX + pad, lineY, contentW, Align.left, true);
        lineY -= 42;

        bannerFont.setColor(titleColor);
        bannerFont.draw(batch, title, panelX + pad, lineY, contentW, Align.left, false);
        lineY -= 72;

        subFont.setColor(COLOR_TEXT);
        lineY -= subFont.draw(batch, body, panelX + pad, lineY, contentW, Align.left, true).height + 26;

        subFont.setColor(COLOR_DIM);
        subFont.draw(batch, hint, panelX + pad, lineY, contentW, Align.left, true);
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
        enemySprites.dispose();
        bannerFont.dispose();
        subFont.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}