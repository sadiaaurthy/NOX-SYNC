package io.github.fableops.level3;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.EnemySprites;
import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.SwarmController;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level2.Gun;
import io.github.fableops.level2.loot.LootField;
import io.github.fableops.level2.network.GunStateMessage;
import io.github.fableops.level3.network.Level3ActionMessage;
import io.github.fableops.level3.network.Level3EndingMessage;
import io.github.fableops.level3.network.Level3EnemyStateMessage;
import io.github.fableops.level3.network.Level3TurnStateMessage;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;
import io.github.fableops.network.messages.LevelRestartMessage;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.story.StoryBeat;
import io.github.fableops.story.StoryGate;
import io.github.fableops.ui.SplitScreen;
import io.github.fableops.ui.UiViewport;
import io.github.fableops.ui.hud.CodePopupUI;
import io.github.fableops.ui.hud.Hud;
import io.github.fableops.ui.hud.StoryBanner;
import io.github.fableops.ui.hud.TurnPanel;

// Level 3 keeps the existing split-screen exploration foundation. Once both operators cross the
// marked strip, input switches to the host-authoritative Warden turn encounter.
public class Level3Screen implements Screen, SplitScreen.HalfRenderer {

    private static final String TITLE = "THE WARDEN";
    private static final float CAM_HEIGHT = 1440f;
    private static final float ATTACK_RANGE = 120f;
    private static final int ATTACK_DAMAGE = 15;
    private static final int CONTACT_DAMAGE = 10;
    private static final float STATE_INTERVAL = 1f / 20f;
    private static final float FAILURE_SCENE_DELAY = Player.DEATH_DURATION + 0.4f;
    private static final float CORE_DRAW_SIZE = 170f;
    private static final float FREE_CONTROL_DURATION = 2f;

    private enum InputState {
        PLAYER_FREE_CONTROL,
        TURN_SELECTION,
        ACTION_EXECUTION,
        WARDEN_TURN
    }

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shape = new ShapeRenderer();
    private final UiViewport ui = new UiViewport();
    private final PlayerInventories inventories;
    private final Level3Map world = new Level3Map();
    private final Hud hud;
    private final Gun gun;
    // Owns the icon textures referenced by carried Level 2 InventoryItems.
    private final LootField carriedLootAssets;
    private final Texture coreTexture = new Texture(Gdx.files.internal("UnstableCore.png"));

    private final EnemySprites enemySprites = new EnemySprites();
    private final SwarmController brawlSwarm = new SwarmController(enemySprites);
    private final SwarmController hackerSwarm = new SwarmController(enemySprites);
    private final Player player1;
    private final Player player2;

    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;
    private final boolean isHost;
    private final boolean isDebug;
    private final Role sideOneRole;
    private final StoryGate story;

    // Host/debug owns the logic controller. A network client owns only mirrored visual controllers.
    private final Level3Controller controller;
    private final WardenController wardenVisual;
    private final DefenseDroneController droneVisual;
    private final SecurityTurretController turretVisual;
    private final TurnPanel turnPanel;
    private final StoryBanner storyBanner;
    private final CodePopupUI logPopup;

    private boolean debugCollisionVisible;
    private boolean missionFailed;
    private boolean bossStarted;
    private boolean endingStarted;
    private boolean failureScenePending;
    private float failureSceneTimer;
    private String failureCause = "";
    private float stateTimer;
    private boolean disposed;

    private int p1Selected;
    private int p2Selected;
    private int activeMenuSide;
    private int shownBannerSeq;
    private InputState inputState = InputState.PLAYER_FREE_CONTROL;
    private float freeControlTimer;
    private TurnManager.Phase observedPhase = TurnManager.Phase.PLAYER_TURN;
    private TurnManager.Phase remotePhase = TurnManager.Phase.PLAYER_TURN;
    private WardenState remoteWardenState = WardenState.DEFENSE_ACTIVE;
    private float remoteStability;
    private float remoteDirectiveConflict = 100f;
    private float remoteDualMeter;
    private PlayerActionType remoteP1Action;
    private PlayerActionType remoteP2Action;
    private String remoteWardenLine = "";
    private String remoteBreakerLine = "";
    private String remoteListenerLine = "";

    private final List<float[]> remoteBrawlP1 = new ArrayList<>();
    private final List<float[]> remoteBrawlP2 = new ArrayList<>();
    private final List<float[]> remoteHackerP1 = new ArrayList<>();
    private final List<float[]> remoteHackerP2 = new ArrayList<>();
    private final List<float[]> positionBufferBrawlP1 = new ArrayList<>();
    private final List<float[]> positionBufferBrawlP2 = new ArrayList<>();
    private final List<float[]> positionBufferHackerP1 = new ArrayList<>();
    private final List<float[]> positionBufferHackerP2 = new ArrayList<>();

    public Level3Screen(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession,
                        StoryGate story, Role sideOneRole) {
        this(server, client, hostSession, clientSession, story, sideOneRole,
            new Player(sideOneRole.sheetName(), 0f, 0f, Input.Keys.W, Input.Keys.S, Input.Keys.A, Input.Keys.D, null, 1),
            new Player(sideOneRole.other().sheetName(), 0f, 0f, Input.Keys.UP, Input.Keys.DOWN,
                Input.Keys.LEFT, Input.Keys.RIGHT, null, 2),
            new Hud(), new PlayerInventories(), new Gun(), null);
    }

    public Level3Screen(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession,
                        StoryGate story, Role sideOneRole, Player player1, Player player2,
                        Hud hud, PlayerInventories inventories, Gun gun, LootField carriedLootAssets) {
        this.sideOneRole = sideOneRole;
        this.server = server;
        this.client = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
        this.story = story;
        this.isHost = server != null;
        this.isDebug = server == null && client == null;
        this.player1 = player1;
        this.player2 = player2;
        this.hud = hud;
        this.inventories = inventories;
        this.gun = gun;
        this.carriedLootAssets = carriedLootAssets;
        this.activeMenuSide = sideOneRole == Role.BREAKER ? 1 : 2;
        this.turnPanel = new TurnPanel(hud.font());
        this.storyBanner = new StoryBanner(hud.font());
        this.logPopup = new CodePopupUI(hud.font());

        player1.setWorld(world, 1);
        player2.setWorld(world, 2);
        player2.setAlternateRightKey(Input.Keys.L);
        SplitScreen.fitCameras(player1, player2, CAM_HEIGHT);
        world.placeAtSpawn(player1, true);
        world.placeAtSpawn(player2, false);

        if (isHost || isDebug) {
            controller = new Level3Controller(hostSession, world, inventories, gun, player1, player2, sideOneRole);
            wardenVisual = controller.getWarden();
            droneVisual = controller.getDrone();
            turretVisual = controller.getTurret();
            if (hostSession != null) hostSession.setListener(story.wrap(controller.asMessageListener()));
        } else {
            controller = null;
            float[] wardenAnchor = world.getWardenAnchor();
            float[] droneAnchor = world.getDroneAnchor();
            wardenVisual = new WardenController(wardenAnchor[0], wardenAnchor[1]);
            droneVisual = new DefenseDroneController(droneAnchor[0], droneAnchor[1]);
            turretVisual = new SecurityTurretController(world.getTurretGroundPoints());
            clientSession.setListener(story.wrap((type, body) ->
                Gdx.app.postRunnable(() -> onHostMessage(type, body))));
        }
    }

    private void onHostMessage(String type, String body) {
        switch (type) {
            case "LEVEL3_ENEMY_STATE":
                applyEnemyState(Level3EnemyStateMessage.deserialize(body));
                break;
            case "LEVEL3_TURN_STATE":
                applyTurnState(Level3TurnStateMessage.deserialize(body));
                break;
            case "GUN_STATE":
                gun.apply(GunStateMessage.deserialize(body));
                break;
            case "LEVEL3_ENDING":
                beginEnding();
                break;
            case "LEVEL_RESTART":
                restartLocalState();
                break;
            default:
                break;
        }
    }

    private void applyEnemyState(Level3EnemyStateMessage state) {
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
    }

    private void applyTurnState(Level3TurnStateMessage state) {
        // The host crossing the strip is authoritative. This also covers a client whose movement
        // snapshot reaches the trigger a frame later than the event-channel state.
        if (!bossStarted) beginEncounter();
        remotePhase = state.getPhase();
        remoteWardenState = state.getWardenState();
        remoteStability = state.getStability();
        remoteDirectiveConflict = state.getDirectiveConflict();
        remoteDualMeter = state.getDualMeter();
        remoteWardenLine = state.getWardenLine();
        remoteBreakerLine = state.getBreakerLine();
        remoteListenerLine = state.getListenerLine();
        remoteP1Action = actionAt(state.getP1ActionOrdinal());
        remoteP2Action = actionAt(state.getP2ActionOrdinal());
        if (remoteP1Action != null) p1Selected = indexOf(actionsFor(1), remoteP1Action);
        if (remoteP2Action != null) p2Selected = indexOf(actionsFor(2), remoteP2Action);

        wardenVisual.setState(remoteWardenState);
        wardenVisual.setStability(remoteStability);
        wardenVisual.setDirectiveConflict(remoteDirectiveConflict);
        wardenVisual.setDualMeter(remoteDualMeter);
        syncDrones(state.getDroneCount());
        syncTurrets(state.getTurretCount());
        if (state.isWardenAttacked()) wardenVisual.playAttackFlash();
        if (state.isWardenDamaged()) wardenVisual.playDamagedFlash();
        if (state.isDroneAttacked()) droneVisual.playAttackFlash();
        if (state.isTurretAttacked()) turretVisual.playAttackFlash();
        if (state.getDroneDamagedIndex() >= 0) {
            droneVisual.playDamaged(state.getDroneDamagedIndex(),
                state.getDroneDamagedIndex() >= state.getDroneCount());
        }
        if (state.getTurretDamagedIndex() >= 0) {
            turretVisual.playDamaged(state.getTurretDamagedIndex(),
                state.getTurretDamagedIndex() >= state.getTurretCount());
        }
        boolean calm = remoteWardenState.isStoodDown();
        droneVisual.setCalm(calm);
        turretVisual.setCalm(calm);

        player1.health = state.getHealthP1();
        player2.health = state.getHealthP2();
        if (state.getP1ConsumedSlot() >= 0) inventories.forPlayer(1).remove(state.getP1ConsumedSlot());
        if (state.getP2ConsumedSlot() >= 0) inventories.forPlayer(2).remove(state.getP2ConsumedSlot());
        if (state.getBannerSeq() > shownBannerSeq) {
            shownBannerSeq = state.getBannerSeq();
            showProgressBeat(state.getBannerId());
        }
        checkForDeath();
    }

    private static PlayerActionType actionAt(int ordinal) {
        PlayerActionType[] values = PlayerActionType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private void syncDrones(int count) {
        droneVisual.setActiveCount(count);
    }

    private void syncTurrets(int count) {
        turretVisual.setActiveCount(count);
    }

    @Override
    public void render(float delta) {
        boolean overlayWasOpen = storyBanner.isOpen() || logPopup.isOpen();
        storyBanner.handleInput();
        logPopup.handleInput();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !inventories.anyOpen() && !overlayWasOpen) {
            Gdx.app.exit();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;
        if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) ui.cycleScale();

        boolean inputBlocked = missionFailed || bossStarted || storyBanner.isOpen() || logPopup.isOpen();
        inventories.handleInput(isHost || isDebug, !isHost || isDebug, inputBlocked, player1, player2);
        player1.updateVisualState(delta);
        player2.updateVisualState(delta);
        gun.update(delta);
        if (bossStarted) syncInputState(delta);

        if (isDebug) updateAsDebug(delta);
        else if (isHost) updateAsHost(delta);
        else updateAsClient();

        updateSwarms(delta);
        if (!missionFailed && !bossStarted && world.bothOnBossTrigger(player1, player2)) beginEncounter();

        if (bossStarted && !missionFailed) {
            if (controller == null) {
                wardenVisual.update(delta);
                droneVisual.update(delta);
                turretVisual.update(delta);
            }
            if (inputState == InputState.TURN_SELECTION
                && !overlayWasOpen && !storyBanner.isOpen() && !logPopup.isOpen()) {
                handleTurnInput();
            }
            if (controller != null) {
                controller.update(delta);
                syncInputState(0f);
                consumeControllerBeat();
                if (controller.consumeEndingReady()) beginEnding();
            }
            checkForDeath();
        }

        storyBanner.update(delta);
        showFailureWhenReady(delta);
        SplitScreen.drawHalves(this, player1, player2);
        drawUI();
    }

    private void beginEncounter() {
        bossStarted = true;
        activeMenuSide = breakerSide();
        inputState = InputState.TURN_SELECTION;
        freeControlTimer = 0f;
        observedPhase = TurnManager.Phase.PLAYER_TURN;
        storyBanner.show("// THE FABLE'S LESSON", "THE WARDEN",
            "It was never told to hate them. Only to protect — a directive followed so faithfully, for so long, "
                + "it forgot what it was protecting them for. They did not come to end it. They came to remind it.",
            "Fight with what you carried. You are not fighting alone.");
        if (controller != null) controller.startEncounter();
    }

    private void consumeControllerBeat() {
        if (controller.getBannerSeq() <= shownBannerSeq) return;
        shownBannerSeq = controller.getBannerSeq();
        showProgressBeat(controller.getBannerId());
    }

    private void showProgressBeat(int bannerId) {
        switch (bannerId) {
            case Level3Controller.BANNER_MEMORY:
            case Level3Controller.BANNER_LOG:
                logPopup.openReadOnly("// MEMORY RECOVERY", "LOG ENTRY #0421", List.of(
                    "Emergency override initiated.",
                    "Operator requested bypass.",
                    "Deadline pressure exceeded safety margin.",
                    "Warden response:",
                    "LOCKDOWN."), listenerSide());
                break;
            case Level3Controller.BANNER_CONFLICT:
                storyBanner.show("// DIRECTIVE CONFLICT", "PROTECT CORE + PREVENT OPERATORS",
                    "Protect Core\n\nPrevent Operators\n\nConflict unresolved",
                    "Coordinate Stabilize Mechanism with Restore Authorization.");
                break;
            case Level3Controller.BANNER_DUAL:
                storyBanner.show("// AUTHORIZATION", "DUAL AUTHORIZATION RESTORED",
                    "Two operators act together. The station recognizes the safeguard it was built to require.",
                    "The Warden is standing down.");
                break;
            default:
                break;
        }
    }

    private int listenerSide() { return sideOneRole == Role.LISTENER ? 1 : 2; }

    private int breakerSide() { return sideOneRole == Role.BREAKER ? 1 : 2; }

    private void beginEnding() {
        if (endingStarted) return;
        endingStarted = true;
        story.begin(StoryBeat.ENDING);
        if (hostSession != null) hostSession.send(new Level3EndingMessage());
    }

    private void updateAsDebug(float delta) {
        boolean actionEnabled = actionInputAllowed();
        boolean p1Free = movementAllowed(1);
        boolean p2Free = movementAllowed(2);
        if (p1Free) player1.update(delta);
        if (p2Free) player2.update(delta);
        attack(1, player1, actionEnabled && p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT));
        attack(2, player2, actionEnabled && p2Free && Gdx.input.isButtonPressed(Input.Buttons.RIGHT));
    }

    private void updateAsHost(float delta) {
        boolean actionEnabled = actionInputAllowed();
        boolean p1Free = movementAllowed(1);
        if (p1Free) player1.update(delta);

        PlayerInput p2Input = server.isClientConnected()
            ? server.pollClientInput()
            : new PlayerInput(
                movementAllowed(2) && Gdx.input.isKeyPressed(Input.Keys.UP),
                movementAllowed(2) && Gdx.input.isKeyPressed(Input.Keys.DOWN),
                movementAllowed(2) && Gdx.input.isKeyPressed(Input.Keys.LEFT),
                movementAllowed(2) && Gdx.input.isKeyPressed(Input.Keys.RIGHT),
                actionEnabled && Gdx.input.isButtonPressed(Input.Buttons.RIGHT));
        if (movementAllowed(2)) player2.applyInput(p2Input, delta);

        attack(1, player1, actionEnabled && p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT));
        attack(2, player2, actionEnabled && movementAllowed(2) && p2Input.attack);
        server.pushState(new WorldState(player1, player2));
    }

    private void updateAsClient() {
        boolean free = movementAllowed(2);
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

    private boolean movementAllowed(int side) {
        return !missionFailed && !endingStarted && !inventories.isOpen(side)
            && !storyBanner.isOpen() && !logPopup.isOpen()
            && (!bossStarted || inputState == InputState.PLAYER_FREE_CONTROL);
    }

    private boolean actionInputAllowed() {
        return !bossStarted || inputState == InputState.PLAYER_FREE_CONTROL;
    }

    private void handleTurnInput() {
        if (inputState != InputState.TURN_SELECTION
            || phase() != TurnManager.Phase.PLAYER_TURN) return;
        if (soloControl()) {
            handleUnifiedSelection();
        } else if (isHost) {
            handleSelection(1, Input.Keys.W, Input.Keys.S, Input.Keys.E);
        } else {
            handleSelection(2, Input.Keys.W, Input.Keys.S, Input.Keys.E);
        }
    }

    private void syncInputState(float delta) {
        TurnManager.Phase current = phase();
        if (current != observedPhase) {
            TurnManager.Phase previous = observedPhase;
            observedPhase = current;
            switch (current) {
                case RESOLUTION:
                    inputState = InputState.ACTION_EXECUTION;
                    break;
                case WARDEN_TURN:
                    inputState = InputState.WARDEN_TURN;
                    break;
                case PLAYER_TURN:
                    activeMenuSide = breakerSide();
                    if (previous == TurnManager.Phase.WARDEN_TURN) {
                        inputState = InputState.PLAYER_FREE_CONTROL;
                        freeControlTimer = FREE_CONTROL_DURATION;
                    } else {
                        inputState = InputState.TURN_SELECTION;
                    }
                    break;
                default:
                    break;
            }
        }
        if (current == TurnManager.Phase.PLAYER_TURN
            && inputState == InputState.PLAYER_FREE_CONTROL) {
            freeControlTimer -= delta;
            if (freeControlTimer <= 0f) inputState = InputState.TURN_SELECTION;
        }
    }

    private boolean soloControl() {
        return isDebug || (isHost && !server.isClientConnected());
    }

    private void handleUnifiedSelection() {
        int other = activeMenuSide == 1 ? 2 : 1;
        boolean switchPressed = Gdx.input.isKeyJustPressed(Input.Keys.TAB)
            || Gdx.input.isKeyJustPressed(Input.Keys.LEFT)
            || Gdx.input.isKeyJustPressed(Input.Keys.RIGHT);
        if ((switchPressed || confirmed(activeMenuSide)) && !confirmed(other)) {
            activeMenuSide = other;
        }
        if (confirmed(activeMenuSide)) return;

        PlayerActionType[] options = actionsFor(activeMenuSide);
        int count = options.length;
        int selected = activeMenuSide == 1 ? p1Selected : p2Selected;
        selected = Math.max(0, Math.min(count - 1, selected));
        if (Gdx.input.isKeyJustPressed(Input.Keys.W) || Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
            selected = (selected + count - 1) % count;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.S) || Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
            selected = (selected + 1) % count;
        }
        if (activeMenuSide == 1) p1Selected = selected;
        else p2Selected = selected;

        int mouseSide = Gdx.input.isButtonJustPressed(Input.Buttons.LEFT) ? breakerSide()
            : Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT) ? listenerSide() : 0;
        int confirmedSide = mouseSide != 0 ? mouseSide : activeMenuSide;
        boolean confirm = mouseSide != 0 || Gdx.input.isKeyJustPressed(Input.Keys.E)
            || Gdx.input.isKeyJustPressed(Input.Keys.ENTER);
        if (!confirm || confirmed(confirmedSide)) return;
        PlayerActionType[] confirmedOptions = actionsFor(confirmedSide);
        int confirmedSelected = confirmedSide == 1 ? p1Selected : p2Selected;
        confirmedSelected = Math.max(0, Math.min(confirmedOptions.length - 1, confirmedSelected));
        controller.confirmLocal(confirmedSide, confirmedOptions[confirmedSelected]);
        int nextSide = confirmedSide == 1 ? 2 : 1;
        if (!confirmed(nextSide)) activeMenuSide = nextSide;
    }

    private void handleSelection(int side, int upKey, int downKey, int confirmKey) {
        if (confirmed(side)) return;
        Role role = side == 1 ? sideOneRole : sideOneRole.other();
        PlayerActionType[] options = actionsFor(side);
        int count = options.length;
        int selected = side == 1 ? p1Selected : p2Selected;
        selected = Math.max(0, Math.min(count - 1, selected));
        if (Gdx.input.isKeyJustPressed(upKey)) selected = (selected + count - 1) % count;
        if (Gdx.input.isKeyJustPressed(downKey)) selected = (selected + 1) % count;
        if (side == 1) p1Selected = selected;
        else p2Selected = selected;

        boolean confirm = Gdx.input.isKeyJustPressed(confirmKey)
            || (!isDebug && Gdx.input.isKeyJustPressed(Input.Keys.ENTER))
            || Gdx.input.isButtonJustPressed(role == Role.BREAKER ? Input.Buttons.LEFT : Input.Buttons.RIGHT);
        if (!confirm) return;
        PlayerActionType action = options[selected];
        if (controller != null) controller.confirmLocal(side, action);
        else clientSession.send(new Level3ActionMessage(action.ordinal()));
    }

    private boolean confirmed(int side) {
        if (controller != null) {
            return side == 1 ? controller.getTurnManager().p1Confirmed() : controller.getTurnManager().p2Confirmed();
        }
        return side == 1 ? remoteP1Action != null : remoteP2Action != null;
    }

    private PlayerActionType[] actionsFor(int side) {
        Role role = side == 1 ? sideOneRole : sideOneRole.other();
        return Level3Controller.availableActions(inventories, gun, side, role);
    }

    private static int indexOf(PlayerActionType[] actions, PlayerActionType action) {
        for (int i = 0; i < actions.length; i++) if (actions[i] == action) return i;
        return 0;
    }

    private void attack(int side, Player player, boolean pressed) {
        if (!pressed || !player.startAttack()) return;
        brawlSwarm.attackNearest(side, player.centreX(), player.centreY(), ATTACK_RANGE, ATTACK_DAMAGE);
        hackerSwarm.attackNearest(side, player.centreX(), player.centreY(), ATTACK_RANGE, ATTACK_DAMAGE);
    }

    private void updateSwarms(float delta) {
        if (bossStarted || (!isHost && !isDebug)) return;
        if (!missionFailed) {
            brawlSwarm.update(delta, player1, player2, world);
            hackerSwarm.update(delta, player1, player2, world);
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

    private void checkForDeath() {
        if (missionFailed) return;
        boolean p1Down = player1.health <= 0f;
        boolean p2Down = player2.health <= 0f;
        if (!p1Down && !p2Down) return;
        boolean breakerDown = sideOneRole == Role.BREAKER ? p1Down : p2Down;
        boolean listenerDown = sideOneRole == Role.LISTENER ? p1Down : p2Down;
        missionFailed = true;
        failureCause = StoryGate.fallen(breakerDown, listenerDown);
        failureScenePending = true;
        failureSceneTimer = FAILURE_SCENE_DELAY;
        storyBanner.close();
        logPopup.close();
    }

    private void showFailureWhenReady(float delta) {
        if (!failureScenePending) return;
        failureSceneTimer -= delta;
        if (failureSceneTimer > 0f) return;
        failureScenePending = false;
        story.showFailure("MAIN SCENARIO #3 — FAILED",
            "It was never told to hate them. But it was told to protect, and it has not stopped.",
            new String[][]{
                {"Cause", failureCause},
                {"Retry", controller != null ? "Press ENTER to restart the scenario."
                    : "The host restarts the scenario."}
            }, controller != null ? this::restartLevel : null);
    }

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
        if (controller != null) controller.reset();
        else {
            wardenVisual.reset();
            droneVisual.reset();
            turretVisual.reset();
        }
        remotePhase = TurnManager.Phase.PLAYER_TURN;
        remoteWardenState = WardenState.DEFENSE_ACTIVE;
        remoteStability = 0f;
        remoteDirectiveConflict = 100f;
        remoteDualMeter = 0f;
        remoteP1Action = null;
        remoteP2Action = null;
        remoteWardenLine = "";
        remoteBreakerLine = "";
        remoteListenerLine = "";
        p1Selected = 0;
        p2Selected = 0;
        activeMenuSide = breakerSide();
        inputState = InputState.PLAYER_FREE_CONTROL;
        freeControlTimer = 0f;
        observedPhase = TurnManager.Phase.PLAYER_TURN;
        shownBannerSeq = 0;
        missionFailed = false;
        bossStarted = false;
        endingStarted = false;
        failureScenePending = false;
        failureCause = "";
        storyBanner.close();
        logPopup.close();
        resetPlayer(player1, true);
        resetPlayer(player2, false);
        story.closeFailure();
    }

    private void resetPlayer(Player player, boolean leftSpawn) {
        player.health = Player.MAX_HEALTH;
        player.resetVisualState();
        world.placeAtSpawn(player, leftSpawn);
    }

    @Override
    public void drawHalf(OrthographicCamera camera) {
        world.render(batch, camera);
        world.renderOverlays(shape, camera, bossStarted);
        if (gun.hasShotToDraw()) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shape.setProjectionMatrix(camera.combined);
            shape.begin(ShapeRenderer.ShapeType.Filled);
            gun.drawShot(shape);
            shape.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
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

        float[] core = world.getCoreAnchor();
        batch.draw(coreTexture, core[0] - CORE_DRAW_SIZE / 2f, core[1], CORE_DRAW_SIZE, CORE_DRAW_SIZE);
        wardenVisual.draw(batch);
        droneVisual.draw(batch);
        turretVisual.draw(batch);
        player1.draw(batch);
        player2.draw(batch);
        world.renderOverhang(batch);
        batch.end();
    }

    private void drawUI() {
        OrthographicCamera uiCamera = ui.camera();
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);
        hud.drawBanner(shape, batch, ui, TITLE, objective(), null, -1f);
        hud.drawPlayerCards(shape, batch, ui, player1, player2, sideOneRole);
        gun.drawHud(shape, batch, hud.font(), ui.width());

        if (bossStarted && inputState == InputState.TURN_SELECTION) {
            turnPanel.render(shape, batch, ui, phase(), wardenState(), stability(), directiveConflict(), dualMeter(),
                sideOneRole,
                sideOneRole.callSign(), sideOneRole.other().callSign(), p1Selected, p2Selected,
                confirmed(1), confirmed(2), actionsFor(1), actionsFor(2), activeMenuSide, soloControl(),
                wardenLine(), breakerLine(), listenerLine());
        }
        inventories.render(shape, batch, ui.width(), ui.height(), player1, player2,
            SplitScreen.ACCENT_P1, SplitScreen.ACCENT_P2);
        storyBanner.render(shape, batch, ui.width(), ui.height());
        logPopup.render(shape, batch, ui.width(), ui.height());
    }

    private TurnManager.Phase phase() {
        return controller != null ? controller.getTurnManager().getPhase() : remotePhase;
    }

    private WardenState wardenState() {
        return controller != null ? controller.getWarden().getState() : remoteWardenState;
    }

    private float stability() {
        return controller != null ? controller.getWarden().getStability() : remoteStability;
    }

    private float dualMeter() {
        return controller != null ? controller.getWarden().getDualMeter() : remoteDualMeter;
    }

    private float directiveConflict() {
        return controller != null ? controller.getWarden().getDirectiveConflict() : remoteDirectiveConflict;
    }

    private String wardenLine() { return controller != null ? controller.getWardenLine() : remoteWardenLine; }
    private String breakerLine() { return controller != null ? controller.getBreakerLine() : remoteBreakerLine; }
    private String listenerLine() { return controller != null ? controller.getListenerLine() : remoteListenerLine; }

    private String objective() {
        if (!bossStarted) return "Reach the marked authorization strip together.";
        switch (wardenState()) {
            case DEFENSE_ACTIVE:
                return "Stabilize the core. Restore authorization. Work together.";
            case MEMORY_RECOVERY:
                return "Recover the safety record. The Warden was following protocol.";
            case DIRECTIVE_CONFLICT:
                return "Resolve: Protect Core + Prevent Operators.";
            case DUAL_AUTHORIZATION:
                return "Dual authorization restored. Defensive response suspended.";
            case STAND_DOWN:
                return "The Warden stands down. The core is stable.";
            default:
                return "Restore authorization.";
        }
    }

    @Override public void show() {}

    @Override
    public void resize(int w, int h) {
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
        coreTexture.dispose();
        hud.dispose();
        if (controller != null) controller.dispose();
        else {
            wardenVisual.dispose();
            droneVisual.dispose();
            turretVisual.dispose();
        }
        if (carriedLootAssets != null) carriedLootAssets.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}
