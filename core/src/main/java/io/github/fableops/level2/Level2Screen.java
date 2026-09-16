package io.github.fableops.level2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

import io.github.fableops.EnemySprites;
import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.SwarmController;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level2.loot.LootDrop;
import io.github.fableops.level2.loot.LootField;
import io.github.fableops.level2.network.CoreInteractRequestMessage;
import io.github.fableops.level2.network.CoreStateMessage;
import io.github.fableops.level2.network.GunStateMessage;
import io.github.fableops.level2.network.Level3StartMessage;
import io.github.fableops.level2.network.LootInteractRequestMessage;
import io.github.fableops.level2.network.LootPickedUpMessage;
import io.github.fableops.level3.Level3Screen;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.messages.EnemyStateMessage;
import io.github.fableops.network.messages.LevelRestartMessage;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.story.StoryBeat;
import io.github.fableops.story.StoryGate;
import io.github.fableops.ui.SplitScreen;
import io.github.fableops.ui.UiViewport;
import io.github.fableops.ui.hud.Hud;

// Level 2: carry the core from the pedestal to the socket and leave through the exit together,
// while enemy waves keep closing in around both players
public class Level2Screen implements Screen, SplitScreen.HalfRenderer {

    private static final String TITLE = "UNSTABLE CORE MAZE";

    private static final float ATTACK_RANGE = 120f;
    private static final int ATTACK_DAMAGE = 15;
    private static final int CONTACT_DAMAGE = 10; // per second of contact
    // A wave comes in around each player this often. Taking the core keeps the pressure up by
    // shortening the gap rather than by sending bigger groups: one enemy every 5s instead of
    // every 8s is still 1.6x the pre-core rate, but they arrive alone and can be fought one at a time
    private static final float FIRST_WAVE_DELAY = 5f;
    private static final float WAVE_INTERVAL = 8f;
    private static final float WAVE_INTERVAL_CORE_TAKEN = 5f;
    private static final int WAVE_SIZE = 1;
    private static final int WAVE_SIZE_CORE_TAKEN = 1;
    private static final int MAX_ENEMIES_PER_PLAYER = 10;
    // Level 2's own spawn pacing (SwarmController defaults to 0.15f/0.35f for Level 1).
    // ~3x slower so enemies trickle in one at a time instead of appearing as a bunch.
    private static final float SPAWN_INITIAL_DELAY_SECONDS = 0.45f;
    private static final float SPAWN_INTERVAL_SECONDS = 1.05f;
    // Enemy and gun updates are sent 20 times a second, not every frame
    private static final float STATE_INTERVAL = 1f / 20f;
    // A fallen player's death animation plays out before the mission failed window
    private static final float FAILURE_SCENE_DELAY = Player.DEATH_DURATION + 0.4f;

    private final Game game;
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shape = new ShapeRenderer();
    private final UiViewport ui = new UiViewport();
    private final PlayerInventories inventories;
    private final Level2Map world = new Level2Map();
    private final CoreObject core;
    private final LootField loot;
    private final Gun gun = new Gun();
    private final Hud hud;
    private final EnemySprites enemySprites;
    private final SwarmController swarm;
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
    // null on the client
    private final Level2Controller controller;
    // Holds the game on a scenario window until both players confirm it
    private final StoryGate story;

    private String localPrompt; // what E or G does for this machine's player right now, or null
    private boolean exitReached = false;
    private boolean debugCollisionVisible = false; // F1
    private boolean missionFailed = false;
    private boolean failureScenePending = false;
    private float failureSceneTimer = 0f;
    private String failureCause = "";
    private float waveTimer = FIRST_WAVE_DELAY;
    private float stateTimer = 0f;
    private boolean disposed = false;
    private boolean advancingToLevel3 = false; // guards advanceToLevel3() against running twice

    // Client only: the host's swarm as last reported
    private final List<float[]> remoteEnemiesP1 = new ArrayList<>();
    private final List<float[]> remoteEnemiesP2 = new ArrayList<>();
    // Host only, reused for every enemy snapshot
    private final List<float[]> positionBufferP1 = new ArrayList<>();
    private final List<float[]> positionBufferP2 = new ArrayList<>();

    public Level2Screen(Game game, GameServer server, GameClient client, HostSession hostSession,
                        ClientSession clientSession, StoryGate story, Role sideOneRole) {
        this(game, server, client, hostSession, clientSession, story, sideOneRole,
            new Player(sideOneRole.sheetName(), 0f, 0f, Input.Keys.W, Input.Keys.S, Input.Keys.A, Input.Keys.D, null, 1),
            new Player(sideOneRole.other().sheetName(), 0f, 0f, Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT, Input.Keys.RIGHT, null, 2),
            new EnemySprites(), new Hud(), new PlayerInventories(), new Random().nextLong());
    }

    // lootSeed: same value on host and client (relayed in Level2StartMessage) so both machines
    // roll the same random loot layout instead of the client generating its own
    public Level2Screen(Game game, GameServer server, GameClient client, HostSession hostSession,
                        ClientSession clientSession, StoryGate story, Role sideOneRole, Player player1, Player player2,
                        EnemySprites enemySprites, Hud hud, PlayerInventories inventories, long lootSeed) {
        this.game = game;
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
        this.enemySprites = enemySprites;
        this.hud = hud;
        this.inventories = inventories;
        this.core = new CoreObject(inventories);
        this.loot = new LootField(world, lootSeed);
        this.swarm = new SwarmController(enemySprites, SPAWN_INITIAL_DELAY_SECONDS, SPAWN_INTERVAL_SECONDS);

        player1.setWorld(world, 1);
        player2.setWorld(world, 2);
        player2.setAlternateRightKey(Input.Keys.L);
        SplitScreen.fitCameras(player1, player2);
        world.placeAtSpawn(player1, true);
        world.placeAtSpawn(player2, false);

        // Replace Level 1's listeners, that screen is disposed
        if (isHost || isDebug) {
            controller = new Level2Controller(hostSession, core, loot, gun, inventories, world, player1, player2);
            if (hostSession != null) hostSession.setListener(story.wrap(controller.asMessageListener()));
        } else {
            controller = null;
            // Messages come in on the network thread, so handle them on the render thread
            clientSession.setListener(story.wrap((type, body) ->
                Gdx.app.postRunnable(() -> onHostMessage(type, body))));
        }
    }

    // Client only
    private void onHostMessage(String type, String body) {
        switch (type) {
            case "CORE_STATE":
                CoreStateMessage coreState = CoreStateMessage.deserialize(body);
                core.set(coreState.getState(), coreState.getCarrierId());
                if (coreState.getState() == CoreObject.State.IN_SOCKET) world.openExit();
                break;
            case "LOOT_PICKED_UP":
                LootPickedUpMessage picked = LootPickedUpMessage.deserialize(body);
                loot.applyPickup(picked.getLootId(), picked.getPlayerId(), inventories);
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
            case "GUN_STATE":
                gun.apply(GunStateMessage.deserialize(body));
                break;
            case "LEVEL_RESTART":
                restartLocalState();
                break;
            case "LEVEL3_START":
                advanceToLevel3();
                break;
            default:
                break;
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

        inventories.handleInput(isHost || isDebug, !isHost || isDebug, missionFailed, player1, player2);
        core.update(delta);
        loot.update(delta);
        gun.update(delta);
        player1.updateVisualState(delta);
        player2.updateVisualState(delta);

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient();
        }

        handleInteraction();
        updateSwarm(delta);
        if (!missionFailed && !exitReached && world.isExitOpen()) {
            exitReached = world.isInExit(player1) && world.isInExit(player2);
        }
        showFailureWhenReady(delta);
        SplitScreen.drawHalves(this, player1, player2);
        drawUI();
        // Has to be last, advancing disposes this screen
        checkExitToLevel3();
    }

    // The host decides, the client waits for LEVEL3_START
    private void checkExitToLevel3() {
        if ((!isHost && !isDebug) || missionFailed || !exitReached) return;
        advanceToLevel3();
    }

    private void advanceToLevel3() {
        if (advancingToLevel3) return;
        advancingToLevel3 = true;
        story.begin(StoryBeat.LEVEL_3);
        if (hostSession != null) hostSession.send(new Level3StartMessage());
        Level3Screen next = new Level3Screen(server, client, hostSession, clientSession, story, sideOneRole,
            player1, player2, hud, inventories);
        disposed = true;
        disposeLevel2OnlyResources();
        game.setScreen(next);
    }

    // Debug: both players on one keyboard, left mouse button for player 1 and right for player 2.
    // An open inventory freezes only its own player
    private void updateAsDebug(float delta) {
        boolean p1Free = !missionFailed && !inventories.isOpen(1);
        boolean p2Free = !missionFailed && !inventories.isOpen(2);
        if (p1Free) player1.update(delta); // WASD
        if (p2Free) player2.update(delta); // arrow keys
        act(1, player1, p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT),
            p1Free && Gdx.input.isKeyJustPressed(Input.Keys.R));
        act(2, player2, p2Free && Gdx.input.isButtonPressed(Input.Buttons.RIGHT),
            p2Free && Gdx.input.isKeyJustPressed(Input.Keys.CONTROL_RIGHT));
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
                Gdx.input.isButtonPressed(Input.Buttons.RIGHT),
                Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT));
        if (!missionFailed) player2.applyInput(p2Input, delta);

        act(1, player1, p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT),
            p1Free && Gdx.input.isKeyJustPressed(Input.Keys.R));
        act(2, player2, !missionFailed && p2Input.attack, !missionFailed && p2Input.reload);
        server.pushState(new WorldState(player1, player2));
    }

    private void updateAsClient() {
        // The client is its own machine: WASD to move, right mouse button to attack, R to reload
        boolean free = !missionFailed && !inventories.isOpen(2);
        client.pushInput(free
            ? new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.W),
                Gdx.input.isKeyPressed(Input.Keys.S),
                Gdx.input.isKeyPressed(Input.Keys.A),
                Gdx.input.isKeyPressed(Input.Keys.D),
                Gdx.input.isButtonPressed(Input.Buttons.RIGHT),
                Gdx.input.isKeyPressed(Input.Keys.R))
            : new PlayerInput(false, false, false, false));

        client.pollState().applyTo(player1, player2);
    }

    // Attack shoots while this player holds the gun with ammo left, otherwise it's a melee swing
    private void act(int playerId, Player player, boolean attack, boolean reload) {
        if (reload) gun.reload(playerId);
        if (!attack || gun.trigger(playerId, player, swarm, world)) return;
        if (player.startAttack()) {
            swarm.attackNearest(0, player.centreX(), player.centreY(), ATTACK_RANGE, ATTACK_DAMAGE);
        }
    }

    private void updateSwarm(float delta) {
        if (controller == null) return;
        if (!missionFailed) {
            spawnWaves(delta);
            swarm.update(delta, player1, player2, world);
            // The maze is shared, so any enemy hurts whichever player it reaches
            if (swarm.isTouchingAny(0, player1)) player1.takeDamage(CONTACT_DAMAGE * delta);
            if (swarm.isTouchingAny(0, player2)) player2.takeDamage(CONTACT_DAMAGE * delta);
            checkForDeath();
        }
        if (hostSession == null) return;

        stateTimer += delta;
        if (stateTimer < STATE_INTERVAL) return;
        stateTimer = 0f;
        hostSession.send(new EnemyStateMessage(
            swarm.positions(1, positionBufferP1),
            swarm.positions(2, positionBufferP2),
            player1.health, player2.health));
        if (gun.getOwner() != 0) hostSession.send(gun.toMessage());
    }

    // Taking the core brings the next wave forward as well as every one after it
    private void spawnWaves(float delta) {
        boolean coreTaken = core.getState() != CoreObject.State.ON_PEDESTAL;
        float interval = coreTaken ? WAVE_INTERVAL_CORE_TAKEN : WAVE_INTERVAL;
        waveTimer = Math.min(waveTimer, interval) - delta;
        if (waveTimer > 0f) return;

        waveTimer = interval;
        int size = coreTaken ? WAVE_SIZE_CORE_TAKEN : WAVE_SIZE;
        spawnAround(1, player1, size);
        spawnAround(2, player2, size);
    }

    private void spawnAround(int side, Player player, int size) {
        int room = MAX_ENEMIES_PER_PLAYER - swarm.count(side);
        if (room > 0) swarm.spawnAround(side, player.x, player.y, Math.min(size, room), world);
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
        story.showFailure("MAIN SCENARIO #2 — FAILED",
            "The dark keeps what it is given. When they stand at the mouth of the maze again their hands are "
                + "empty, and the core waits on its pedestal as if no one had ever touched it.",
            new String[][]{
                {"Cause", failureCause},
                {"Penalty", "All loot from this scenario is lost."},
                {"Retry", controller != null ? "Press ENTER to restart the scenario."
                    : "The host restarts the scenario."}
            },
            controller != null ? this::restartLevel : null);
    }

    // The host's ENTER in the mission failed window
    private void restartLevel() {
        restartLocalState();
        if (hostSession != null) hostSession.send(new LevelRestartMessage());
    }

    // Level 2 starts over and everything the players picked up is lost
    private void restartLocalState() {
        core.set(CoreObject.State.ON_PEDESTAL, 0);
        inventories.clear();
        loot.reset();
        gun.reset();
        swarm.reset();
        remoteEnemiesP1.clear();
        remoteEnemiesP2.clear();
        world.closeExit();
        exitReached = false;
        missionFailed = false;
        failureScenePending = false;
        failureCause = "";
        waveTimer = FIRST_WAVE_DELAY;
        resetPlayer(player1, true);
        resetPlayer(player2, false);
        story.closeFailure();
    }

    private void resetPlayer(Player player, boolean leftSpawn) {
        player.health = Player.MAX_HEALTH;
        player.resetVisualState();
        world.placeAtSpawn(player, leftSpawn);
    }

    // E takes or places the core. G picks up loot. The client only asks, the host decides
    private void handleInteraction() {
        if (missionFailed) {
            localPrompt = null;
            return;
        }
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

        // Loot, enemies and players in one batch pass
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        loot.render(batch, core.getState());
        if (controller != null) {
            enemySprites.drawAll(batch, camera, swarm.getEnemiesP1());
            enemySprites.drawAll(batch, camera, swarm.getEnemiesP2());
        } else {
            enemySprites.drawRemote(batch, camera, remoteEnemiesP1);
            enemySprites.drawRemote(batch, camera, remoteEnemiesP2);
        }
        player1.draw(batch);
        player2.draw(batch);
        batch.end();

        drawEffects(camera);
    }

    // The core glowing in its socket and the gun's tracer, in one filled pass. On the pedestal the core
    // is part of the map image, and while carried it's in the inventory
    private void drawEffects(OrthographicCamera camera) {
        boolean coreInSocket = core.getState() == CoreObject.State.IN_SOCKET;
        if (!coreInSocket && !gun.hasShotToDraw()) return;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shape.setProjectionMatrix(camera.combined);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        if (coreInSocket) {
            Rectangle socket = world.getSocketZone();
            core.draw(shape, socket.x + socket.width / 2f, socket.y + socket.height / 2f);
        }
        gun.drawShot(shape);
        shape.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawUI() {
        OrthographicCamera uiCamera = ui.camera();
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);

        hud.drawBanner(shape, batch, ui, TITLE, objective(), localPrompt, -1f);
        hud.drawPlayerCards(shape, batch, ui, player1, player2, sideOneRole);
        gun.drawHud(shape, batch, hud.font(), ui.width());
        inventories.render(shape, batch, ui.width(), ui.height(), player1, player2,
            SplitScreen.ACCENT_P1, SplitScreen.ACCENT_P2);
    }

    private String objective() {
        if (exitReached) return "Exit reached. Proceeding to the Warden...";
        return switch (core.getState()) {
            case ON_PEDESTAL -> "Find the Unstable Core on its pedestal and take it.";
            case CARRIED -> "Carry the core to the reactor socket.";
            default -> "Exit gate open! Both of you step inside it.";
        };
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
        disposeLevel2OnlyResources();
        inventories.dispose();
        player1.dispose();
        player2.dispose();
        hud.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }

    // Only resources exclusive to Level 2 and not passed to Level 3 (player1/player2/hud/inventories
    // carry forward; Level 3 has its own enemy visuals, so the drone enemySprites doesn't)
    private void disposeLevel2OnlyResources() {
        batch.dispose();
        shape.dispose();
        world.dispose();
        core.dispose();
        loot.dispose();
        enemySprites.dispose();
    }
}
