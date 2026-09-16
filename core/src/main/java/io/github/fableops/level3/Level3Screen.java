package io.github.fableops.level3;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.EnemySprites;
import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.SwarmController;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level3.network.Level3EnemyStateMessage;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.messages.LevelRestartMessage;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.story.StoryGate;
import io.github.fableops.ui.SplitScreen;
import io.github.fableops.ui.UiViewport;
import io.github.fableops.ui.hud.Hud;

// Level 3 foundation: the Warden's Brawl and Hacker constructs close in on both players in one
// shared arena. No terminals, loot or boss AI yet - this is the base the real encounter builds on.
// Movement goes through GameServer/GameClient, everything else through the message channel,
// exactly like Level 1 and Level 2
public class Level3Screen implements Screen, SplitScreen.HalfRenderer {

    private static final String TITLE = "THE WARDEN";

    // Twice the usual view. The city art is 1536x1024, and at the standard 720 the world scale
    // needed to keep the operators in proportion magnified it about 4.5x, which turned it to mush.
    // Pulling the camera back instead lands at about 2.25x - sharper than the other levels - and
    // shows roughly half the city at once
    private static final float CAM_HEIGHT = 1440f;

    private static final float ATTACK_RANGE = 120f;
    private static final int ATTACK_DAMAGE = 15;
    private static final int CONTACT_DAMAGE = 10; // per second of contact
    // Enemy updates are sent 20 times a second, not every frame
    private static final float STATE_INTERVAL = 1f / 20f;
    // A fallen player's death animation plays out before the mission failed window
    private static final float FAILURE_SCENE_DELAY = Player.DEATH_DURATION + 0.4f;

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shape = new ShapeRenderer();
    private final UiViewport ui = new UiViewport();
    private final PlayerInventories inventories;
    private final Level3Map world = new Level3Map();
    private final Hud hud;
    // Same swarm-drone visual Level 1 and Level 2 use - one shared instance, exactly like
    // Level2Screen's enemySprites field. "Brawl"/"Hacker" are spawn-pool/gameplay labels
    // (see Level3EnemySpawner), not distinct enemy art
    private final EnemySprites enemySprites = new EnemySprites();
    // Two independent pools (spawn limits, network sync) sharing one visual, following the same
    // authoritative-host swarm model Level 1 and Level 2 already use
    private final SwarmController brawlSwarm = new SwarmController(enemySprites);
    private final SwarmController hackerSwarm = new SwarmController(enemySprites);
    // No automatic wave spawning in Level 3 - the pools above stay empty until a real Warden
    // encounter feeds them directly. Level3EnemySpawner is kept as-is (unused for now), ready to
    // be wired back in - or reused - once that boss logic exists
    private final Player player1;
    private final Player player2;

    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;
    // Side 1's operator, for the HUD cards
    private final Role sideOneRole;
    // Holds the game on a scenario window until both players confirm it
    private final StoryGate story;

    private boolean debugCollisionVisible = false; // F1
    private boolean missionFailed = false;
    // Both operators stood on the trigger strip. The Warden encounter itself is not built yet
    private boolean bossStarted = false;
    private boolean failureScenePending = false;
    private float failureSceneTimer = 0f;
    private String failureCause = "";
    private float stateTimer = 0f;
    private boolean disposed = false;

    // Client only: the host's swarms as last reported
    private final List<float[]> remoteBrawlP1 = new ArrayList<>();
    private final List<float[]> remoteBrawlP2 = new ArrayList<>();
    private final List<float[]> remoteHackerP1 = new ArrayList<>();
    private final List<float[]> remoteHackerP2 = new ArrayList<>();
    // Host only, reused for every enemy snapshot
    private final List<float[]> positionBufferBrawlP1 = new ArrayList<>();
    private final List<float[]> positionBufferBrawlP2 = new ArrayList<>();
    private final List<float[]> positionBufferHackerP1 = new ArrayList<>();
    private final List<float[]> positionBufferHackerP2 = new ArrayList<>();

    public Level3Screen(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession,
                        StoryGate story, Role sideOneRole) {
        this(server, client, hostSession, clientSession, story, sideOneRole,
            new Player(sideOneRole.sheetName(), 0f, 0f, Input.Keys.W, Input.Keys.S, Input.Keys.A, Input.Keys.D, null, 1),
            new Player(sideOneRole.other().sheetName(), 0f, 0f, Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT, Input.Keys.RIGHT, null, 2),
            new Hud(), new PlayerInventories());
    }

    public Level3Screen(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession,
                        StoryGate story, Role sideOneRole, Player player1, Player player2,
                        Hud hud, PlayerInventories inventories) {
        this.sideOneRole = sideOneRole;
        this.server = server;
        this.client = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.story = story;
        this.isHost = (server != null);
        this.isDebug = (server == null && client == null);

        this.player1 = player1;
        this.player2 = player2;
        this.hud = hud;
        this.inventories = inventories;

        player1.setWorld(world, 1);
        player2.setWorld(world, 2);
        player2.setAlternateRightKey(Input.Keys.L);
        SplitScreen.fitCameras(player1, player2, CAM_HEIGHT);
        world.placeAtSpawn(player1, true);
        world.placeAtSpawn(player2, false);

        // Replace Level 2's listeners, that screen is disposed. Nothing needs a client-initiated
        // request yet (no terminals/loot/core here) - extension point for a Level3Controller once
        // the Warden encounter needs host-authoritative interaction, mirroring Level1Controller/Level2Controller
        if (isHost || isDebug) {
            if (hostSession != null) hostSession.setListener(story.wrap((type, body) -> { }));
        } else {
            // Messages come in on the network thread, so handle them on the render thread
            clientSession.setListener(story.wrap((type, body) ->
                Gdx.app.postRunnable(() -> onHostMessage(type, body))));
        }
    }

    // Client only
    private void onHostMessage(String type, String body) {
        switch (type) {
            case "LEVEL3_ENEMY_STATE": {
                Level3EnemyStateMessage state = Level3EnemyStateMessage.deserialize(body);
                boolean brawl = state.getKind() == Level3EnemyStateMessage.Kind.BRAWL;
                List<float[]> p1 = brawl ? remoteBrawlP1 : remoteHackerP1;
                List<float[]> p2 = brawl ? remoteBrawlP2 : remoteHackerP2;
                p1.clear();
                p1.addAll(state.getEnemiesP1());
                p2.clear();
                p2.addAll(state.getEnemiesP2());
                player1.health = state.getHealthP1();
                player2.health = state.getHealthP2();
                checkForDeath();
                break;
            }
            case "LEVEL_RESTART":
                restartLocalState();
                break;
            default:
                break;
        }
    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !inventories.anyOpen()) {
            Gdx.app.exit();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;
        if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) ui.cycleScale();

        inventories.handleInput(isHost || isDebug, !isHost || isDebug, missionFailed, player1, player2);
        player1.updateVisualState(delta);
        player2.updateVisualState(delta);

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient();
        }

        updateSwarms(delta);
        // Both machines have both players, so both reach the same answer without a message,
        // the same way Level 2 settles exitReached
        if (!missionFailed && !bossStarted && world.bothOnBossTrigger(player1, player2)) {
            bossStarted = true;
        }
        showFailureWhenReady(delta);
        SplitScreen.drawHalves(this, player1, player2);
        drawUI();
    }

    // Debug: both players on one keyboard, left mouse button for player 1 and right for player 2
    private void updateAsDebug(float delta) {
        boolean p1Free = !missionFailed && !inventories.isOpen(1);
        boolean p2Free = !missionFailed && !inventories.isOpen(2);
        if (p1Free) player1.update(delta); // WASD
        if (p2Free) player2.update(delta); // arrow keys
        attack(1, player1, p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT));
        attack(2, player2, p2Free && Gdx.input.isButtonPressed(Input.Buttons.RIGHT));
    }

    private void updateAsHost(float delta) {
        boolean p1Free = !missionFailed && !inventories.isOpen(1);
        if (p1Free) player1.update(delta);

        // With no client connected, Player 2 plays from this keyboard and mouse
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
        boolean free = !missionFailed && !inventories.isOpen(2);
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

    // Only hits when a new swing starts, so holding the button hits once per animation. Checks
    // both pools - a foundation-level simplification that can land one hit per pool per swing
    // if a Brawl and a Hacker construct are both in range; fine for now, an extension point once
    // combat needs a single cross-pool "nearest enemy" query
    private void attack(int side, Player player, boolean pressed) {
        if (!pressed || !player.startAttack()) return;
        brawlSwarm.attackNearest(side, player.centreX(), player.centreY(), ATTACK_RANGE, ATTACK_DAMAGE);
        hackerSwarm.attackNearest(side, player.centreX(), player.centreY(), ATTACK_RANGE, ATTACK_DAMAGE);
    }

    private void updateSwarms(float delta) {
        if (!isHost && !isDebug) return;
        if (!missionFailed) {
            brawlSwarm.update(delta, player1, player2, world);
            hackerSwarm.update(delta, player1, player2, world);
            // The arena is shared, so any construct hurts whichever player it reaches
            if (brawlSwarm.isTouchingAny(0, player1) || hackerSwarm.isTouchingAny(0, player1)) {
                player1.takeDamage(CONTACT_DAMAGE * delta);
            }
            if (brawlSwarm.isTouchingAny(0, player2) || hackerSwarm.isTouchingAny(0, player2)) {
                player2.takeDamage(CONTACT_DAMAGE * delta);
            }
            checkForDeath();
        }
        if (hostSession == null) return;

        stateTimer += delta;
        if (stateTimer < STATE_INTERVAL) return;
        stateTimer = 0f;
        hostSession.send(new Level3EnemyStateMessage(Level3EnemyStateMessage.Kind.BRAWL,
            brawlSwarm.positions(1, positionBufferBrawlP1), brawlSwarm.positions(2, positionBufferBrawlP2),
            player1.health, player2.health));
        hostSession.send(new Level3EnemyStateMessage(Level3EnemyStateMessage.Kind.HACKER,
            hackerSwarm.positions(1, positionBufferHackerP1), hackerSwarm.positions(2, positionBufferHackerP2),
            player1.health, player2.health));
    }

    // Host after contact damage, client when the host reports health
    private void checkForDeath() {
        if (missionFailed) return;
        boolean breakerDown = player1.health <= 0f;
        boolean listenerDown = player2.health <= 0f;
        if (!breakerDown && !listenerDown) return;
        missionFailed = true;
        failureCause = StoryGate.fallen(breakerDown, listenerDown);
        failureScenePending = true;
        failureSceneTimer = FAILURE_SCENE_DELAY;
    }

    // Waits for the death animation, then puts up the mission failed window
    private void showFailureWhenReady(float delta) {
        if (!failureScenePending) return;
        failureSceneTimer -= delta;
        if (failureSceneTimer > 0f) return;
        failureScenePending = false;
        story.showFailure("MAIN SCENARIO #3 — FAILED",
            "It was never told to hate them. But it was told to protect, and it has not stopped.",
            new String[][]{
                {"Cause", failureCause},
                {"Retry", (isHost || isDebug) ? "Press ENTER to restart the scenario."
                    : "The host restarts the scenario."}
            },
            (isHost || isDebug) ? this::restartLevel : null);
    }

    // The host's ENTER in the mission failed window
    private void restartLevel() {
        restartLocalState();
        if (hostSession != null) hostSession.send(new LevelRestartMessage());
    }

    private void restartLocalState() {
        brawlSwarm.reset();
        hackerSwarm.reset();
        remoteBrawlP1.clear();
        remoteBrawlP2.clear();
        remoteHackerP1.clear();
        remoteHackerP2.clear();
        missionFailed = false;
        bossStarted = false;
        failureScenePending = false;
        failureCause = "";
        resetPlayer(player1, true);
        resetPlayer(player2, false);
        story.closeFailure();
    }

    private void resetPlayer(Player player, boolean leftSpawn) {
        player.health = Player.MAX_HEALTH;
        player.resetVisualState();
        world.placeAtSpawn(player, leftSpawn);
    }

    // Called once per camera by SplitScreen.drawHalves()
    @Override
    public void drawHalf(OrthographicCamera camera) {
        world.render(batch, camera);
        world.renderOverlays(shape, camera, bossStarted);
        if (debugCollisionVisible) world.renderDebugCollision(batch, camera);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        if (isHost || isDebug) {
            enemySprites.drawAll(batch, camera, brawlSwarm.getEnemiesP1());
            enemySprites.drawAll(batch, camera, brawlSwarm.getEnemiesP2());
            enemySprites.drawAll(batch, camera, hackerSwarm.getEnemiesP1());
            enemySprites.drawAll(batch, camera, hackerSwarm.getEnemiesP2());
        } else {
            enemySprites.drawRemote(batch, camera, remoteBrawlP1);
            enemySprites.drawRemote(batch, camera, remoteBrawlP2);
            enemySprites.drawRemote(batch, camera, remoteHackerP1);
            enemySprites.drawRemote(batch, camera, remoteHackerP2);
        }
        player1.draw(batch);
        player2.draw(batch);
        // Last, so it covers anyone on the road behind the tower
        world.renderOverhang(batch);
        batch.end();
    }

    private void drawUI() {
        OrthographicCamera uiCamera = ui.camera();
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);

        hud.drawBanner(shape, batch, ui, TITLE, objective(), null, -1f);
        hud.drawPlayerCards(shape, batch, ui, player1, player2, sideOneRole);
        inventories.render(shape, batch, ui.width(), ui.height(), player1, player2,
            SplitScreen.ACCENT_P1, SplitScreen.ACCENT_P2);
    }

    private String objective() {
        return bossStarted
            ? "The Warden answers. Hold the line."
            : "Walk the road together, then both stand on the marked strip.";
    }

    @Override
    public void show() {}

    @Override
    public void resize(int w, int h) {
        // Uses the back buffer size instead of w and h (HiDPI)
        ui.update();
        SplitScreen.fitCameras(player1, player2, CAM_HEIGHT);
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
        inventories.dispose();
        player1.dispose();
        player2.dispose();
        world.dispose();
        enemySprites.dispose();
        hud.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}
