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
import io.github.fableops.LobbyScreen;
import io.github.fableops.Main;
import io.github.fableops.Player;
import io.github.fableops.ResultsScreen;
import io.github.fableops.SwarmController;
import io.github.fableops.inventory.PlayerInventories;
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

    private final Main game;
    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;

    private Level1Controller controller; // host only
    private Level1Controller debugController; // debug only — same class, hostSession=null

    // Both players' inventories and panels. Shared with Level 2 rather than reimplemented
    // there — only the items placed in them differ between levels.
    private final PlayerInventories inventories = new PlayerInventories();

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
    // Rebuilt only when alertMeterValue changes — see alertMeterLabel().
    private String cachedAlertLabel;
    private int cachedAlertValue = -1;
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
    // Host-side snapshot rate, independent of frame rate — see updateSwarm().
    private static final float ENEMY_STATE_INTERVAL = 1f / 20f;
    private float enemyStateTimer = 0f;
    private final List<float[]> positionBufferP1 = new ArrayList<>();
    private final List<float[]> positionBufferP2 = new ArrayList<>();
    private boolean missionFailed = false;
    private String missionFailedReason = "";
    private boolean returnedToMenu = false; // guards returnToMainMenu() against running twice
    private boolean disposed = false;       // guards dispose() against running twice
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

    /**
     * The HUD, the terminal popups and the end-of-level banners are authored in a fixed
     * 1920x1080 virtual space, never in raw back-buffer pixels. Every UI size below —
     * padding, panel dimensions, font scales, row spacing — is in those virtual units.
     *
     * This is what makes the interface resolution- and DPI-independent. Previously the
     * UI camera was the back buffer itself, so panels sized as a fraction of the screen
     * grew with it while the text inside them stayed an absolute pixel count: on a 4K
     * display that meant a huge panel holding unreadably small text, and on a 1366x768
     * laptop the same text was proportionally oversized and overran its panel. Anchoring
     * the whole layer to one reference resolution makes text a constant fraction of
     * screen height everywhere, whatever the OS scaling factor is set to.
     *
     * Same idea as LobbyScreen's 1536x960 design space, but scaled-and-extended rather
     * than fitted: a FitViewport would letterbox, and these black bars would pull the UI
     * halves out of alignment with the split-screen world halves drawn under them.
     */
    private static final float UI_REF_W = 1920f;
    private static final float UI_REF_H = 1080f;
    /** Current virtual size — UI_REF on a 16:9 display, taller/wider on other aspects. */
    private float uiWorldW = UI_REF_W;
    private float uiWorldH = UI_REF_H;

    /**
     * Presentation aid: multiplies the whole UI layer's size. 1.0 is the normal
     * desk-monitor size and the default, so this changes nothing unless asked for.
     * A projector is usually both low-resolution and viewed from across a room, where
     * text sized for a monitor at arm's length is too small to read from the back row;
     * F2 cycles these live so it can be adjusted against the actual wall during setup.
     * Only the UI scales — the world cameras are untouched, so gameplay is unaffected.
     */
    private static final float[] UI_SCALE_STEPS = {1f, 1.25f, 1.5f};
    private int uiScaleStep = 0;

    // HUD metrics, all in virtual units. Row spacing is derived from one step rather
    // than the old hand-written -20/-45/-70/-95/-115 ladder, so changing the font scale
    // can't leave the rows overlapping.
    private static final float HUD_FONT_SCALE = 1.4f;
    private static final float HUD_MARGIN = 26f;
    private static final float HUD_LINE_STEP = 34f;
    private static final float HUD_BAR_W = 220f;
    private static final float HUD_BAR_H = 22f;

    public Level1Screen(Main game, GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession) {
        this.game = game;
        this.server  = server;
        this.client  = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.isHost  = (server != null);
        this.isDebug = (server == null && client == null);

        batch = new SpriteBatch();
        shape = new ShapeRenderer();
        // Font scales are in UI_REF virtual units: the default BitmapFont is ~15px tall,
        // so 1.4 is ~21 virtual px of a 1080-unit-tall space — a constant share of screen
        // height on every display rather than a fixed pixel count on some of them.
        font = new BitmapFont();
        font.getData().setScale(HUD_FONT_SCALE);
        bannerFont = new BitmapFont();
        bannerFont.getData().setScale(3.0f);
        subFont = new BitmapFont();
        subFont.getData().setScale(1.6f);
        // The built-in font is a small bitmap; upscaling it with the default Nearest
        // filter is what made the end-of-level text look blocky. Linear filtering plus
        // sub-pixel positioning smooths it. A truly crisp result needs a real TTF via
        // gdx-freetype, which isn't a dependency here.
        smoothFont(bannerFont);
        smoothFont(subFont);
        smoothFont(font);
        uiCamera = new OrthographicCamera();
        updateUiCamera();

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

        // Both players exist now, so the cameras can be matched to the real window shape.
        updateWorldCameras();


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

    /**
     * The single path back to the main menu from Level 1 — currently only reached via
     * ESC during mission failure. Only ever called from render(), so it always runs on
     * the libGDX render/application thread already; no Gdx.app.postRunnable needed.
     * Guarded so a second trigger in the same or a later frame can't set a screen twice
     * or double-dispose this one.
     */
    private void returnToMainMenu() {
        if (returnedToMenu) return;
        returnedToMenu = true;
        popupP1.close();
        popupP2.close();
        game.setScreen(new LobbyScreen(game));
        dispose();
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
                world.unlockReactor();
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
                player1.resetVisualState();
                player2.resetVisualState();
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
                    world.unlockReactor();
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
                    player1.resetVisualState();
                    player2.resetVisualState();
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
                world.unlockReactor();
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
                player1.resetVisualState();
                player2.resetVisualState();
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
        // Any on-screen panel that owns ESC for itself. Both the terminal popups and the
        // inventories close on ESC, and both are handled further down this method — so
        // without counting them here, ESC would quit the game (or leave for the menu)
        // before the open panel ever saw the key.
        boolean uiPanelOpen = popupP1.isOpen() || popupP2.isOpen() || inventories.anyOpen();

        // Mission-failure input takes priority over every other top-of-frame key check.
        // ESC always leaves for the main menu here — never Gdx.app.exit() — and stops
        // this frame immediately since the screen is being torn down. ENTER only
        // restarts from whichever side actually owns the puzzle/enemy state; a joined
        // LAN client gets no ENTER handling at all here — it has no authoritative
        // restart and only ever reacts to the host's own LEVEL_RESTART message (see
        // setupClientPuzzle()). The !uiPanelOpen guard is defensive: triggerMissionFailed()
        // already force-closes both popups, so the two states can't actually overlap, but
        // this keeps "ESC closes an open terminal, never the menu" true unconditionally.
        if (missionFailed) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !uiPanelOpen) {
                returnToMainMenu();
                return;
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
                if (isHost) controller.restartLevel1();
                else if (isDebug) debugController.restartLevel1();
            }
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !uiPanelOpen) {
            Gdx.app.exit();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;

        // F2 = cycle UI size, for projector setup. Deliberately outside the missionFailed
        // and popup guards above: the banners and the terminal popups are exactly the text
        // most likely to need resizing, so it has to work while they are on screen.
        if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) {
            uiScaleStep = (uiScaleStep + 1) % UI_SCALE_STEPS.length;
            updateUiCamera();
        }

        // K = skip the current stage. Debug mode only; there's no controller to drive it
        // on a joined client, and it would desync a real host/client match.
        if (isDebug && !missionFailed && Gdx.input.isKeyJustPressed(Input.Keys.K)) {
            debugController.skipCurrentStage();
        }

        handleInventoryInput();

        // Visual-only timers (attack lunge, hurt flash) — always ticking, regardless of
        // mode, popup state, or mission failure, so an in-flight effect always finishes.
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
            player1.triggerAttackVisual();
            swarmController.attackNearest(1, player1.centreX(), player1.centreY(),
                ATTACK_RANGE, ATTACK_DAMAGE);
            attackCooldownP1 = ATTACK_COOLDOWN;
        }
        if (p2Attacking && attackCooldownP2 <= 0f) {
            player2.triggerAttackVisual();
            swarmController.attackNearest(2, player2.centreX(), player2.centreY(),
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
            // Collider, not the sprite box — a 100x100 player box overlapped enemies a
            // third of a body away, and it has to agree with what movement collides with.
            if (swarmController.isTouchingAny(1, Player.colliderX(player1.x), Player.colliderY(player1.y),
                Player.COLLIDER, Player.COLLIDER)) {
                player1.takeDamage(CONTACT_DAMAGE * delta);
            }
            if (swarmController.isTouchingAny(2, Player.colliderX(player2.x), Player.colliderY(player2.y),
                Player.COLLIDER, Player.COLLIDER)) {
                player2.takeDamage(CONTACT_DAMAGE * delta);
            }
            if (player1.health <= 0 || player2.health <= 0) {
                triggerMissionFailed("A player was eliminated!");
            }
        }
        // Enemy state used to be serialized and pushed every rendered frame. At 60fps with
        // a full swarm that was ~60 string-built packets per second plus a fresh List and
        // a float[] per enemy each time — pure garbage for data the client only redraws.
        // Decoupled the same way Minecraft separates its 20 TPS simulation from render:
        // simulation still runs every frame, the network snapshot goes out at a fixed
        // rate. Movement rides its own channel and is unaffected.
        if (isHost && hostSession != null) {
            enemyStateTimer += delta;
            if (enemyStateTimer >= ENEMY_STATE_INTERVAL) {
                enemyStateTimer = 0f;
                hostSession.send(new EnemyStateMessage(
                    toPositions(swarmController.getEnemiesP1(), positionBufferP1),
                    toPositions(swarmController.getEnemiesP2(), positionBufferP2),
                    player1.health,
                    player2.health
                ));
            }
        }
    }

    /**
     * Fills a reused buffer rather than allocating a new List and a float[] per enemy.
     * The message is serialized to a string synchronously inside send(), so the buffer is
     * fully consumed before the next call can touch it.
     */
    private List<float[]> toPositions(List<Enemy> enemies, List<float[]> buffer) {
        buffer.clear();
        for (int i = 0; i < enemies.size(); i++) buffer.add(enemies.get(i).getPosition());
        return buffer;
    }

    /**
     * Hands a successful completion to the launcher's JavaFX results window, once, same
     * as before. Mission failure never does this — it always uses the in-game MISSION
     * FAILED panel, since ESC/ENTER navigation and restart authority live entirely in
     * this screen's own input handling, which the JavaFX window (Dismiss-only) can't
     * drive. If nothing is registered for a victory (game started without the launcher)
     * this reports false and the in-game panel is drawn instead, same as before.
     */
    private void presentResultsOnce() {
        if (resultsShown) return;
        if (!missionFailed && !level1Complete) return;
        resultsShown = true;
        if (missionFailed) {
            resultsHandledExternally = false;
            return;
        }
        resultsHandledExternally = ResultsScreen.show(true, "Both plates held. The exit gate is open.");
    }

    /**
     * Re-derives the virtual UI space from the current back-buffer size.
     *
     * Back-buffer size, not getWidth()/getHeight(): those are logical points and diverge
     * from the physical pixels glViewport() works in whenever the display has OS-level
     * scaling (125%/150% etc.). Reading the physical size here is what makes the UI track
     * the real window rather than a stale logical one.
     *
     * The scale is the tighter of the two axes against the reference, so the aspect ratio
     * is always preserved — text is never stretched. The looser axis simply gets more
     * virtual units than the reference, which is why uiWorldW/uiWorldH are read per frame
     * instead of assuming 1920x1080: on a 16:10 or ultrawide display the UI fills the
     * window with extra space rather than distorting or letterboxing.
     *
     * Done by hand rather than with ExtendViewport because Viewport.apply() routes through
     * HdpiUtils, which would convert logical to physical a second time on top of the
     * back-buffer values this screen deliberately works in.
     */
    /**
     * Re-derives both world cameras from the real split-screen viewport.
     *
     * CAM_W/CAM_H describe a 1920x1080 window, where each half is 958x1080 — almost
     * exactly 640x720's aspect. Nothing else matches: a 4:3 projector's half viewport is
     * 510x768, and drawing a 640x720 world rect into it squashes the world 25%
     * horizontally. Holding the vertical extent at CAM_H and solving the width from the
     * viewport's own aspect keeps the zoom level identical to today on a 16:9 display
     * while removing the distortion everywhere else; a narrower display simply shows less
     * width rather than a deformed picture.
     */
    private void updateWorldCameras() {
        float bbW = Gdx.graphics.getBackBufferWidth();
        float bbH = Gdx.graphics.getBackBufferHeight();
        if (bbW <= 0f || bbH <= 0f) return; // minimised window — keep the last good size
        float halfW = (bbW - DIVIDER) / 2f;
        float camW = CAM_H * (halfW / bbH);
        player1.setCameraViewport(camW, CAM_H);
        player2.setCameraViewport(camW, CAM_H);
    }

    private void updateUiCamera() {
        float bbW = Gdx.graphics.getBackBufferWidth();
        float bbH = Gdx.graphics.getBackBufferHeight();
        if (bbW <= 0f || bbH <= 0f) return; // minimised window — keep the last good size
        float scale = UI_SCALE_STEPS[uiScaleStep] * Math.min(bbW / UI_REF_W, bbH / UI_REF_H);
        uiWorldW = bbW / scale;
        uiWorldH = bbH / scale;
        uiCamera.setToOrtho(false, uiWorldW, uiWorldH);
    }

    private static void smoothFont(BitmapFont f) {
        f.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        f.setUseIntegerPositions(false);
    }

    /** True while that player is stood on their own pressure plate beside the reactor. */
    private boolean isOnOwnPlate(Player player, Rectangle plate) {
        // Standing on a plate is a footprint question, so it uses the collider too.
        return plate.overlaps(plateProbe.set(Player.colliderX(player.x), Player.colliderY(player.y),
            Player.COLLIDER, Player.COLLIDER));
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
        // An open inventory freezes only its own player — the world and the partner keep
        // running, same rule the terminal popups already use.
        boolean p1Free = !popupP1.isOpen() && !inventories.isOpen(1) && !missionFailed;
        boolean p2Free = !popupP2.isOpen() && !inventories.isOpen(2) && !missionFailed;
        if (p1Free) player1.update(delta); // WASD
        if (p2Free) player2.update(delta); // arrow keys

        resolveAttacks(delta,
            p1Free && Gdx.input.isKeyPressed(Input.Keys.F),
            p2Free && Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT));
    }

    private void updateAsHost(float delta) {
        if (!popupP1.isOpen() && !inventories.isOpen(1) && !missionFailed) {
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
            !popupP1.isOpen() && !inventories.isOpen(1) && !missionFailed
                && Gdx.input.isKeyPressed(Input.Keys.F),
            !missionFailed && p2Input.attack);

        server.pushState(new WorldState(
            player1.x, player1.y,
            player2.x, player2.y
        ));
    }

    private void updateAsClient(float delta) {
        // Client is a separate physical device — no keyboard conflict, so WASD like P1.
        PlayerInput myInput = (popupP2.isOpen() || inventories.isOpen(2) || missionFailed)
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

    /**
     * Opens, closes and drives each player's inventory.
     *
     * A player may only open their own: the host is Player 1 locally and the client is
     * Player 2, so each listens for its own number key. Debug mode drives both players
     * from one keyboard, so there both keys are live.
     *
     * Guarded on the terminal popups because CodePopupUI reads NUM_1 and NUM_2 as puzzle
     * digits — without this, typing "1" into a terminal would also throw the inventory
     * open behind it. Mission failure blocks it too: that screen owns ESC and ENTER, and a
     * panel over the top would eat both.
     */
    private void handleInventoryInput() {
        // Blocked on the terminal popups because CodePopupUI reads NUM_1 and NUM_2 as
        // puzzle digits — without this, typing "1" into a terminal would also throw the
        // inventory open behind it. Mission failure blocks it too: that screen owns ESC
        // and ENTER, and a panel over the top would eat both.
        boolean blocked = missionFailed || popupP1.isOpen() || popupP2.isOpen();
        inventories.handleInput(isHost || isDebug, !isHost || isDebug, blocked, player1, player2);
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
        List<Rectangle> terminals = isHost ? world.getTerminalZonesP1() : world.getTerminalZonesP2();

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
        boolean p1Near = isNearStageTerminal(player1, world.getTerminalZonesP1(), debugP1View);
        boolean p2Near = isNearStageTerminal(player2, world.getTerminalZonesP2(), debugP2View);
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
    private boolean isNearStageTerminal(Player player, List<Rectangle> terminals, CodeFragmentPayload view) {
        if (view == null) return false;
        int index = view.getStageNumber() - 1;
        if (index < 0 || index >= terminals.size()) return false;

        // Measured to the nearest edge of the painted console, not to its centre. The
        // consoles are painted into the walls they hang on, so their centres sit ~45
        // units inside solid rock; measuring from there charged the player for the
        // console's own depth and left every terminal hovering at 72-80 against an 80
        // limit, with one failing outright at 80.3.
        return Level1Map.distanceSquaredToZone(terminals.get(index),
            player.centreX(), player.centreY())
            <= INTERACT_RANGE * INTERACT_RANGE;
    }

    private void drawWorld() {
        int screenW = Gdx.graphics.getBackBufferWidth();
        int screenH = Gdx.graphics.getBackBufferHeight();
        int half    = (screenW - DIVIDER) / 2;

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // left half — P1 camera
        Gdx.gl.glViewport(0, 0, half, screenH);
        world.render(batch, player1.camera);
        world.renderOverlays(shape, player1.camera, plateP1Held, plateP2Held);
        if (debugCollisionVisible) world.renderDebugCollision(batch, player1.camera);
        drawEnemies(player1.camera);

        batch.setProjectionMatrix(player1.camera.combined);
        batch.begin();
        player1.draw(batch);
        player2.draw(batch);
        batch.end();

        // right half — P2 camera
        Gdx.gl.glViewport(half + DIVIDER, 0, half, screenH);
        world.render(batch, player2.camera);
        world.renderOverlays(shape, player2.camera, plateP1Held, plateP2Held);
        if (debugCollisionVisible) world.renderDebugCollision(batch, player2.camera);
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
        // Indexed loops: this runs twice a frame, once per split-screen camera, and an
        // enhanced-for over an ArrayList allocates an Iterator each time.
        if (isHost || isDebug) {
            drawSwarm(camera, swarmController.getEnemiesP1());
            drawSwarm(camera, swarmController.getEnemiesP2());
        } else {
            // The client only receives positions, not per-enemy animation state, so it
            // renders a fixed frame rather than guessing a facing or death progress.
            drawRemoteSwarm(camera, remoteEnemiesP1);
            drawRemoteSwarm(camera, remoteEnemiesP2);
        }
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
            if (isOnScreen(camera, pos[0], pos[1])) drawRemoteEnemy(pos);
        }
    }

    /**
     * Frustum cull: each split-screen camera only shows CAM_W x CAM_H of the world, but
     * both swarms were drawn into both viewports regardless of where they were. A player's
     * own swarm is in their own wing, so the other camera was submitting a full set of
     * off-screen sprites every frame for the GPU to discard. The margin is one draw size,
     * so a sprite straddling the edge still renders.
     */
    private boolean isOnScreen(OrthographicCamera camera, float x, float y) {
        // Read the camera's live viewport rather than CAM_W/CAM_H: updateWorldCameras()
        // now varies the width with the display's aspect, and on a display wider than
        // 16:9 the real view is wider than CAM_W — culling against the constant would
        // discard enemies that are genuinely on screen, popping them out at the edges.
        float margin = Enemy.DRAW_SIZE;
        return Math.abs(x - camera.position.x) <= camera.viewportWidth / 2f + margin
            && Math.abs(y - camera.position.y) <= camera.viewportHeight / 2f + margin;
    }

    private void drawRemoteEnemy(float[] pos) {
        float draw = 120f;
        float offset = (Enemy.SIZE - draw) / 2f;
        batch.draw(enemySprites.walkFrame(0, 0f), pos[0] + offset, pos[1] + offset, draw, draw);
    }

    /**
     * Issues one bar's rects. Deliberately does NOT open its own batch — all four bars are
     * drawn inside a single begin/end by the caller. Each begin/end pair flushes the
     * pipeline and rebinds the shader, so four self-contained bars cost eight flushes a
     * frame for two rectangles apiece.
     */
    private void drawHealthBar(float x, float y, float health, Color color) {
        shape.setColor(Color.DARK_GRAY);
        shape.rect(x, y, HUD_BAR_W, HUD_BAR_H);
        shape.setColor(color);
        shape.rect(x, y, HUD_BAR_W * (health / Player.MAX_HEALTH), HUD_BAR_H);
    }

    /**
     * The alert-meter caption, rebuilt only when the number actually changes.
     *
     * Concatenating it inline produced a fresh String (and its StringBuilder and char
     * array) on every rendered frame — ~180 dead objects a second for a label that changes
     * a handful of times per match. Same approach LobbyScreen already uses for its clock.
     */
    private String alertMeterLabel() {
        if (alertMeterValue != cachedAlertValue || cachedAlertLabel == null) {
            cachedAlertValue = alertMeterValue;
            cachedAlertLabel = "Alert Meter: " + cachedAlertValue;
        }
        return cachedAlertLabel;
    }

    private void drawUI() {
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);

        // Rows step down by a shared HUD_LINE_STEP from the top of the virtual space, so
        // the whole block scales with the font instead of sitting at fixed pixel offsets.
        float rowY = uiWorldH - HUD_MARGIN;

        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, alertMeterLabel(), HUD_MARGIN, rowY);
        rowY -= HUD_LINE_STEP;
        if (reactorUnlocked) {
            font.setColor(Color.GREEN);
            font.draw(batch, "Reactor unlocked! Find the exit gate.", HUD_MARGIN, rowY);
        } else {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, "Find your terminal and solve it together with your partner to unlock the reactor.",
                HUD_MARGIN, rowY);
        }
        rowY -= HUD_LINE_STEP;
        if (nearTerminal) {
            font.setColor(Color.CYAN);
            font.draw(batch, "Press E to interact", HUD_MARGIN, rowY);
        }
        rowY -= HUD_LINE_STEP;
        if (isDebug && popupP1.isOpen() && popupP2.isOpen()) {
            font.setColor(Color.ORANGE);
            font.draw(batch, "SPACE = switch terminal   (typing into: player " + debugFocusedPlayerId + ")",
                HUD_MARGIN, rowY);
        }
        batch.end();

        // Drawn once per split-screen half — otherwise both bars land inside the left
        // half only, since the window is split but this UI pass uses absolute screen coords.
        float half = uiWorldW / 2f;
        float barY = rowY - HUD_LINE_STEP;
        float barGap = HUD_BAR_W + 20f;
        shape.begin(ShapeRenderer.ShapeType.Filled);
        drawHealthBar(HUD_MARGIN, barY, player1.health, Color.CYAN);
        drawHealthBar(HUD_MARGIN + barGap, barY, player2.health, Color.MAGENTA);
        drawHealthBar(half + HUD_MARGIN, barY, player1.health, Color.CYAN);
        drawHealthBar(half + HUD_MARGIN + barGap, barY, player2.health, Color.MAGENTA);
        shape.end();

        // Inventories draw under the terminal popups: handleInventoryInput() already
        // closes them whenever a popup opens, so the two never actually overlap, but this
        // keeps the ordering correct if that ever changes.
        inventories.render(shape, batch, uiWorldW, uiWorldH, player1, player2, COLOR_CYAN, COLOR_MAGENTA);

        popupP1.render(shape, batch, uiWorldW, uiWorldH);
        popupP2.render(shape, batch, uiWorldW, uiWorldH);

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
            (isHost || isDebug)
                ? "ESC = Main Menu\nENTER = Restart Level"
                : "ESC = Main Menu\nWaiting for host to restart...");
    }

    /**
     * Shared end-of-level panel, styled to the launcher's language: dimmed backdrop, dark
     * notched panel, magenta eyebrow, accent title, ASCII only (the default BitmapFont has
     * no box-drawing or dash glyphs and renders them as empty squares).
     */
    private void drawBanner(String eyebrow, String title, Color titleColor, String body, String hint) {
        // Virtual units throughout — the panel keeps the same proportions on every display.
        float screenW = uiWorldW;
        float screenH = uiWorldH;
        float panelW = Math.min(980f, screenW * 0.6f);
        float panelH = 420f;
        float panelX = (screenW - panelW) / 2f;
        float panelY = (screenH - panelH) / 2f;
        float pad = 52f;
        float notch = 22f;

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
        // Steps scale with the font sizes above — see UI_REF_W/UI_REF_H.
        subFont.setColor(COLOR_MAGENTA);
        subFont.draw(batch, eyebrow, panelX + pad, lineY, contentW, Align.left, true);
        lineY -= 54;

        bannerFont.setColor(titleColor);
        bannerFont.draw(batch, title, panelX + pad, lineY, contentW, Align.left, false);
        lineY -= 98;

        subFont.setColor(COLOR_TEXT);
        lineY -= subFont.draw(batch, body, panelX + pad, lineY, contentW, Align.left, true).height + 33;

        subFont.setColor(COLOR_DIM);
        subFont.draw(batch, hint, panelX + pad, lineY, contentW, Align.left, true);
        batch.end();
    }

    @Override
    public void show() {}

    @Override
    public void resize(int w, int h) {
        // Ignore the passed logical w/h — these re-read the back buffer directly so they
        // stay in the same physical-pixel space as drawWorld()'s glViewport calls, then
        // map it back onto the fixed virtual design space. Both matter when a projector
        // is plugged in and the desktop switches resolution mid-session.
        updateUiCamera();
        updateWorldCameras();
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