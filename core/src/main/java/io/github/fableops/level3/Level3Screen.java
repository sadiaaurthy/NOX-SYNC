package io.github.fableops.level3;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.EnemySprites;
import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.SwarmController;
import io.github.fableops.inventory.Inventory;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.inventory.network.InventoryTransferMessage;
import io.github.fableops.level2.Sidearms;
import io.github.fableops.level2.loot.LootField;
import io.github.fableops.level2.network.GunStateMessage;
import io.github.fableops.level3.network.Level3ActionMessage;
import io.github.fableops.level3.network.Level3EndingMessage;
import io.github.fableops.level3.network.Level3EnemyStateMessage;
import io.github.fableops.level3.network.Level3TntMessage;
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
import io.github.fableops.ui.hud.EquipmentSelectionPanel;
import io.github.fableops.ui.hud.Hud;
import io.github.fableops.ui.hud.ReactionPanel;
import io.github.fableops.ui.hud.StoryBanner;
import io.github.fableops.ui.hud.TurnPanel;
import io.github.fableops.ui.hud.TurnPromptRenderer;

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
    private static final float CORE_DRAW_SIZE = 95f;
    private static final float RECOVER_BOLT_TRAVEL = 0.6f;
    private static final float RECOVER_BOLT_BURST = 0.3f;

    private enum InputState {
        NORMAL_GAMEPLAY,
        TURN_MENU_OPEN,
        INVENTORY_PREVIEW,
        ITEM_READY,
        ACTION_EXECUTION
    }

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shape = new ShapeRenderer();
    private final UiViewport ui = new UiViewport();
    private final PlayerInventories inventories;
    private final Level3Map world = new Level3Map();
    private final Hud hud;
    private final Sidearms sidearms;
    // Owns the icon textures referenced by carried Level 2 InventoryItems.
    private final LootField carriedLootAssets;
    private final InventoryItem[] itemDefinitions;
    private final Texture[] ownedItemDefinitionTextures;
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
    private final EquipmentSelectionPanel equipmentPanel;
    private final TntExplosion tntExplosion = new TntExplosion();
    private final ReactionPanel reactionPanelP1;
    private final ReactionPanel reactionPanelP2;
    private final StoryBanner storyBanner;
    private final CodePopupUI logPopup;

    private boolean debugCollisionVisible;
    // Set when a menu closes while LMB/RMB is still held, so that click cannot become an attack.
    private boolean combatLatched;
    // The open menu / item popup was started to answer a reaction (a turret alert or a drone hit),
    // not to queue a turn action. If that reaction ends underneath it, the menu closes itself.
    private boolean menuIsReaction;
    private boolean selectedForReaction;
    // The prepared TNT already answered the turret alert that is open right now
    private boolean preparedTntAnswered;
    private float recoverBoltTimer;
    private float recoverFromX;
    private float recoverFromY;
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
    private boolean equipmentOpen;
    private int equipmentSide;
    private int equipmentSelectedSlot = -1;
    private PlayerActionType equipmentAction;
    private int p1InventorySelected;
    private int p2InventorySelected;
    private boolean p1InventoryFocus;
    private boolean p2InventoryFocus;
    private int selectedItemSide;
    private int selectedItemCategory = -1;
    private int selectedItemQuantity;
    private InventoryItem selectedItem;
    private String lastPromptLogSignature = "";
    private int shownBannerSeq;
    private InputState inputState = InputState.NORMAL_GAMEPLAY;
    private TurnManager.Phase observedPhase = TurnManager.Phase.PLAYER_TURN;
    private TurnManager.Phase remotePhase = TurnManager.Phase.PLAYER_TURN;
    private WardenState remoteWardenState = WardenState.DEFENSE_ACTIVE;
    private float remoteStability;
    private float remoteDirectiveConflict = 100f;
    private float remoteDualMeter;
    private boolean remoteMemoryRecovered;
    private boolean remoteReactionOpen;
    private Level3Controller.EnemyAttackType remoteReactionAttackType;
    private int remoteReactionTargetSide;
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
            new Hud(), new PlayerInventories(), new Sidearms(), null);
    }

    public Level3Screen(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession,
                        StoryGate story, Role sideOneRole, Player player1, Player player2,
                        Hud hud, PlayerInventories inventories, Sidearms sidearms, LootField carriedLootAssets) {
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
        this.sidearms = sidearms;
        this.carriedLootAssets = carriedLootAssets;
        if (carriedLootAssets != null) {
            this.ownedItemDefinitionTextures = null;
            // One entry per TurnPanel.InventoryCategory, in ordinal order - it is indexed by
            // category.ordinal(). Adding a category without adding its row here is an out-of-bounds
            this.itemDefinitions = new InventoryItem[]{
                carriedLootAssets.itemDefinition("Sidearm"),
                carriedLootAssets.itemDefinition("Shield Cell"),
                carriedLootAssets.itemDefinition("Medkit"),
                carriedLootAssets.itemDefinition("TNT")
            };
        } else {
            Texture sidearmIcon = new Texture(Gdx.files.internal("LootWeapon.png"));
            Texture shieldIcon = new Texture(Gdx.files.internal("LootShieldCell.png"));
            Texture medkitIcon = new Texture(Gdx.files.internal("LootMedkit.png"));
            Texture tntIcon = new Texture(Gdx.files.internal("LootTNT.png"));
            this.ownedItemDefinitionTextures =
                new Texture[]{sidearmIcon, shieldIcon, medkitIcon, tntIcon};
            // Same contract as above: one entry per category, in ordinal order
            this.itemDefinitions = new InventoryItem[]{
                new InventoryItem("Sidearm", "Fires where you face.", sidearmIcon, 0f, true),
                new InventoryItem("Shield Cell", "A temporary shield charge.", shieldIcon, 0f, true),
                new InventoryItem("Medkit", "Patches you up.", medkitIcon, 35f, true),
                new InventoryItem("TNT", "Takes down one turret.", tntIcon, 0f, true)
            };
        }
        // itemDefinitions is indexed by InventoryCategory.ordinal(). Catch a missing row the moment
        // Level 3 loads instead of when a player happens to scroll onto that category mid-fight
        if (itemDefinitions.length != TurnPanel.InventoryCategory.values().length) {
            throw new IllegalStateException("itemDefinitions has " + itemDefinitions.length
                + " entries but there are " + TurnPanel.InventoryCategory.values().length
                + " inventory categories. Add one entry per category, in ordinal order.");
        }
        this.activeMenuSide = 1;
        this.turnPanel = new TurnPanel(hud.font());
        this.equipmentPanel = new EquipmentSelectionPanel(hud.font());
        this.reactionPanelP1 = new ReactionPanel(hud.font());
        this.reactionPanelP2 = new ReactionPanel(hud.font());
        this.storyBanner = new StoryBanner(hud.font());
        this.logPopup = new CodePopupUI(hud.font());

        player1.setWorld(world, 1);
        player2.setWorld(world, 2);
        player2.setAlternateRightKey(Input.Keys.L);
        SplitScreen.fitCameras(player1, player2, CAM_HEIGHT);
        world.placeAtSpawn(player1, true);
        world.placeAtSpawn(player2, false);

        if (isHost || isDebug) {
            controller = new Level3Controller(hostSession, world, inventories, sidearms, player1, player2, sideOneRole);
            wardenVisual = controller.getWarden();
            droneVisual = controller.getDrone();
            turretVisual = controller.getTurret();
            inventories.setTransferHandler((side, fromShared, slot) -> controller.transferInventory(
                new InventoryTransferMessage(side, fromShared, slot)));
            if (hostSession != null) hostSession.setListener(story.wrap(controller.asMessageListener()));
        } else {
            controller = null;
            float[] wardenAnchor = world.getWardenAnchor();
            float[] droneAnchor = world.getDroneAnchor();
            wardenVisual = new WardenController(wardenAnchor[0], wardenAnchor[1]);
            droneVisual = new DefenseDroneController(droneAnchor[0], droneAnchor[1]);
            turretVisual = new SecurityTurretController(world.getTurretGroundPoints());
            inventories.setTransferHandler((side, fromShared, slot) -> clientSession.send(
                new InventoryTransferMessage(side, fromShared, slot)));
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
                sidearms.apply(GunStateMessage.deserialize(body));
                break;
            case "INVENTORY_TRANSFER": {
                InventoryTransferMessage transfer = InventoryTransferMessage.deserialize(body);
                inventories.applyTransfer(transfer.getPlayerSide(), transfer.isFromShared(),
                    transfer.getPersonalSlot());
                break;
            }
            case "LEVEL3_ENDING":
                beginEnding();
                break;
            case "LEVEL3_TNT": {
                Level3TntMessage blast = Level3TntMessage.deserialize(body);
                tntExplosion.spawn(blast.getX(), blast.getY());
                break;
            }
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
        remoteMemoryRecovered = state.isMemoryRecovered();
        remoteReactionOpen = state.isReactionOpen();
        remoteReactionAttackType = enemyAttackAt(state.getReactionAttackOrdinal());
        remoteReactionTargetSide = state.getReactionTargetSide();
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
        if (state.isDroneAttacked()) {
            if (state.getReactionUnitIndex() >= 0) {
                int unit = state.getReactionUnitIndex();
                droneVisual.playAttackFlash(unit, nearestPlayerX(droneVisual, unit));
            } else {
                droneVisual.playAttackFlash();
            }
        }
        // Alert and waiting are derived from positions on both peers. The host announces only the
        // moment the targeted player is prepared; the mirrored unit then readies, aims and fires
        // on the same fixed timers as the host's.
        if (state.isTurretAiming() && state.getTurretTargetSide() != 0) {
            turretVisual.beginCombat(state.getReactionUnitIndex(), state.getTurretTargetSide());
        }
        if (state.getDroneDamagedIndex() >= 0) {
            droneVisual.playDamaged(state.getDroneDamagedIndex(),
                state.getDroneDamagedIndex() >= state.getDroneCount());
        }
        if (state.getTurretDamagedIndex() >= 0) {
            turretVisual.playDamaged(state.getTurretDamagedIndex(), state.isTurretDestroyed());
        }
        ReactionType selectedReaction = reactionAt(state.getReactionSelectionOrdinal());
        Player reactionPlayer = state.getReactionTargetSide() == 1 ? player1
            : state.getReactionTargetSide() == 2 ? player2 : null;
        if (selectedReaction != null && reactionPlayer != null) {
            if (selectedReaction == ReactionType.SIDEARM) reactionPlayer.startShooting();
            else if (selectedReaction == ReactionType.SHIELD) reactionPlayer.startShielding();
            else if (selectedReaction == ReactionType.MEDKIT) reactionPlayer.playHealingFeedback();
        }
        if (state.getDamageAppliedTargetSide() == 1) player1.playDamagedFeedback();
        else if (state.getDamageAppliedTargetSide() == 2) player2.playDamagedFeedback();
        if (state.isRestorationStarted()) {
            droneVisual.startRestoration();
            turretVisual.startRestoration();
        }
        if (state.isRestorationCompleted()) {
            droneVisual.completeRestoration();
            turretVisual.completeRestoration();
        }
        boolean calm = remoteWardenState.isStoodDown();
        if (calm) storyBanner.close();

        player1.health = state.getHealthP1();
        player2.health = state.getHealthP2();
        removeConsumedItem(1, state.getP1ConsumedSlot());
        removeConsumedItem(2, state.getP2ConsumedSlot());
        if (state.getBannerSeq() > shownBannerSeq) {
            shownBannerSeq = state.getBannerSeq();
            showProgressBeat(state.getBannerId());
        }
        checkForDeath();
        syncInputState(0f);
    }

    private static PlayerActionType actionAt(int ordinal) {
        PlayerActionType[] values = PlayerActionType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private static ReactionType reactionAt(int ordinal) {
        ReactionType[] values = ReactionType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private static Level3Controller.EnemyAttackType enemyAttackAt(int ordinal) {
        Level3Controller.EnemyAttackType[] values = Level3Controller.EnemyAttackType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private void removeConsumedItem(int side, int slot) {
        if (slot < 0) return;
        if (slot == Level3Controller.SHARED_SLOT_INDEX) inventories.removeSharedItem();
        else inventories.forPlayer(side).remove(slot);
    }

    // X of whichever operator is closer to this drone, so its attack beam leaves the right side
    private float nearestPlayerX(DefenseDroneController drones, int unit) {
        float d1 = drones.distanceSquaredToUnit(unit, player1.centreX(), player1.centreY());
        float d2 = drones.distanceSquaredToUnit(unit, player2.centreX(), player2.centreY());
        return d1 <= d2 ? player1.centreX() : player2.centreX();
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
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            Gdx.app.log("InputTrace", "key=ENTER actionTriggered=false");
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) && !equipmentOpen
            && inputState != InputState.TURN_MENU_OPEN
            && inputState != InputState.INVENTORY_PREVIEW
            && inputState != InputState.ITEM_READY
            && !inventories.anyOpen() && !overlayWasOpen) {
            Gdx.app.exit();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F1)) debugCollisionVisible = !debugCollisionVisible;
        if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) ui.cycleScale();
        if (combatLatched && !Gdx.input.isButtonPressed(Input.Buttons.LEFT)
            && !Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            combatLatched = false;
        }
        recoverBoltTimer = Math.max(0f, recoverBoltTimer - delta);
        tntExplosion.update(delta);

        boolean inputBlocked = missionFailed || bossStarted || storyBanner.isOpen() || logPopup.isOpen();
        inventories.handleInput(isHost || isDebug, !isHost || isDebug, inputBlocked, player1, player2);
        player1.updateVisualState(delta);
        player2.updateVisualState(delta);
        sidearms.update(delta);
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
                turretVisual.setTrackedPlayers(player1.centreX(), player1.centreY(),
                    player2.centreX(), player2.centreY());
                turretVisual.update(delta);
            }
            // Input priority: 1) Turn Menu (I, TAB, ENTER, ESC only), 2) inventory item popup
            // (ENTER/ESC only), 3) free gameplay (T, movement and LMB/RMB combat in updateAs*).
            if (inputState == InputState.NORMAL_GAMEPLAY
                && !overlayWasOpen && !storyBanner.isOpen() && !logPopup.isOpen()) {
                handleTurnMenuRequest();
                handleTntRequest();
                if (inputState == InputState.NORMAL_GAMEPLAY && reactionOpen()) handleReactionInput();
            } else if (inputState == InputState.TURN_MENU_OPEN
                && !overlayWasOpen && !storyBanner.isOpen() && !logPopup.isOpen()) {
                handleTurnInput();
            } else if (inputState == InputState.INVENTORY_PREVIEW
                && !overlayWasOpen && !storyBanner.isOpen() && !logPopup.isOpen()) {
                handleInventoryPreviewInput();
            } else if (inputState == InputState.ITEM_READY
                && !overlayWasOpen && !storyBanner.isOpen() && !logPopup.isOpen()) {
                handleReadyItemInput();
            }
            if (controller != null) {
                controller.setTurretAiPaused(storyBanner.isOpen() || logPopup.isOpen());
                controller.update(delta);
                for (float[] blast = controller.pollTntBlast(); blast != null;
                     blast = controller.pollTntBlast()) {
                    tntExplosion.spawn(blast[0], blast[1]);
                }
                syncInputState(0f);
                consumeControllerBeat();
                if (wardenState().isStoodDown()) storyBanner.close();
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
        inventories.closeAll();
        closeEquipmentSelection();
        activeMenuSide = 1;
        setInputState(InputState.NORMAL_GAMEPLAY, "encounter started");
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

    private Role roleForSide(int side) { return side == 1 ? sideOneRole : sideOneRole.other(); }

    private void beginEnding() {
        if (endingStarted) return;
        endingStarted = true;
        story.begin(StoryBeat.ENDING);
        if (hostSession != null) hostSession.send(new Level3EndingMessage());
    }

    private void updateAsDebug(float delta) {
        boolean actionEnabled = combatInputEnabled();
        boolean p1Free = movementAllowed(1);
        boolean p2Free = movementAllowed(2);
        if (p1Free) player1.update(delta);
        if (p2Free) player2.update(delta);
        startPlayerAttack(1, player1,
            actionEnabled && p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT));
        startPlayerAttack(2, player2,
            actionEnabled && p2Free && Gdx.input.isButtonPressed(Input.Buttons.RIGHT));
    }

    private void updateAsHost(float delta) {
        boolean actionEnabled = combatInputEnabled();
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

        startPlayerAttack(1, player1,
            actionEnabled && p1Free && Gdx.input.isButtonPressed(Input.Buttons.LEFT));
        startPlayerAttack(2, player2, actionEnabled && movementAllowed(2) && p2Input.attack);
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
                combatInputEnabled() && Gdx.input.isButtonPressed(Input.Buttons.RIGHT))
            : new PlayerInput(false, false, false, false));
        client.pollState().applyTo(player1, player2);
    }

    private boolean movementAllowed(int side) {
        return !missionFailed && !endingStarted && !inventories.isOpen(side)
            && !storyBanner.isOpen() && !logPopup.isOpen()
            && !movementBlockedByLevel3Ui();
    }

    private boolean actionInputAllowed() {
        return !bossStarted || inputState == InputState.NORMAL_GAMEPLAY;
    }

    // Combat is LMB/RMB only, and only in free gameplay with no menu click still held.
    private boolean combatInputEnabled() {
        return actionInputAllowed() && !combatLatched;
    }

    private boolean movementBlockedByLevel3Ui() {
        return inputState == InputState.TURN_MENU_OPEN
            || inputState == InputState.INVENTORY_PREVIEW;
    }

    private void setInputState(InputState next, String reason) {
        inputState = next;
        traceInputState(reason);
    }

    private void traceInputState(String reason) {
        Gdx.app.log("Level3InputTrace", reason + " state=" + inputState
            + " movementEnabled=" + !movementBlockedByLevel3Ui());
    }

    // MenuInputHandler: every method from here to handleReadyItemInput reads keyboard keys only.
    private void handleTurnInput() {
        if (inputState != InputState.TURN_MENU_OPEN) return;
        if (menuIsReaction && !reactionOpen()) {
            // The turret alert ended (the player walked out of range, or it was destroyed).
            restoreGameplayInput("reaction ended");
            return;
        }
        if (!menuIsReaction && reactionOpen() && localCanRespondToReaction()) {
            // An alert opened under a normal menu: restart on the reacting player's equipment.
            openTurnMenu(reactionTargetSide(), true, "reaction opened under turn menu");
        }
        if (!menuIsReaction && phase() != TurnManager.Phase.PLAYER_TURN) {
            // The round moved on under an open menu: never leave movement locked behind it.
            restoreGameplayInput("turn menu no longer valid");
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            traceMenuInput("ESC");
            closeTurnMenu();
            return;
        }
        if (menuIsReaction) {
            handleReactionMenuInput();
            return;
        }
        if (equipmentOpen) {
            handleEquipmentInput();
            return;
        }
        if (soloControl()) {
            handleUnifiedSelection();
        } else {
            handleSelection(isHost ? 1 : 2);
        }
    }

    private void handleTurnMenuRequest() {
        if (!Gdx.input.isKeyJustPressed(Input.Keys.T) || !turnMenuAvailable()) return;
        if (reactionOpen()) openTurnMenu(reactionTargetSide(), true, "reaction turn menu opened");
        else openTurnMenu(firstMenuSide(), false, "turn menu opened");
    }

    // Y throws a carried TNT charge straight from free gameplay. It is the same turn action as
    // Inventory > TNT > Y, just without the menu. During a turret alert the throw also answers it:
    // the operator is prepared first (which is what releases the turret), then the charge goes.
    private void handleTntRequest() {
        if (!Gdx.input.isKeyJustPressed(Input.Keys.Y)) return;
        int side = tntThrowerSide();
        if (side == 0) return;
        int slot = Level3Controller.firstCompatibleSlot(inventories, side, PlayerActionType.USE_TNT);
        if (slot < 0) return;
        Gdx.app.log("InputTrace", "key=Y actionTriggered=true side=" + side);
        if (reactionOpen()) confirmReaction(ReactionType.TNT);
        confirmAction(side, PlayerActionType.USE_TNT, slot);
    }

    // The HUD line. It is an instruction like "PRESS T TO OPEN TURN MENU", not an interaction prompt:
    // it is up whenever an active operator carries TNT, and nothing about the enemies (distance to a
    // turret, a turret or drone being alive, an alert being open, whose phase it is) can hide it.
    private boolean tntPromptVisible() {
        if (!bossStarted || missionFailed || endingStarted) return false;
        return (player1.health > 0f && Level3Controller.hasTnt(inventories, 1))
            || (player2.health > 0f && Level3Controller.hasTnt(inventories, 2));
    }

    // The operator a Y press would throw for, or 0 when Y has nothing to do right now: the turn must
    // be open to that operator (or a turret alert must be waiting on them), they must carry TNT, and
    // there must be a turret left to hit. This is only the key; the HUD line does not depend on it.
    private int tntThrowerSide() {
        if (!bossStarted || missionFailed || endingStarted
            || inputState != InputState.NORMAL_GAMEPLAY
            || storyBanner.isOpen() || logPopup.isOpen()
            || turretVisual.getActiveCount() == 0) return 0;
        if (reactionOpen()) {
            if (reactionAttackType() != Level3Controller.EnemyAttackType.TURRET
                || !localCanRespondToReaction()) return 0;
            int side = reactionTargetSide();
            return Level3Controller.hasTnt(inventories, side) ? side : 0;
        }
        if (phase() != TurnManager.Phase.PLAYER_TURN) return 0;
        if (!soloControl()) {
            int side = isHost ? 1 : 2;
            return !confirmed(side) && Level3Controller.hasTnt(inventories, side) ? side : 0;
        }
        for (int side = 1; side <= 2; side++) {
            if (!confirmed(side) && Level3Controller.hasTnt(inventories, side)) return side;
        }
        return 0;
    }

    // Single source of truth for "T would open the Turn Menu right now". It gates the T key and the
    // cyan reminder alike, so the reminder is never on screen when T would do nothing.
    private boolean turnMenuAvailable() {
        if (!bossStarted || missionFailed || endingStarted
            || inputState != InputState.NORMAL_GAMEPLAY
            || storyBanner.isOpen() || logPopup.isOpen()) return false;
        // A reaction menu can only offer carried equipment. With none to offer, opening it would
        // strand the operator on a column of x0 rows with no visible way out, so T stays shut and
        // the ENTER "take the hit" fallback in handleReactionInput stays reachable instead
        if (reactionOpen()) return localCanRespondToReaction() && localReactionHasEquipment();
        if (phase() != TurnManager.Phase.PLAYER_TURN) return false;
        return soloControl() ? !(confirmed(1) && confirmed(2)) : !confirmed(isHost ? 1 : 2);
    }

    // Player 1 unless this keyboard controls both operators and Player 1 has already confirmed.
    private int firstMenuSide() {
        if (!soloControl()) return isHost ? 1 : 2;
        // With Restore Authorization unlocked, open on the Listener's column: that is the row to pick.
        if (authorizationUnlocked() && !confirmed(listenerSide())) return listenerSide();
        return confirmed(1) && !confirmed(2) ? 2 : 1;
    }

    // Every open starts from the same cursor: ACTIONS, first row, never a remembered position (the
    // one exception is the Listener's unlocked Restore Authorization row, see below). A
    // reaction can only choose carried equipment, so that one opens on the inventory rows.
    private void openTurnMenu(int side, boolean reaction, String reason) {
        closeEquipmentSelection();
        clearSelectedItem();
        inventories.closeAll();
        p1Selected = 0;
        p2Selected = 0;
        if (!reaction && authorizationUnlocked()) {
            // Restore Authorization just unlocked: the Listener's cursor starts on it, highlighted.
            int row = indexOf(actionsFor(listenerSide()), PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT);
            if (listenerSide() == 1) p1Selected = row;
            else p2Selected = row;
        }
        p1InventorySelected = 0;
        p2InventorySelected = 0;
        p1InventoryFocus = reaction && side == 1;
        p2InventoryFocus = reaction && side == 2;
        activeMenuSide = side;
        menuIsReaction = reaction;
        setInputState(InputState.TURN_MENU_OPEN, reason);
        Gdx.app.log("Level3TurnMenuTrace", "Turn menu opened for player "
            + roleForSide(side).callSign());
        traceMenuInput("T");
    }

    private void closeTurnMenu() {
        restoreGameplayInput("turn menu closed");
    }

    // Forces free gameplay back after a menu, popup or action ends. Movement and combat are derived
    // from inputState (there is no separate lock flag to forget), so resetting it here is the
    // whole recovery; nothing relies on what the previous state happened to be.
    private void restoreGameplayInput(String reason) {
        closeEquipmentSelection();
        clearSelectedItem();
        inventories.closeAll();
        p1InventoryFocus = false;
        p2InventoryFocus = false;
        activeMenuSide = 0;
        menuIsReaction = false;
        combatLatched = Gdx.input.isButtonPressed(Input.Buttons.LEFT)
            || Gdx.input.isButtonPressed(Input.Buttons.RIGHT);
        setInputState(InputState.NORMAL_GAMEPLAY, reason);
    }

    private void traceMenuInput(String key) {
        int side = activeMenuSide;
        boolean inventory = side != 0 && inventoryFocused(side);
        int index = side == 1 ? (inventory ? p1InventorySelected : p1Selected)
            : side == 2 ? (inventory ? p2InventorySelected : p2Selected) : -1;
        Gdx.app.log("TurnMenuInputTrace", "key=" + key
            + " currentPlayer=" + (side == 0 ? "NONE" : "PLAYER_" + side)
            + " category=" + (inventory ? "INVENTORY" : "ACTIONS")
            + " index=" + index);
    }

    private void syncInputState(float delta) {
        TurnManager.Phase current = phase();
        if (reactionOpen()) {
            if (inputState == InputState.ACTION_EXECUTION) restoreGameplayInput("reaction available");
            return;
        }
        if (current != observedPhase) {
            observedPhase = current;
            switch (current) {
                case RESOLUTION:
                    closeEquipmentSelection();
                    setInputState(InputState.ACTION_EXECUTION, "operator actions executing");
                    break;
                case WARDEN_TURN:
                    restoreGameplayInput("warden turn gameplay restored");
                    break;
                case PLAYER_TURN:
                    restoreGameplayInput("new player turn");
                    break;
                default:
                    break;
            }
        }
    }

    // Whether the targeted operator carries anything a reaction could actually use. Only meaningful
    // while a reaction is open, since reactionAvailability reads reactionTargetSide
    private boolean localReactionHasEquipment() {
        boolean[] available = reactionAvailability();
        return available[ReactionType.SIDEARM.ordinal()]
            || available[ReactionType.SHIELD.ordinal()]
            || available[ReactionType.MEDKIT.ordinal()]
            || available[ReactionType.TNT.ordinal()];
    }

    // ENTER-only fallback for a reaction with no usable equipment: take the hit.
    private void handleReactionInput() {
        if (!reactionOpen() || !localCanRespondToReaction()) return;
        if (!localReactionHasEquipment() && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            traceMenuInput("ENTER");
            confirmReaction(ReactionType.NONE);
        }
    }

    private void handleReactionMenuInput() {
        int side = reactionTargetSide();
        boolean up = Gdx.input.isKeyJustPressed(Input.Keys.W)
            || Gdx.input.isKeyJustPressed(Input.Keys.UP);
        boolean down = Gdx.input.isKeyJustPressed(Input.Keys.S)
            || Gdx.input.isKeyJustPressed(Input.Keys.DOWN);
        moveInventorySelection(side, up ? -1 : down ? 1 : 0);
        if (up || down) traceMenuInput(up ? "UP" : "DOWN");
        if (!Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) return;

        traceMenuInput("ENTER");
        int selected = side == 1 ? p1InventorySelected : p2InventorySelected;
        selectInventoryCategory(side, selected);
    }

    private static ReactionType reactionForCategory(int category) {
        if (category == TurnPanel.InventoryCategory.SIDEARM.ordinal()) return ReactionType.SIDEARM;
        if (category == TurnPanel.InventoryCategory.SHIELD.ordinal()) return ReactionType.SHIELD;
        if (category == TurnPanel.InventoryCategory.MEDKIT.ordinal()) return ReactionType.MEDKIT;
        if (category == TurnPanel.InventoryCategory.TNT.ordinal()) return ReactionType.TNT;
        return null;
    }

    private void handleInventoryPreviewInput() {
        if (selectedForReaction && !reactionOpen()) {
            restoreGameplayInput("reaction ended");
            return;
        }
        if (selectedItemCategory < 0 || selectedItemSide == 0) {
            restoreGameplayInput("invalid inventory preview closed");
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            restoreGameplayInput("inventory preview cancelled");
            return;
        }
        if (!Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) return;

        boolean tnt = selectedItemCategory == TurnPanel.InventoryCategory.TNT.ordinal();
        // Against a drone TNT is no answer, so it counts as an unusable choice and takes the hit
        boolean tntUnusableHere = tnt && selectedForReaction
            && reactionAttackType() != Level3Controller.EnemyAttackType.TURRET;
        if (selectedItemQuantity > 0 && !tntUnusableHere) {
            if (tnt && selectedForReaction) {
                // TNT selected during a turret alert: the operator is now prepared, which releases
                // the turret (nothing waits on this menu). The charge stays until Y throws it, so
                // from here the ready state behaves exactly like TNT chosen on a normal turn.
                Gdx.app.log("Level3TntTrace", "TNT prepared against turret alert side=" + selectedItemSide);
                confirmReaction(ReactionType.TNT);
                selectedForReaction = false;
            }
            setInputState(InputState.ITEM_READY, "inventory preview confirmed");
            return;
        }

        boolean unavailableReaction = reactionOpen();
        restoreGameplayInput("unavailable inventory preview closed");
        if (unavailableReaction) confirmReaction(ReactionType.NONE);
    }

    private void handleReadyItemInput() {
        if (selectedForReaction && !reactionOpen()) {
            restoreGameplayInput("reaction ended");
            return;
        }
        if (selectedItemCategory < 0 || selectedItemSide == 0) {
            restoreGameplayInput("item readiness cleared");
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            restoreGameplayInput("ready item cancelled");
            return;
        }

        TurnPanel.InventoryCategory category =
            TurnPanel.InventoryCategory.values()[selectedItemCategory];
        answerTurretAlertWithPreparedTnt(category);
        boolean activate = category == TurnPanel.InventoryCategory.SIDEARM
            ? Gdx.input.isKeyJustPressed(Input.Keys.X)
            : category == TurnPanel.InventoryCategory.SHIELD
                ? Gdx.input.isKeyJustPressed(Input.Keys.H)
                : category == TurnPanel.InventoryCategory.TNT
                    ? Gdx.input.isKeyJustPressed(Input.Keys.Y)
                    : Gdx.input.isKeyJustPressed(Input.Keys.M);
        if (!activate) return;
        Gdx.app.log("InputTrace", "key=" + activationKey(category) + " actionTriggered=true");

        int side = selectedItemSide;
        // The sidearm is no longer a turn action of its own - Disable Drone spends its rounds. It
        // is still a valid answer to an incoming attack, so it only activates during a reaction
        if (category == TurnPanel.InventoryCategory.SIDEARM && !reactionOpen()) {
            Gdx.app.log("Level3TurnMenuTrace", "Sidearm is not a turn action; "
                + "Disable Drone fires it, and it answers incoming attacks");
            return;
        }
        int slot = firstPersonalCompatibleSlot(side, actionForCategory(side, category));
        if (slot < 0) return;
        if (reactionOpen() && category != TurnPanel.InventoryCategory.TNT) {
            // Only the targeted player's activation answers the alert.
            if (side != reactionTargetSide()) return;
            // TNT is a turn action, not a defensive response, so it has no reaction mapping
            ReactionType reaction = reactionForCategory(selectedItemCategory);
            if (reaction == null) return;
            restoreGameplayInput("reaction item activated");
            confirmReaction(reaction);
            return;
        }

        PlayerActionType action = actionForCategory(side, category);
        playReadyItemAnimation(side, category);
        clearSelectedItem();
        confirmAction(side, action, slot);
    }

    // A prepared TNT charge means the operator is ready, the same as choosing TNT from the reaction
    // menu: it answers a turret alert on them, so the turret carries on (aim, fire, damage) instead
    // of waiting for a response this state never sent. The charge stays in the pack until Y throws
    // it, and the operator moves freely meanwhile. Only the alert's own target answers it.
    private void answerTurretAlertWithPreparedTnt(TurnPanel.InventoryCategory category) {
        boolean answers = category == TurnPanel.InventoryCategory.TNT
            && !selectedForReaction && reactionOpen()
            && reactionAttackType() == Level3Controller.EnemyAttackType.TURRET
            && selectedItemSide == reactionTargetSide() && localCanRespondToReaction();
        if (!answers) {
            preparedTntAnswered = false;
            return;
        }
        // A client only hears the alert close on the host's next message; send the answer once
        if (preparedTntAnswered) return;
        preparedTntAnswered = true;
        Gdx.app.log("Level3TntTrace", "prepared TNT answers turret alert side=" + selectedItemSide);
        confirmReaction(ReactionType.TNT);
    }

    private void playReadyItemAnimation(int side, TurnPanel.InventoryCategory category) {
        Player player = side == 1 ? player1 : player2;
        switch (category) {
            case SIDEARM:
                player.startShooting();
                break;
            case SHIELD:
                player.startShielding();
                break;
            case MEDKIT:
                player.playHealingFeedback();
                break;
            default:
                break;
        }
    }

    private PlayerActionType actionForCategory(int side, TurnPanel.InventoryCategory category) {
        Role role = roleForSide(side);
        switch (category) {
            case SIDEARM:
                return role == Role.BREAKER ? PlayerActionType.BREAKER_WEAPON_ATTACK
                    : PlayerActionType.LISTENER_WEAPON_ATTACK;
            case SHIELD:
                return role == Role.BREAKER ? PlayerActionType.BREAKER_SHIELD_DEFENSE
                    : PlayerActionType.LISTENER_SHIELD_DEFENSE;
            case TNT:
                return PlayerActionType.USE_TNT;
            case MEDKIT:
                return PlayerActionType.USE_MEDKIT;
            default:
                return null;
        }
    }

    private void confirmReaction(ReactionType reaction) {
        int side = reactionTargetSide();
        int slot = reactionSlot(side, reaction);
        Gdx.app.log("Level3ReactionTrace", "reaction input side=" + side + " reaction=" + reaction
            + " slot=" + slot);
        if (controller != null) controller.confirmReaction(side, reaction, slot);
        else clientSession.send(new Level3ActionMessage(reaction.ordinal(), slot, true));
    }

    private int reactionSlot(int side, ReactionType reaction) {
        switch (reaction) {
            case SIDEARM:
                return firstPersonalCompatibleSlot(side,
                    roleForSide(side) == Role.BREAKER ? PlayerActionType.BREAKER_WEAPON_ATTACK
                        : PlayerActionType.LISTENER_WEAPON_ATTACK);
            case SHIELD:
                return firstPersonalCompatibleSlot(side,
                    roleForSide(side) == Role.BREAKER ? PlayerActionType.BREAKER_SHIELD_DEFENSE
                        : PlayerActionType.LISTENER_SHIELD_DEFENSE);
            case MEDKIT:
                return firstPersonalCompatibleSlot(side, PlayerActionType.USE_MEDKIT);
            case TNT:
                return firstPersonalCompatibleSlot(side, PlayerActionType.USE_TNT);
            default:
                return -1;
        }
    }

    private boolean[] reactionAvailability() {
        int side = reactionTargetSide();
        int[] quantities = inventoryQuantities(side);
        boolean[] available = new boolean[ReactionType.values().length];
        available[ReactionType.NONE.ordinal()] = true;
        available[ReactionType.SIDEARM.ordinal()] = sidearms.forSide(side).hasAmmo()
            && quantities[TurnPanel.InventoryCategory.SIDEARM.ordinal()] > 0;
        available[ReactionType.SHIELD.ordinal()] =
            quantities[TurnPanel.InventoryCategory.SHIELD.ordinal()] > 0;
        available[ReactionType.MEDKIT.ordinal()] =
            quantities[TurnPanel.InventoryCategory.MEDKIT.ordinal()] > 0;
        // TNT can only answer a turret; against a drone it is not an option
        available[ReactionType.TNT.ordinal()] =
            reactionAttackType() == Level3Controller.EnemyAttackType.TURRET
            && quantities[TurnPanel.InventoryCategory.TNT.ordinal()] > 0;
        return available;
    }

    private int firstPersonalCompatibleSlot(int side, PlayerActionType action) {
        Inventory inventory = inventories.forPlayer(side);
        for (int slot = 0; slot < Inventory.CAPACITY; slot++) {
            if (Level3Controller.itemSupportsAction(action, inventory.get(slot))) return slot;
        }
        return -1;
    }

    private boolean soloControl() {
        return isDebug || (isHost && !server.isClientConnected());
    }

    private void handleUnifiedSelection() {
        boolean switchPressed = Gdx.input.isKeyJustPressed(Input.Keys.TAB)
            || Gdx.input.isKeyJustPressed(Input.Keys.LEFT)
            || Gdx.input.isKeyJustPressed(Input.Keys.RIGHT);
        if (switchPressed) {
            activeMenuSide = activeMenuSide == 1 ? 2 : 1;
            traceMenuInput("TAB");
        }
        if (confirmed(activeMenuSide)) return;

        if (Gdx.input.isKeyJustPressed(Input.Keys.I)) {
            toggleInventoryFocus(activeMenuSide);
            traceMenuInput("I");
        }
        moveSelection(activeMenuSide);

        if (!Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) return;
        traceMenuInput("ENTER");
        confirmSelection(activeMenuSide);
    }

    private void handleSelection(int side) {
        if (confirmed(side)) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.I)) {
            toggleInventoryFocus(side);
            traceMenuInput("I");
        }
        moveSelection(side);

        if (!Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) return;
        traceMenuInput("ENTER");
        confirmSelection(side);
    }

    private void moveSelection(int side) {
        boolean up = Gdx.input.isKeyJustPressed(Input.Keys.W)
            || Gdx.input.isKeyJustPressed(Input.Keys.UP);
        boolean down = Gdx.input.isKeyJustPressed(Input.Keys.S)
            || Gdx.input.isKeyJustPressed(Input.Keys.DOWN);
        if (inventoryFocused(side)) {
            moveInventorySelection(side, up ? -1 : down ? 1 : 0);
        } else {
            int count = actionsFor(side).length;
            int selected = side == 1 ? p1Selected : p2Selected;
            selected = Math.max(0, Math.min(count - 1, selected));
            if (up) selected = (selected + count - 1) % count;
            if (down) selected = (selected + 1) % count;
            if (side == 1) p1Selected = selected;
            else p2Selected = selected;
        }
        if (up || down) traceMenuInput(up ? "UP" : "DOWN");
    }

    private void confirmSelection(int side) {
        if (inventoryFocused(side)) {
            selectInventoryCategory(side, side == 1 ? p1InventorySelected : p2InventorySelected);
            return;
        }
        PlayerActionType[] options = actionsFor(side);
        int selected = side == 1 ? p1Selected : p2Selected;
        selected = Math.max(0, Math.min(options.length - 1, selected));
        beginActionConfirmation(side, options[selected]);
    }

    private boolean inventoryFocused(int side) {
        return side == 1 ? p1InventoryFocus : p2InventoryFocus;
    }

    // I moves between the ACTIONS section and the INVENTORY section of every Turn Menu
    private void toggleInventoryFocus(int side) {
        if (side == 1) p1InventoryFocus = !p1InventoryFocus;
        else p2InventoryFocus = !p2InventoryFocus;
        if (inventoryFocused(side)) logInventorySelection(side);
    }

    private void moveInventorySelection(int side, int direction) {
        if (direction == 0) return;
        int count = TurnPanel.InventoryCategory.values().length;
        if (side == 1) p1InventorySelected = (p1InventorySelected + direction + count) % count;
        else p2InventorySelected = (p2InventorySelected + direction + count) % count;
        logInventorySelection(side);
    }

    private void logInventorySelection(int side) {
        int selected = side == 1 ? p1InventorySelected : p2InventorySelected;
        TurnPanel.InventoryCategory category = TurnPanel.InventoryCategory.values()[selected];
        int quantity = inventoryQuantities(side)[selected];
        String name = roleForSide(side).callSign();
        Gdx.app.log("Level3TurnMenuTrace", name + " selected "
            + category.name() + " quantity=" + quantity);
        if (quantity == 0) {
            Gdx.app.log("Level3PromptTrace", name + " " + promptItemName(category) + " unavailable");
        }
    }

    private void selectInventoryCategory(int side, int categoryIndex) {
        TurnPanel.InventoryCategory[] categories = TurnPanel.InventoryCategory.values();
        if (categoryIndex < 0 || categoryIndex >= categories.length) return;
        logInventorySelection(side);
        int[] quantities = inventoryQuantities(side);
        InventoryItem[] representatives = inventoryRepresentatives(side);
        selectedItemSide = side;
        selectedForReaction = menuIsReaction;
        selectedItemCategory = categoryIndex;
        selectedItemQuantity = quantities[categoryIndex];
        selectedItem = representatives[categoryIndex];
        closeEquipmentSelection();
        p1InventoryFocus = false;
        p2InventoryFocus = false;
        setInputState(InputState.INVENTORY_PREVIEW, "inventory item selected");
    }

    private void clearSelectedItem() {
        selectedItemSide = 0;
        selectedForReaction = false;
        selectedItemCategory = -1;
        selectedItemQuantity = 0;
        selectedItem = null;
        preparedTntAnswered = false;
        lastPromptLogSignature = "";
    }

    private void beginActionConfirmation(int side, PlayerActionType action) {
        if (action == PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT) {
            boolean allowed = authorizationUnlocked();
            Gdx.app.log("Level3EndingTrace", "Before Restore Authorization: remaining drones="
                + droneVisual.getActiveCount() + " remaining turrets=" + turretVisual.getActiveCount()
                + " recoverMemoryCompleted=" + recoverMemoryCompleted()
                + " authorizationUnlocked=" + allowed);
            if (!allowed) return;
        }
        if (Level3Controller.requiresEquipment(action)) {
            int firstSlot = Level3Controller.firstCompatibleSlot(inventories, side, action);
            if (firstSlot < 0) return;
            // With one kind of item there is nothing to choose between (three Shields are one
            // choice), so the picker would only add a second confirm. Commit straight away
            if (Level3Controller.compatibleSlotCount(inventories, side, action) <= 1) {
                Gdx.app.log("Level3TurnMenuTrace", action.label()
                    + " has one candidate (slot " + firstSlot + "); skipping the equipment picker");
                confirmAction(side, action, firstSlot);
                return;
            }
            equipmentOpen = true;
            equipmentSide = side;
            equipmentAction = action;
            equipmentSelectedSlot = firstSlot;
            inventories.closeAll();
            return;
        }
        confirmAction(side, action, -1);
    }

    private void handleEquipmentInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            closeEquipmentSelection();
            return;
        }
        boolean up = Gdx.input.isKeyJustPressed(Input.Keys.W)
            || Gdx.input.isKeyJustPressed(Input.Keys.UP);
        boolean down = Gdx.input.isKeyJustPressed(Input.Keys.S)
            || Gdx.input.isKeyJustPressed(Input.Keys.DOWN);
        if (up) equipmentSelectedSlot = nextCompatibleSlot(-1);
        if (down) equipmentSelectedSlot = nextCompatibleSlot(1);

        if (!Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) return;
        int side = equipmentSide;
        int slot = equipmentSelectedSlot;
        PlayerActionType action = equipmentAction;
        closeEquipmentSelection();
        confirmAction(side, action, slot);
    }

    private int nextCompatibleSlot(int direction) {
        int slot = equipmentSelectedSlot;
        int slotCount = Inventory.CAPACITY + 1;
        for (int i = 0; i < slotCount; i++) {
            slot = (slot + direction + slotCount) % slotCount;
            if (Level3Controller.isKindRepresentative(inventories, equipmentSide, equipmentAction,
                slot)) return slot;
        }
        return equipmentSelectedSlot;
    }

    private void confirmAction(int side, PlayerActionType action, int inventorySlot) {
        // Read before confirming: on the host, confirming Recover Memory sets recoverMemoryCompleted.
        boolean recoveringMemory = action == PlayerActionType.LISTENER_RECOVER_LOGS
            && !recoverMemoryCompleted() && defensesCleared();
        if (controller != null) controller.confirmLocal(side, action, inventorySlot);
        else clientSession.send(new Level3ActionMessage(action.ordinal(), inventorySlot));
        if (recoveringMemory && (controller == null || confirmed(side))) {
            playRecoverMemorySequence(side);
        }
        closeTurnMenu();
    }

    // Level 2 style golden bolt from the Listener into the Warden. Visual only: it never touches
    // startPlayerAttack/startShooting or the Gun, so it uses no ammo and starts no combat state.
    private void playRecoverMemorySequence(int side) {
        Player listener = side == 1 ? player1 : player2;
        recoverFromX = listener.centreX();
        recoverFromY = listener.centreY();
        recoverBoltTimer = RECOVER_BOLT_TRAVEL + RECOVER_BOLT_BURST;
        Gdx.app.log("Level3RecoverMemoryTrace", "golden memory bolt started side=" + side
            + " playerAnimation=" + listener.getAnimationState());
    }

    private void drawRecoverMemorySequence(ShapeRenderer shape) {
        float elapsed = RECOVER_BOLT_TRAVEL + RECOVER_BOLT_BURST - recoverBoltTimer;
        float toX = wardenVisual.centreX();
        float toY = wardenVisual.centreY();
        if (elapsed < RECOVER_BOLT_TRAVEL) {
            float head = elapsed / RECOVER_BOLT_TRAVEL;
            float tail = Math.max(0f, head - 0.2f);
            float headX = recoverFromX + (toX - recoverFromX) * head;
            float headY = recoverFromY + (toY - recoverFromY) * head;
            float tailX = recoverFromX + (toX - recoverFromX) * tail;
            float tailY = recoverFromY + (toY - recoverFromY) * tail;
            shape.setColor(1f, 0.78f, 0.22f, 0.55f);
            shape.rectLine(tailX, tailY, headX, headY, 14f);
            shape.setColor(1f, 0.92f, 0.55f, 1f);
            shape.rectLine(tailX, tailY, headX, headY, 5f);
            shape.circle(headX, headY, 11f);
        } else {
            float burst = (elapsed - RECOVER_BOLT_TRAVEL) / RECOVER_BOLT_BURST;
            shape.setColor(1f, 0.85f, 0.35f, 0.6f * (1f - burst));
            shape.circle(toX, toY, 24f + 90f * burst);
        }
    }

    private boolean defensesCleared() {
        return droneVisual.getActiveCount() == 0 && turretVisual.getActiveCount() == 0;
    }

    private void closeEquipmentSelection() {
        equipmentOpen = false;
        equipmentSide = 0;
        equipmentSelectedSlot = -1;
        equipmentAction = null;
    }

    private boolean confirmed(int side) {
        if (controller != null) {
            return side == 1 ? controller.getTurnManager().p1Confirmed() : controller.getTurnManager().p2Confirmed();
        }
        return side == 1 ? remoteP1Action != null : remoteP2Action != null;
    }

    private PlayerActionType[] actionsFor(int side) {
        Role role = side == 1 ? sideOneRole : sideOneRole.other();
        return Level3Controller.availableActions(inventories, sidearms, side, role);
    }

    private int[] inventoryQuantities(int side) {
        TurnPanel.InventoryCategory[] categories = TurnPanel.InventoryCategory.values();
        int[] quantities = new int[categories.length];
        Inventory inventory = inventories.forPlayer(side);
        for (int slot = 0; slot < Inventory.CAPACITY; slot++) {
            InventoryItem item = inventory.get(slot);
            for (TurnPanel.InventoryCategory category : categories) {
                if (itemMatchesCategory(side, item, category)) quantities[category.ordinal()]++;
            }
        }
        return quantities;
    }

    private InventoryItem[] inventoryRepresentatives(int side) {
        TurnPanel.InventoryCategory[] categories = TurnPanel.InventoryCategory.values();
        InventoryItem[] items = new InventoryItem[categories.length];
        Inventory inventory = inventories.forPlayer(side);
        for (int slot = 0; slot < Inventory.CAPACITY; slot++) {
            InventoryItem item = inventory.get(slot);
            for (TurnPanel.InventoryCategory category : categories) {
                if (items[category.ordinal()] == null && itemMatchesCategory(side, item, category)) {
                    items[category.ordinal()] = item;
                }
            }
        }
        for (TurnPanel.InventoryCategory category : categories) {
            if (items[category.ordinal()] == null) {
                items[category.ordinal()] = itemDefinitions[category.ordinal()];
            }
        }
        return items;
    }

    private boolean itemMatchesCategory(int side, InventoryItem item,
                                        TurnPanel.InventoryCategory category) {
        Role role = roleForSide(side);
        switch (category) {
            case SIDEARM:
                return Level3Controller.itemSupportsAction(
                    role == Role.BREAKER ? PlayerActionType.BREAKER_WEAPON_ATTACK
                        : PlayerActionType.LISTENER_WEAPON_ATTACK, item);
            case SHIELD:
                return Level3Controller.itemSupportsAction(
                    role == Role.BREAKER ? PlayerActionType.BREAKER_SHIELD_DEFENSE
                        : PlayerActionType.LISTENER_SHIELD_DEFENSE, item);
            case TNT:
                return Level3Controller.itemSupportsAction(PlayerActionType.USE_TNT, item);
            case MEDKIT:
                return Level3Controller.itemSupportsAction(PlayerActionType.USE_MEDKIT, item);
            default:
                return false;
        }
    }

    private void logArmedPrompt(int side, TurnPanel.InventoryCategory category,
                                int quantity, boolean available) {
        String signature = side + ":" + category + ":" + quantity + ":" + available;
        if (signature.equals(lastPromptLogSignature)) return;
        lastPromptLogSignature = signature;
        String name = roleForSide(side).callSign();
        Gdx.app.log("Level3PromptTrace", name + " " + promptItemName(category)
            + (available ? " prompt enabled" : " unavailable"));
    }

    private static String promptItemName(TurnPanel.InventoryCategory category) {
        switch (category) {
            case SIDEARM: return "Sidearm";
            case SHIELD: return "Shield";
            case MEDKIT: return "Medkit";
            default: return category.name();
        }
    }

    private static int indexOf(PlayerActionType[] actions, PlayerActionType action) {
        for (int i = 0; i < actions.length; i++) if (actions[i] == action) return i;
        return 0;
    }

    // CombatInputHandler: the only caller of Player.startAttack(). It is fed by LMB/RMB alone (see
    // updateAs*) and shares no method with the menu handlers, so ENTER can never start an attack.
    private void startPlayerAttack(int side, Player player, boolean pressed) {
        if (!pressed || !player.startAttack()) return;
        Gdx.app.log("InputTrace", "key=" + (side == 1 ? "LMB" : "RMB")
            + " actionTriggered=true");
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
        closeEquipmentSelection();
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
        remoteMemoryRecovered = false;
        remoteReactionOpen = false;
        remoteReactionAttackType = null;
        remoteReactionTargetSide = 0;
        remoteP1Action = null;
        remoteP2Action = null;
        remoteWardenLine = "";
        remoteBreakerLine = "";
        remoteListenerLine = "";
        p1Selected = 0;
        p2Selected = 0;
        p1InventorySelected = 0;
        p2InventorySelected = 0;
        p1InventoryFocus = false;
        p2InventoryFocus = false;
        clearSelectedItem();
        closeEquipmentSelection();
        activeMenuSide = 1;
        recoverBoltTimer = 0f;
        combatLatched = false;
        menuIsReaction = false;
        setInputState(InputState.NORMAL_GAMEPLAY, "level reset");
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
        if (sidearms.hasShotToDraw() || turretVisual.hasLaserToDraw()
            || turretVisual.hasWarningToDraw() || recoverBoltTimer > 0f) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shape.setProjectionMatrix(camera.combined);
            shape.begin(ShapeRenderer.ShapeType.Filled);
            sidearms.drawShots(shape);
            if (turretVisual.hasWarningToDraw()) turretVisual.drawWarning(shape);
            if (turretVisual.hasLaserToDraw()) turretVisual.drawLaser(shape);
            if (recoverBoltTimer > 0f) drawRecoverMemorySequence(shape);
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
        tntExplosion.draw(batch);
        player1.draw(batch);
        player2.draw(batch);
        world.renderOverhang(batch);
        batch.end();
    }

    private void drawUI() {
        OrthographicCamera uiCamera = ui.camera();
        batch.setProjectionMatrix(uiCamera.combined);
        shape.setProjectionMatrix(uiCamera.combined);
        TurnPromptRenderer.render(hud, shape, batch, ui, TITLE, objective(), turnMenuAvailable(),
            tntPromptVisible(), turnPromptHint());
        hud.drawPlayerCards(shape, batch, ui, player1, player2, sideOneRole);
        sidearms.drawHuds(shape, batch, hud.font(), ui.width());

        if (bossStarted && inputState == InputState.TURN_MENU_OPEN && !equipmentOpen) {
            int[] p1Quantities = inventoryQuantities(1);
            int[] p2Quantities = inventoryQuantities(2);
            boolean reactionMenu = menuIsReaction;
            turnPanel.render(shape, batch, ui, phase(), wardenState(), stability(), directiveConflict(), dualMeter(),
                sideOneRole,
                sideOneRole.callSign(), sideOneRole.other().callSign(), p1Selected, p2Selected,
                reactionMenu ? false : confirmed(1), reactionMenu ? false : confirmed(2),
                actionsFor(1), actionsFor(2), activeMenuSide, soloControl(),
                authorizationUnlocked(), authorizationLockReason(),
                p1InventorySelected, p2InventorySelected, p1InventoryFocus, p2InventoryFocus,
                p1Quantities, p2Quantities);
        }
        if (equipmentOpen) {
            Role role = equipmentSide == 1 ? sideOneRole : sideOneRole.other();
            equipmentPanel.render(shape, batch, ui, equipmentSide, role.name(), equipmentAction,
                inventories, equipmentSelectedSlot, sidearms.forSide(equipmentSide),
                equipmentSide == 1 ? SplitScreen.ACCENT_P1 : SplitScreen.ACCENT_P2);
        }
        if (!reactionOpen()) lastPromptLogSignature = "";
        if (selectedItemCategory >= 0 && inputState == InputState.INVENTORY_PREVIEW) {
            TurnPanel.InventoryCategory category =
                TurnPanel.InventoryCategory.values()[selectedItemCategory];
            Color accent = selectedItemSide == 1 ? SplitScreen.ACCENT_P1 : SplitScreen.ACCENT_P2;
            inventories.renderItemInfoPopup(shape, batch, ui.width(), selectedItemSide,
                category.label(), roleForSide(selectedItemSide).callSign(), selectedItemQuantity,
                selectedItemDescription(category), category != TurnPanel.InventoryCategory.SIDEARM,
                selectedItem, selectedItemQuantity > 0, accent);
        }
        if (selectedItemCategory >= 0 && inputState == InputState.ITEM_READY) {
            TurnPanel.InventoryCategory category =
                TurnPanel.InventoryCategory.values()[selectedItemCategory];
            logArmedPrompt(selectedItemSide, category, selectedItemQuantity, true);
            ReactionPanel panel = selectedItemSide == 1 ? reactionPanelP1 : reactionPanelP2;
            // TNT's instruction is the top-centre HUD line, which is up whenever TNT is carried;
            // drawing it again here would print the same words over it
            if (category != TurnPanel.InventoryCategory.TNT) {
                panel.renderActivationHeader(batch, ui, activationInstruction(category));
            }
        }
        // The T reminder is gone in this case (turnMenuAvailable is false), so say what does work
        if (bossStarted && reactionOpen() && localCanRespondToReaction()
            && inputState == InputState.NORMAL_GAMEPLAY && !localReactionHasEquipment()
            && !storyBanner.isOpen() && !logPopup.isOpen()) {
            ReactionPanel panel = reactionTargetSide() == 1 ? reactionPanelP1 : reactionPanelP2;
            panel.renderActivationHeader(batch, ui, "NO EQUIPMENT - PRESS ENTER TO TAKE THE HIT");
        }
        inventories.render(shape, batch, ui.width(), ui.height(), player1, player2,
            SplitScreen.ACCENT_P1, SplitScreen.ACCENT_P2);
        storyBanner.render(shape, batch, ui.width(), ui.height());
        logPopup.render(shape, batch, ui.width(), ui.height());
    }

    private String selectedItemDescription(TurnPanel.InventoryCategory category) {
        if (selectedItem != null) return selectedItem.getDescription();
        switch (category) {
            case SIDEARM: return "Ranged security sidearm.";
            case SHIELD: return "Protection equipment.";
            case MEDKIT: return "Medical recovery supplies.";
            case TNT: return "Demolition charge. Takes down one turret.";
            default: return "Equipment unavailable.";
        }
    }

    private static String activationInstruction(TurnPanel.InventoryCategory category) {
        switch (category) {
            case SIDEARM: return "PRESS X TO FIRE SIDE ARM";
            case SHIELD: return "PRESS H TO ACTIVATE SHIELD";
            case MEDKIT: return "PRESS M TO USE MEDKIT";
            case TNT: return TurnPromptRenderer.TNT_TEXT;
            default: return "";
        }
    }

    private static String activationKey(TurnPanel.InventoryCategory category) {
        switch (category) {
            case SIDEARM: return "X";
            case SHIELD: return "H";
            case MEDKIT: return "M";
            case TNT: return "Y";
            default: return "UNKNOWN";
        }
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

    // Same three checks as Level3Controller, from host state on the host and from the host's synced
    // drone/turret counts and memoryRecovered flag on the client, so both peers always agree.
    private boolean recoverMemoryCompleted() {
        return controller != null ? controller.isRecoverMemoryCompleted() : remoteMemoryRecovered;
    }

    private boolean authorizationUnlocked() {
        if (controller != null) return controller.isAuthorizationUnlocked();
        return defensesCleared() && recoverMemoryCompleted();
    }

    // Second line of the top-centre turn prompt: which action the operators must pick next.
    private String turnPromptHint() {
        WardenState state = wardenState();
        if (!bossStarted || !defensesCleared() || state == WardenState.DUAL_AUTHORIZATION
            || state.isStoodDown()) return null;
        return recoverMemoryCompleted() ? TurnPromptRenderer.HINT_RESTORE_AUTHORIZATION
            : TurnPromptRenderer.HINT_RECOVER_MEMORY;
    }

    private boolean reactionOpen() {
        return controller != null ? controller.isReactionOpen() : remoteReactionOpen;
    }

    private Level3Controller.EnemyAttackType reactionAttackType() {
        return controller != null ? controller.getReactionAttackType() : remoteReactionAttackType;
    }

    private int reactionTargetSide() {
        return controller != null ? controller.getReactionTargetSide() : remoteReactionTargetSide;
    }

    private boolean localCanRespondToReaction() {
        if (soloControl()) return true;
        return (isHost && reactionTargetSide() == 1) || (!isHost && reactionTargetSide() == 2);
    }

    private String authorizationLockReason() {
        if (controller != null) return controller.authorizationLockReason();
        if (droneVisual.getActiveCount() != 0 || turretVisual.getActiveCount() != 0) {
            return "Restore Authorization requires all defenses disabled";
        }
        if (!remoteMemoryRecovered) return "Restore Authorization requires recovered memory";
        return "";
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
        tntExplosion.dispose();
        if (controller != null) controller.dispose();
        else {
            wardenVisual.dispose();
            droneVisual.dispose();
            turretVisual.dispose();
        }
        if (carriedLootAssets != null) carriedLootAssets.dispose();
        if (ownedItemDefinitionTextures != null) {
            for (Texture texture : ownedItemDefinitionTextures) texture.dispose();
        }
        if (server != null) server.stop();
        if (client != null) client.stop();
        if (hostSession != null) hostSession.stop();
        if (clientSession != null) clientSession.stop();
    }
}
