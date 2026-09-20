package io.github.fableops.level3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.Gdx;

import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.inventory.Inventory;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.inventory.network.InventoryTransferMessage;
import io.github.fableops.level2.Gun;
import io.github.fableops.level3.network.Level3ActionMessage;
import io.github.fableops.level3.network.Level3TurnStateMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

// Host-authoritative turn encounter. The Warden has no health: operator actions stabilize its
// directive, expose the old safety decision, and finally prove dual authorization.
public class Level3Controller {

    public enum EnemyAttackType { DRONE, TURRET }

    // Encodes the existing shared slot in the same integer carried by Level3ActionMessage. Personal
    // inventory indices remain 0..24, so older messages and UI selections keep their meaning.
    public static final int SHARED_SLOT_INDEX = Inventory.CAPACITY;

    public static final int BANNER_MEMORY = 0;
    public static final int BANNER_CONFLICT = 1;
    public static final int BANNER_DUAL = 2;
    public static final int BANNER_LOG = 3; // retained for wire compatibility with the first draft

    private static final float STABILITY_MEMORY = 48f;
    private static final float STABILITY_CONFLICT = 75f;
    private static final float DIRECTIVE_MEMORY = 52f;
    private static final float DIRECTIVE_CONFLICT = 25f;
    private static final float DUAL_CAP = 100f;
    private static final float WARDEN_TURN_DELAY = 1.4f;
    private static final float RESOLUTION_DELAY = 1.9f;
    private static final float STAND_DOWN_DELAY = 3.5f;
    private static final float ENDING_DELAY = 2.0f;

    private final HostSession hostSession;
    private final PlayerInventories inventories;
    private final Gun gun;
    private final Player player1;
    private final Player player2;
    private final Role sideOneRole;
    private final TurnManager turnManager = new TurnManager();
    private final WardenController warden;
    private final DefenseDroneController drone;
    private final SecurityTurretController turret;

    private WardenActionType rolledWardenAction = WardenActionType.DEPLOY_DRONE;
    private int wardenActionCursor;
    private float nextWardenDamageMultiplier = 1f;
    private boolean p1Braced;
    private boolean p2Braced;
    private boolean listenerProtected;
    private boolean breakerSupported;
    private Player pendingDroneTarget;
    private float pendingDroneDamageTimer = -1f;
    private Player pendingTurretTarget;
    private float pendingTurretDamageTimer = -1f;
    private float standDownTimer = -1f;
    private float endingTimer = -1f;
    private boolean endingReady;
    private boolean memoryRecovered;
    private boolean reactionOpen;
    private EnemyAttackType reactionAttackType;
    private int reactionTargetSide;
    private int reactionUnitIndex = -1;
    private boolean reactionBlocksDamage;
    private int reactionSelectionOrdinal = -1;
    private int damageAppliedTargetSide;
    private boolean p1InsideTurretRadius;
    private boolean p2InsideTurretRadius;
    private boolean turretRangeMissLogged;
    private boolean defenseRestorationInProgress;
    private boolean restorationStarted;
    private boolean restorationCompleted;

    private int bannerSeq;
    private int pendingBannerId = -1;
    private String wardenLine = "";
    private String breakerLine = "";
    private String listenerLine = "";

    private boolean wardenAttacked;
    private boolean wardenDamaged;
    private boolean droneAttacked;
    private boolean turretAiming;
    private boolean turretAttacked;
    private int droneDamagedIndex = -1;
    private int turretDamagedIndex = -1;
    private boolean turretDestroyed;
    private int p1ConsumedSlot = -1;
    private int p2ConsumedSlot = -1;
    private int p1SelectedItemSlot = -1;
    private int p2SelectedItemSlot = -1;

    public Level3Controller(HostSession hostSession, Level3Map world, PlayerInventories inventories,
                            Gun gun, Player player1, Player player2, Role sideOneRole) {
        this.hostSession = hostSession;
        this.inventories = inventories;
        this.gun = gun;
        this.player1 = player1;
        this.player2 = player2;
        this.sideOneRole = sideOneRole;

        float[] wardenAnchor = world.getWardenAnchor();
        float[] droneAnchor = world.getDroneAnchor();
        warden = new WardenController(wardenAnchor[0], wardenAnchor[1]);
        drone = new DefenseDroneController(droneAnchor[0], droneAnchor[1]);
        turret = new SecurityTurretController(world.getTurretGroundPoints());
    }

    public WardenController getWarden() { return warden; }
    public DefenseDroneController getDrone() { return drone; }
    public SecurityTurretController getTurret() { return turret; }
    public TurnManager getTurnManager() { return turnManager; }
    public int getBannerSeq() { return bannerSeq; }
    public int getBannerId() { return pendingBannerId; }
    public String getWardenLine() { return wardenLine; }
    public String getBreakerLine() { return breakerLine; }
    public String getListenerLine() { return listenerLine; }
    public boolean isMemoryRecovered() { return memoryRecovered; }
    public boolean isReactionOpen() { return reactionOpen; }
    public EnemyAttackType getReactionAttackType() { return reactionAttackType; }
    public int getReactionTargetSide() { return reactionTargetSide; }

    // Called only during Level3Screen's existing between-turn movement window. A player triggers
    // once on entering a turret radius and must leave before the same proximity can trigger again.
    public boolean detectTurretThreat() {
        if (reactionOpen || pendingDroneDamageTimer > 0f || pendingTurretDamageTimer > 0f
            || turnManager.getPhase() != TurnManager.Phase.PLAYER_TURN || !turret.isActive()) {
            return false;
        }
        boolean p1Inside = turret.detectingUnit(player1.centreX(), player1.centreY()) >= 0;
        boolean p2Inside = turret.detectingUnit(player2.centreX(), player2.centreY()) >= 0;
        if (!p1Inside && !p2Inside) {
            if (!turretRangeMissLogged) logTurretRangeMiss();
            turretRangeMissLogged = true;
        } else {
            turretRangeMissLogged = false;
        }
        boolean p1Entered = p1Inside && !p1InsideTurretRadius;
        boolean p2Entered = p2Inside && !p2InsideTurretRadius;
        p1InsideTurretRadius = p1Inside;
        p2InsideTurretRadius = p2Inside;
        if (!p1Entered && !p2Entered) return false;

        Player target = p1Entered && p2Entered ? nearerDetectedPlayer() : p1Entered ? player1 : player2;
        openTurretReaction(target);
        broadcast();
        return reactionOpen;
    }

    public boolean isAuthorizationAllowed() {
        return defensesCleared() && memoryRecovered;
    }

    public String authorizationLockReason() {
        if (!defensesCleared()) return "Restore Authorization requires all defenses disabled";
        if (!memoryRecovered) return "Restore Authorization requires recovered memory";
        return "";
    }

    public void startEncounter() {
        drone.spawnAll();
        turret.activateAll();
        wardenLine = "Unauthorized operators detected. Defensive protocol active.";
        broadcast();
    }

    public static boolean hasUsableItem(PlayerInventories inventories, int side) {
        return hasMedkit(inventories, side) || hasShield(inventories, side);
    }

    public static boolean hasSidearm(PlayerInventories inventories, Gun gun, int side) {
        return gun != null && hasNamedItem(inventories, side, "Sidearm");
    }

    public static boolean hasShield(PlayerInventories inventories, int side) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && isShieldItem(item)) return true;
        }
        return isShieldItem(inventories.sharedItem());
    }

    public static boolean hasMedkit(PlayerInventories inventories, int side) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && item.isConsumable()) return true;
        }
        InventoryItem shared = inventories.sharedItem();
        return shared != null && shared.isConsumable();
    }

    public static boolean requiresEquipment(PlayerActionType action) {
        return action == PlayerActionType.BREAKER_WEAPON_ATTACK
            || action == PlayerActionType.LISTENER_WEAPON_ATTACK
            || action == PlayerActionType.BREAKER_SHIELD_DEFENSE
            || action == PlayerActionType.LISTENER_SHIELD_DEFENSE
            || action == PlayerActionType.USE_MEDKIT;
    }

    public static boolean itemSupportsAction(PlayerActionType action, InventoryItem item) {
        if (item == null) return false;
        switch (action) {
            case BREAKER_WEAPON_ATTACK:
            case LISTENER_WEAPON_ATTACK:
                return "Sidearm".equalsIgnoreCase(item.getName());
            case BREAKER_SHIELD_DEFENSE:
            case LISTENER_SHIELD_DEFENSE:
                return isShieldItem(item);
            case USE_MEDKIT:
                return item.isConsumable();
            default:
                return false;
        }
    }

    public static int firstCompatibleSlot(PlayerInventories inventories, int side,
                                          PlayerActionType action) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            if (itemSupportsAction(action, inv.get(i))) return i;
        }
        if (itemSupportsAction(action, inventories.sharedItem())) return SHARED_SLOT_INDEX;
        return -1;
    }

    public static InventoryItem itemAt(PlayerInventories inventories, int side, int slot) {
        return slot == SHARED_SLOT_INDEX
            ? inventories.sharedItem()
            : inventories.forPlayer(side).get(slot);
    }

    public static PlayerActionType[] availableActions(PlayerInventories inventories, Gun gun,
                                                       int side, Role role) {
        List<PlayerActionType> actions = new ArrayList<>();
        if (role == Role.BREAKER) {
            actions.add(PlayerActionType.BREAKER_PHYSICAL_STRIKE);
            if (hasSidearm(inventories, gun, side)) actions.add(PlayerActionType.BREAKER_WEAPON_ATTACK);
            actions.add(PlayerActionType.BREAKER_DISABLE_DRONE);
            if (hasShield(inventories, side)) actions.add(PlayerActionType.BREAKER_SHIELD_DEFENSE);
            actions.add(PlayerActionType.BREAKER_PROTECT_LISTENER);
            actions.add(PlayerActionType.BREAKER_REPAIR_MECHANISM);
        } else {
            actions.add(PlayerActionType.LISTENER_SCAN_WARDEN);
            actions.add(PlayerActionType.LISTENER_REDUCE_SUBROUTINE);
            actions.add(PlayerActionType.LISTENER_RECOVER_LOGS);
            actions.add(PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT);
            if (hasSidearm(inventories, gun, side)) actions.add(PlayerActionType.LISTENER_WEAPON_ATTACK);
            if (hasShield(inventories, side)) actions.add(PlayerActionType.LISTENER_SHIELD_DEFENSE);
            actions.add(PlayerActionType.LISTENER_SUPPORT_BREAKER);
            if (hasMedkit(inventories, side)) actions.add(PlayerActionType.USE_MEDKIT);
        }
        return actions.toArray(new PlayerActionType[0]);
    }

    public MessageListener asMessageListener() {
        return (type, body) -> {
            try {
                if ("INVENTORY_TRANSFER".equals(type)) {
                    InventoryTransferMessage msg = InventoryTransferMessage.deserialize(body);
                    if (msg.getPlayerSide() == 2) {
                        Gdx.app.postRunnable(() -> transferInventory(msg));
                    }
                    return;
                }
                if (!"LEVEL3_ACTION".equals(type)) return;
                Level3ActionMessage msg = Level3ActionMessage.deserialize(body);
                int ordinal = msg.getActionOrdinal();
                if (msg.isReaction()) {
                    ReactionType[] reactions = ReactionType.values();
                    if (ordinal < 0 || ordinal >= reactions.length) return;
                    Gdx.app.postRunnable(() -> confirmReaction(2, reactions[ordinal],
                        msg.getInventorySlot()));
                    return;
                }
                PlayerActionType[] actions = PlayerActionType.values();
                if (ordinal < 0 || ordinal >= actions.length) return;
                Gdx.app.postRunnable(() -> confirmLocal(2, actions[ordinal], msg.getInventorySlot()));
            } catch (RuntimeException ignored) {
                // Malformed event-channel input must not take down the render thread.
            }
        };
    }

    public void transferInventory(InventoryTransferMessage message) {
        if (!inventories.applyTransfer(message.getPlayerSide(), message.isFromShared(),
            message.getPersonalSlot())) return;
        if (gun != null) gun.giveTo(inventories.currentHolder("Sidearm"));
        if (hostSession != null) {
            hostSession.send(message);
            if (gun != null) hostSession.send(gun.toMessage());
        }
    }

    public void confirmLocal(int side, PlayerActionType action) {
        confirmLocal(side, action, -1);
    }

    public void confirmLocal(int side, PlayerActionType action, int inventorySlot) {
        if (turnManager.getPhase() != TurnManager.Phase.PLAYER_TURN || action == null) return;
        Role role = roleForSide(side);
        if (action == PlayerActionType.USE_ITEM) {
            if (!hasUsableItem(inventories, side)) return;
        } else if (action == PlayerActionType.USE_MEDKIT && hasMedkit(inventories, side)) {
            // Quick-use prompts may consume a carried medkit for either operator without adding a
            // new action row to the Breaker's established turn menu.
        } else if (!Arrays.asList(availableActions(inventories, gun, side, role)).contains(action)) {
            return;
        }
        if (action == PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT) {
            logAuthorizationValidation();
            if (!isAuthorizationAllowed()) return;
        }
        if (requiresEquipment(action)) {
            InventoryItem selected = itemAt(inventories, side, inventorySlot);
            if (!itemSupportsAction(action, selected)) return;
        } else {
            inventorySlot = -1;
        }

        boolean confirmed = side == 1 ? turnManager.p1Confirmed() : turnManager.p2Confirmed();
        if (confirmed) return;
        if (side == 1) {
            p1SelectedItemSlot = inventorySlot;
            turnManager.confirmP1(action);
        } else if (side == 2) {
            p2SelectedItemSlot = inventorySlot;
            turnManager.confirmP2(action);
        }
        else return;
        broadcast();
    }

    public void confirmReaction(int side, ReactionType reaction, int inventorySlot) {
        if (!reactionOpen || reaction == null || side != reactionTargetSide) return;
        Player target = side == 1 ? player1 : player2;
        PlayerActionType weaponAction = roleForSide(side) == Role.BREAKER
            ? PlayerActionType.BREAKER_WEAPON_ATTACK : PlayerActionType.LISTENER_WEAPON_ATTACK;
        PlayerActionType shieldAction = roleForSide(side) == Role.BREAKER
            ? PlayerActionType.BREAKER_SHIELD_DEFENSE : PlayerActionType.LISTENER_SHIELD_DEFENSE;

        switch (reaction) {
            case SIDEARM:
                if (!hasSidearm(inventories, gun, side) || !gun.hasAmmo()
                    || !itemSupportsAction(weaponAction, itemAt(inventories, side, inventorySlot))) return;
                break;
            case SHIELD:
                if (!itemSupportsAction(shieldAction, itemAt(inventories, side, inventorySlot))) return;
                break;
            case MEDKIT:
                if (!itemSupportsAction(PlayerActionType.USE_MEDKIT,
                    itemAt(inventories, side, inventorySlot))) return;
                break;
            case NONE:
                inventorySlot = -1;
                break;
            default:
                return;
        }

        reactionOpen = false;
        reactionBlocksDamage = false;
        reactionSelectionOrdinal = reaction.ordinal();
        Gdx.app.log("Level3ReactionTrace", "Player reaction selected side=" + side
            + " reaction=" + reaction + " attacker=" + reactionAttackType);

        if (reaction == ReactionType.SIDEARM) {
            target.startShooting();
            gun.useTurnBasedRound();
            float hitX = reactionAttackType == EnemyAttackType.DRONE
                ? drone.unitX(reactionUnitIndex) : turret.unitX(reactionUnitIndex);
            float hitY = reactionAttackType == EnemyAttackType.DRONE
                ? drone.unitY(reactionUnitIndex) : turret.unitY(reactionUnitIndex);
            boolean hit = reactionAttackType == EnemyAttackType.DRONE
                ? damageReactionDrone(gun.turnBasedDamage())
                : damageReactionTurret(gun.turnBasedDamage());
            gun.showTurnBasedShot(target, hitX, hitY, hit);
            setOperatorLine(side, callSignOf(target) + " fires the Sidearm into the incoming attack.");
            Gdx.app.log("Level3ReactionTrace", "Player Sidearm used side=" + side
                + " ammoRemaining=" + gun.getTotalRounds() + " hit=" + hit);
            if (!reactionSourceActive()) {
                wardenLine = reactionAttackType == EnemyAttackType.DRONE
                    ? "The attacking defense drone is destroyed before impact."
                    : "The detected security turret is destroyed before it can aim.";
                clearReactionAttack();
                broadcast();
                return;
            }
        } else if (reaction == ReactionType.SHIELD) {
            target.startShielding();
            String result = useShield(target, inventorySlot, shieldAction);
            reactionBlocksDamage = true;
            setOperatorLine(side, result);
            Gdx.app.log("Level3ReactionTrace", "Player Shield activated side=" + side);
        } else if (reaction == ReactionType.MEDKIT) {
            String result = useMedkit(target, inventorySlot);
            setOperatorLine(side, result);
            Gdx.app.log("Level3ReactionTrace", "Player Medkit used side=" + side
                + " health=" + target.health);
        } else {
            setOperatorLine(side, callSignOf(target) + " cannot deploy defensive equipment in time.");
        }

        startReactionAttack();
        broadcast();
    }

    private void setOperatorLine(int side, String line) {
        if (roleForSide(side) == Role.BREAKER) breakerLine = line;
        else listenerLine = line;
    }

    public void update(float delta) {
        warden.update(delta);
        drone.update(delta);
        turret.update(delta);
        if (defenseRestorationInProgress && drone.isRestorationComplete()
            && turret.isRestorationComplete()) {
            defenseRestorationInProgress = false;
            restorationCompleted = true;
            Gdx.app.log("Level3RestorationTrace", "Defense network restoration completed");
            broadcast();
        }
        if (turret.consumeFiringStarted()) {
            turretAttacked = true;
            broadcast();
        }
        updatePendingDroneAttack(delta);
        updatePendingTurretAttack(delta);

        if (standDownTimer > 0f) {
            standDownTimer -= delta;
            if (standDownTimer <= 0f) {
                WardenState before = warden.getState();
                warden.setState(WardenState.STAND_DOWN);
                drone.setCalm(true);
                turret.setCalm(true);
                defenseRestorationInProgress = true;
                restorationStarted = true;
                Gdx.app.log("Level3RestorationTrace", "Defense network restoration started");
                endingTimer = ENDING_DELAY;
                standDownTimer = -1f;
                Gdx.app.log("Level3EndingTrace", "Warden blue restoration: wardenState before="
                    + before + " wardenState after=" + warden.getState()
                    + " endingReady=" + endingReady
                    + " hostility=" + warden.getDirectiveConflict());
                broadcast();
            }
            return;
        }
        if (endingTimer > 0f) {
            endingTimer -= delta;
            if (endingTimer <= 0f) {
                endingReady = true;
                endingTimer = -1f;
                Gdx.app.log("Level3EndingTrace", "endingReady=true state=" + warden.getState());
            }
            return;
        }
        if (warden.getState() == WardenState.DUAL_AUTHORIZATION || warden.getState().isStoodDown()) return;

        // The existing WARDEN_TURN phase remains active, but its display timer does not advance
        // while the targeted player is choosing a response or while the telegraphed hit is pending.
        if (reactionOpen || pendingDroneDamageTimer > 0f || pendingTurretDamageTimer > 0f) return;

        switch (turnManager.getPhase()) {
            case PLAYER_TURN:
                if (turnManager.bothConfirmed()) {
                    resolvePlayerTurn();
                    if (warden.getState() == WardenState.DUAL_AUTHORIZATION) {
                        wardenLine = "The Warden suspends its defensive response.";
                    }
                    turnManager.advanceTo(TurnManager.Phase.RESOLUTION, RESOLUTION_DELAY);
                    broadcast();
                }
                break;
            case RESOLUTION:
                if (turnManager.tick(delta)) {
                    if (warden.getState() == WardenState.DUAL_AUTHORIZATION) break;
                    rollWardenAction();
                    resolveWardenAction();
                    turnManager.advanceTo(TurnManager.Phase.WARDEN_TURN, WARDEN_TURN_DELAY);
                    broadcast();
                }
                break;
            case WARDEN_TURN:
                if (turnManager.tick(delta)) {
                    turnManager.resetForNextRound();
                    p1SelectedItemSlot = -1;
                    p2SelectedItemSlot = -1;
                    broadcast();
                }
                break;
            default:
                break;
        }
    }

    public boolean consumeEndingReady() {
        if (!endingReady) return false;
        endingReady = false;
        return true;
    }

    private void resolvePlayerTurn() {
        nextWardenDamageMultiplier = 1f;
        p1Braced = false;
        p2Braced = false;
        listenerProtected = false;
        breakerSupported = false;
        wardenLine = "The Warden evaluates the restored operator signatures.";

        boolean p1IsBreaker = sideOneRole == Role.BREAKER;
        Player breaker = p1IsBreaker ? player1 : player2;
        Player listener = p1IsBreaker ? player2 : player1;
        PlayerActionType breakerAction = p1IsBreaker ? turnManager.getP1Action() : turnManager.getP2Action();
        PlayerActionType listenerAction = p1IsBreaker ? turnManager.getP2Action() : turnManager.getP1Action();

        breakerSupported = listenerAction == PlayerActionType.LISTENER_SUPPORT_BREAKER;
        int breakerSlot = p1IsBreaker ? p1SelectedItemSlot : p2SelectedItemSlot;
        int listenerSlot = p1IsBreaker ? p2SelectedItemSlot : p1SelectedItemSlot;
        boolean memoryWasRecovered = memoryRecovered;
        breakerLine = resolvePlayerAction(breakerAction, breaker, breakerSlot);
        listenerLine = resolvePlayerAction(listenerAction, listener, listenerSlot);
        boolean memoryRecoveredThisTurn = !memoryWasRecovered && memoryRecovered;
        if (!memoryRecoveredThisTurn) advanceStateIfNeeded();

        boolean authorizationRequested = listenerAction == PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT;
        if (authorizationRequested) {
            logAuthorizationValidation();
            if (isAuthorizationAllowed()) beginRestoration("defenses cleared and memory recovered");
        }
    }

    private void logAuthorizationValidation() {
        Gdx.app.log("Level3EndingTrace", "Before Restore Authorization: remaining drones="
            + drone.getActiveCount() + " remaining turrets=" + turret.getActiveCount()
            + " memoryRecovered=" + memoryRecovered
            + " authorizationAllowed=" + isAuthorizationAllowed()
            + " hostility=" + warden.getDirectiveConflict()
            + " state=" + warden.getState());
    }

    private void beginRestoration(String reason) {
        if (warden.getState() == WardenState.DUAL_AUTHORIZATION || warden.getState().isStoodDown()) return;
        WardenState before = warden.getState();
        warden.setDualMeter(DUAL_CAP);
        warden.setState(WardenState.DUAL_AUTHORIZATION);
        wardenDamaged = false;
        queueBanner(BANNER_DUAL);
        standDownTimer = STAND_DOWN_DELAY;
        Gdx.app.log("Level3EndingTrace", "After authorization success (" + reason
            + "): wardenState before=" + before + " wardenState after=" + warden.getState()
            + " endingReady=" + endingReady + " hostility=" + warden.getDirectiveConflict());
    }

    private void advanceStateIfNeeded() {
        WardenState state = warden.getState();
        if (state == WardenState.DEFENSE_ACTIVE
            && (warden.getStability() >= STABILITY_MEMORY
                || warden.getDirectiveConflict() <= DIRECTIVE_MEMORY)) {
            warden.setState(WardenState.MEMORY_RECOVERY);
            wardenDamaged = false;
            queueBanner(BANNER_MEMORY);
        } else if (state == WardenState.MEMORY_RECOVERY
            && (warden.getStability() >= STABILITY_CONFLICT
                || warden.getDirectiveConflict() <= DIRECTIVE_CONFLICT)) {
            warden.setState(WardenState.DIRECTIVE_CONFLICT);
            wardenDamaged = false;
            queueBanner(BANNER_CONFLICT);
        }
    }

    private String resolvePlayerAction(PlayerActionType action, Player self, int inventorySlot) {
        if (action == null) return "";
        String name = callSignOf(self);
        switch (action) {
            case BREAKER_PHYSICAL_STRIKE: {
                self.startAttack();
                boolean hitDefense = damageDefense(15f);
                boolean hitWarden = !hitDefense && strikeWarden();
                boolean hit = hitDefense || hitWarden;
                addProgress(hit ? 5f : 3f, 4f);
                return name + " " + action.flavorVerb()
                    + (hitDefense ? "; the unit recoils from the impact."
                        : hitWarden ? "; the Warden's outer shell absorbs the blow."
                        : ", but no target remains in reach.");
            }
            case BREAKER_WEAPON_ATTACK:
            case LISTENER_WEAPON_ATTACK: {
                self.startShooting();
                int side = self == player1 ? 1 : 2;
                InventoryItem selected = itemAt(inventories, side, inventorySlot);
                if (!itemSupportsAction(action, selected) || !hasSidearm(inventories, gun, side)
                    || !gun.useTurnBasedRound()) {
                    return name + " finds the recovered sidearm empty.";
                }
                float damage = gun.turnBasedDamage();
                boolean hitDefense = damageDefense(damage);
                boolean hitWarden = !hitDefense && strikeWarden();
                boolean hit = hitDefense || hitWarden;
                gun.showTurnBasedShot(self, warden.centreX(), warden.centreY() - 60f, hit);
                addProgress(hit ? (gun.hasAmmoCache() ? 8f : 6f) : 3f, 5f);
                return name + " " + action.flavorVerb()
                    + (gun.hasAmmoCache() ? " with the recovered ammo cache's high-output load" : "")
                    + (hitDefense ? "; the targeted unit buckles."
                        : hitWarden ? "; the Warden's armor sparks but holds."
                        : ", but no target remains online.");
            }
            case BREAKER_DISABLE_DRONE: {
                self.startAttack();
                boolean hit = damageDrone(30f);
                addProgress((hit ? 7f : 3f) + (breakerSupported ? 3f : 0f), 6f);
                return name + " " + action.flavorVerb()
                    + (hit ? ", and it powers down." : ", but finds no deployed drone.");
            }
            case BREAKER_REPAIR_MECHANISM:
                self.startAttack();
                addProgress(9f + (breakerSupported ? 3f : 0f), 8f);
                return name + " " + action.flavorVerb() + ".";
            case BREAKER_PROTECT_LISTENER:
                self.startAttack();
                listenerProtected = true;
                addProgress(4f, 5f);
                return name + " " + action.flavorVerb() + ".";
            case BREAKER_SHIELD_DEFENSE:
            case LISTENER_SHIELD_DEFENSE:
                self.startShielding();
                return useShield(self, inventorySlot, action);
            case LISTENER_SCAN_WARDEN:
                self.startAttack();
                addProgress(7f, 6f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_REDUCE_SUBROUTINE:
                self.startAttack();
                addProgress(8f, 9f);
                nextWardenDamageMultiplier = 0.6f;
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_RECOVER_LOGS:
                self.startAttack();
                if (!defensesCleared()) {
                    return name + " cannot recover the Warden's memory while defenses remain online.";
                }
                if (memoryRecovered) return name + " confirms the recovered Warden memory.";
                memoryRecovered = true;
                addProgress(6f, 7f);
                if (warden.getState() == WardenState.DEFENSE_ACTIVE) {
                    warden.setState(WardenState.MEMORY_RECOVERY);
                    wardenDamaged = false;
                    queueBanner(BANNER_MEMORY);
                } else {
                    queueBanner(BANNER_LOG);
                }
                Gdx.app.log("Level3EndingTrace", "Recover Memory succeeded memoryRecovered=true"
                    + " state=" + warden.getState());
                return name + " recovers the Warden's memory record. Authorization is now available.";
            case LISTENER_AUTHORIZATION_ATTEMPT:
                self.startAttack();
                addProgress(6f, 5f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_SUPPORT_BREAKER:
                self.startAttack();
                addProgress(4f, 4f);
                return name + " " + action.flavorVerb() + ".";
            case USE_MEDKIT:
                self.startAttack();
                return useMedkit(self, inventorySlot);
            case USE_ITEM:
                return useItem(self);
            default:
                return "";
        }
    }

    private void addProgress(float stability, float conflictReduction) {
        warden.setStability(warden.getStability() + stability);
        warden.setDirectiveConflict(warden.getDirectiveConflict() - conflictReduction);
    }

    // The Warden is not a health-bar boss. This is only the visual reaction to a direct physical
    // hit after its deployed defenses are clear; narrative progress and meter changes never call it.
    private boolean strikeWarden() {
        if (warden.getState().isStoodDown()) return false;
        warden.playDamagedFlash();
        wardenDamaged = true;
        return true;
    }

    private boolean damageDefense(float damage) {
        return drone.isActive() ? damageDrone(damage) : damageTurret(damage);
    }

    private boolean defensesCleared() {
        return drone.getActiveCount() == 0 && turret.getActiveCount() == 0;
    }

    private boolean damageDrone(float damage) {
        int before = drone.getActiveCount();
        droneDamagedIndex = drone.damage(damage);
        if (droneDamagedIndex >= 0) {
            if (drone.getActiveCount() < before) {
                Gdx.app.log("DefenseDroneTrace", "destroyed unit=" + droneDamagedIndex);
            }
            Gdx.app.log("Level3EndingTrace", "remaining drones=" + drone.getActiveCount()
                + " remaining turrets=" + turret.getActiveCount());
        }
        return droneDamagedIndex >= 0 && (before > 0);
    }

    private boolean damageTurret(float damage) {
        int before = turret.getActiveCount();
        turretDamagedIndex = turret.damage(damage);
        turretDestroyed = turretDamagedIndex >= 0 && turret.getActiveCount() < before;
        if (turretDamagedIndex >= 0) {
            if (turretDestroyed) {
                Gdx.app.log("SecurityTurretTrace", "destroyed unit=" + turretDamagedIndex);
            }
            Gdx.app.log("Level3EndingTrace", "remaining drones=" + drone.getActiveCount()
                + " remaining turrets=" + turret.getActiveCount());
        }
        return turretDamagedIndex >= 0 && (before > 0);
    }

    private String useItem(Player self) {
        int side = self == player1 ? 1 : 2;
        int medkitSlot = firstCompatibleSlot(inventories, side, PlayerActionType.USE_MEDKIT);
        if (medkitSlot >= 0) return useMedkit(self, medkitSlot);
        return useShield(self);
    }

    private String useMedkit(Player self, int slot) {
        int side = self == player1 ? 1 : 2;
        InventoryItem item = itemAt(inventories, side, slot);
        String name = callSignOf(self);
        if (!itemSupportsAction(PlayerActionType.USE_MEDKIT, item)) {
            return name + " has no selected medical supplies.";
        }
        self.heal(item.getHealAmount());
        removeItemAt(side, slot);
        recordConsumedSlot(side, slot);
        return name + " uses the " + item.getName() + " and restores health.";
    }

    private String useShield(Player self) {
        int side = self == player1 ? 1 : 2;
        int slot = firstCompatibleSlot(inventories, side,
            roleForSide(side) == Role.BREAKER
                ? PlayerActionType.BREAKER_SHIELD_DEFENSE
                : PlayerActionType.LISTENER_SHIELD_DEFENSE);
        return slot >= 0
            ? useShield(self, slot, roleForSide(side) == Role.BREAKER
                ? PlayerActionType.BREAKER_SHIELD_DEFENSE
                : PlayerActionType.LISTENER_SHIELD_DEFENSE)
            : callSignOf(self) + " has no usable gear.";
    }

    private String useShield(Player self, int slot, PlayerActionType action) {
        int side = self == player1 ? 1 : 2;
        InventoryItem item = itemAt(inventories, side, slot);
        String name = callSignOf(self);
        if (!itemSupportsAction(action, item)) {
            return name + " has no selected shield equipment.";
        }
        removeItemAt(side, slot);
        recordConsumedSlot(side, slot);
        if (side == 1) p1Braced = true;
        else p2Braced = true;
        return name + " braces with the " + item.getName() + ".";
    }

    private void removeItemAt(int side, int slot) {
        if (slot == SHARED_SLOT_INDEX) inventories.removeSharedItem();
        else inventories.forPlayer(side).remove(slot);
    }

    private static boolean hasNamedItem(PlayerInventories inventories, int side, String name) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && name.equalsIgnoreCase(item.getName())) return true;
        }
        InventoryItem shared = inventories.sharedItem();
        return shared != null && name.equalsIgnoreCase(shared.getName());
    }

    private void recordConsumedSlot(int side, int slot) {
        if (side == 1) p1ConsumedSlot = slot;
        else p2ConsumedSlot = slot;
    }

    private static boolean isShieldItem(InventoryItem item) {
        if (item == null) return false;
        String name = item.getName();
        return "Shield".equalsIgnoreCase(name) || "Shield Cell".equalsIgnoreCase(name)
            || "Rare Plating".equalsIgnoreCase(name);
    }

    private void resolveWardenAction() {
        if (warden.getState() == WardenState.DUAL_AUTHORIZATION || warden.getState().isStoodDown()) {
            wardenLine = "The Warden holds position.";
            return;
        }
        if (defensesCleared()) {
            wardenLine = memoryRecovered
                ? "The Warden's defenses are offline; recovered memory awaits authorization."
                : "The Warden's defenses are offline; its memory archive is exposed.";
            return;
        }

        // Existing deployed defenses get the first response. This keeps the original Warden turn
        // order, while making the units already visible in the arena participate every round.
        if (drone.isActive()) {
            openDroneReaction();
            return;
        }
        Player detectedTarget = detectedTurretTarget();
        if (detectedTarget != null) {
            openTurretReaction(detectedTarget);
            return;
        }

        WardenActionType action = rolledWardenAction;
        switch (action) {
            case DEPLOY_DRONE: {
                drone.spawn();
                openDroneReaction();
                break;
            }
            case ACTIVATE_TURRET: {
                turret.activate();
                if (turret.isActive()) {
                    Player target = detectedTurretTarget();
                    if (target != null) openTurretReaction(target);
                    else wardenLine = "The security turrets rotate, but no operator enters detection range.";
                } else {
                    wardenLine = "The Warden attempts to activate a turret, but none remain online.";
                }
                break;
            }
            case DEFENSIVE_SCAN:
                warden.setStability(warden.getStability() - 2f);
                warden.setDirectiveConflict(warden.getDirectiveConflict() + 3f);
                wardenLine = "The Warden " + action.flavorVerb() + ".";
                break;
            case RAISE_CONTAINMENT_BARRIERS:
                warden.setStability(warden.getStability() - 3f);
                warden.setDirectiveConflict(warden.getDirectiveConflict() + 5f);
                warden.playAttackFlash();
                wardenAttacked = true;
                wardenLine = "The Warden " + action.flavorVerb()
                    + "; access routes lock behind red light.";
                break;
            case INCREASE_CONTAINMENT_WARNING:
                warden.playAttackFlash();
                wardenAttacked = true;
                applyWardenDamage(player1, 6f);
                applyWardenDamage(player2, 6f);
                wardenLine = "The Warden " + action.flavorVerb() + "; the floor lights red.";
                break;
            default:
                break;
        }
    }

    private void openDroneReaction() {
        int unit = drone.lastActiveUnit();
        if (unit < 0) return;
        Player target = nearestValidPlayer(drone.unitX(unit), drone.unitY(unit));
        if (target == null) return;
        reactionOpen = true;
        reactionAttackType = EnemyAttackType.DRONE;
        reactionTargetSide = target == player1 ? 1 : 2;
        reactionUnitIndex = unit;
        reactionBlocksDamage = false;
        pendingDroneTarget = null;
        pendingDroneDamageTimer = -1f;
        wardenLine = "A defense drone targets " + callSignOf(target) + ". Choose a reaction.";
        Gdx.app.log("DefenseDroneTrace", "reaction opened for player " + callSignOf(target)
            + " because drone " + reactionUnitIndex + " targeted player");
    }

    private Player detectedTurretTarget() {
        int p1Unit = turret.detectingUnit(player1.centreX(), player1.centreY());
        int p2Unit = turret.detectingUnit(player2.centreX(), player2.centreY());
        if (p1Unit < 0 && p2Unit < 0) return null;
        if (p1Unit < 0) return player2;
        if (p2Unit < 0) return player1;
        float p1Distance = turret.distanceSquaredToUnit(p1Unit, player1.centreX(), player1.centreY());
        float p2Distance = turret.distanceSquaredToUnit(p2Unit, player2.centreX(), player2.centreY());
        return p1Distance <= p2Distance ? player1 : player2;
    }

    private void openTurretReaction(Player target) {
        int unit = turret.detectingUnit(target.centreX(), target.centreY());
        if (unit < 0) {
            logTurretRangeMiss();
            return;
        }
        reactionOpen = true;
        reactionAttackType = EnemyAttackType.TURRET;
        reactionTargetSide = target == player1 ? 1 : 2;
        reactionUnitIndex = unit;
        reactionBlocksDamage = false;
        pendingTurretTarget = null;
        pendingTurretDamageTimer = -1f;
        wardenLine = "A security turret detects " + callSignOf(target) + ". Choose a reaction.";
        Gdx.app.log("SecurityTurretTrace", "reaction opened for player " + callSignOf(target)
            + " because turret " + unit + " targeted player");
    }

    private Player nearerDetectedPlayer() {
        Player target = detectedTurretTarget();
        return target == null ? lowerHealth() : target;
    }

    private Player nearestValidPlayer(float x, float y) {
        boolean p1Valid = player1.health > 0f;
        boolean p2Valid = player2.health > 0f;
        if (!p1Valid && !p2Valid) return null;
        if (!p1Valid) return player2;
        if (!p2Valid) return player1;
        return distanceSquared(player1, x, y) <= distanceSquared(player2, x, y) ? player1 : player2;
    }

    private static float distanceSquared(Player player, float x, float y) {
        float dx = player.centreX() - x;
        float dy = player.centreY() - y;
        return dx * dx + dy * dy;
    }

    private void logTurretRangeMiss() {
        Gdx.app.log("SecurityTurretTrace", "reaction ignored because player distance > radius"
            + " p1Distance=" + turret.nearestActiveDistance(player1.centreX(), player1.centreY())
            + " p2Distance=" + turret.nearestActiveDistance(player2.centreX(), player2.centreY())
            + " radius=" + SecurityTurretController.DETECTION_RADIUS);
    }

    private void startReactionAttack() {
        Player target = reactionTargetSide == 1 ? player1 : player2;
        if (reactionAttackType == EnemyAttackType.DRONE && drone.isUnitActive(reactionUnitIndex)) {
            drone.playAttackFlash(reactionUnitIndex);
            warden.playAttackFlash();
            droneAttacked = true;
            wardenAttacked = true;
            pendingDroneTarget = target;
            pendingDroneDamageTimer = DefenseDroneController.ATTACK_IMPACT_TIME;
            wardenLine = "The defense drone lunges at " + callSignOf(target) + ".";
            Gdx.app.log("DefenseDroneTrace", "attack started unit=" + reactionUnitIndex
                + " targetSide=" + reactionTargetSide + " impactIn=" + pendingDroneDamageTimer);
        } else if (reactionAttackType == EnemyAttackType.TURRET
            && turret.isUnitActive(reactionUnitIndex)) {
            turret.playAiming(reactionUnitIndex, target.centreX(), target.centreY());
            warden.playAttackFlash();
            turretAiming = true;
            wardenAttacked = true;
            pendingTurretTarget = target;
            pendingTurretDamageTimer = SecurityTurretController.AIM_DURATION
                + SecurityTurretController.FIRE_IMPACT_TIME;
            wardenLine = "The security turret aims at " + callSignOf(target) + ".";
            Gdx.app.log("SecurityTurretTrace", "aim started unit=" + reactionUnitIndex
                + " targetSide=" + reactionTargetSide);
        }
    }

    private boolean damageReactionDrone(float amount) {
        int before = drone.getActiveCount();
        droneDamagedIndex = drone.damage(reactionUnitIndex, amount);
        boolean destroyed = droneDamagedIndex >= 0 && drone.getActiveCount() < before;
        if (destroyed) {
            Gdx.app.log("DefenseDroneTrace", "destroyed unit=" + droneDamagedIndex);
        }
        return droneDamagedIndex >= 0;
    }

    private boolean damageReactionTurret(float amount) {
        int before = turret.getActiveCount();
        turretDamagedIndex = turret.damage(reactionUnitIndex, amount);
        turretDestroyed = turretDamagedIndex >= 0 && turret.getActiveCount() < before;
        if (turretDestroyed) {
            Gdx.app.log("SecurityTurretTrace", "destroyed unit=" + turretDamagedIndex);
        }
        return turretDamagedIndex >= 0;
    }

    private boolean reactionSourceActive() {
        return reactionAttackType == EnemyAttackType.DRONE
            ? drone.isUnitActive(reactionUnitIndex)
            : reactionAttackType == EnemyAttackType.TURRET
                && turret.isUnitActive(reactionUnitIndex);
    }

    private void clearReactionAttack() {
        reactionOpen = false;
        reactionAttackType = null;
        reactionUnitIndex = -1;
        reactionBlocksDamage = false;
        pendingDroneTarget = null;
        pendingDroneDamageTimer = -1f;
        pendingTurretTarget = null;
        pendingTurretDamageTimer = -1f;
    }

    private void updatePendingTurretAttack(float delta) {
        if (pendingTurretTarget == null || pendingTurretDamageTimer <= 0f) return;
        pendingTurretDamageTimer -= delta;
        if (pendingTurretDamageTimer > 0f) return;

        Player target = pendingTurretTarget;
        pendingTurretTarget = null;
        pendingTurretDamageTimer = -1f;
        Gdx.app.log("SecurityTurretTrace", "applying impact to " + callSignOf(target)
            + " after FIRING began");
        if (reactionBlocksDamage) {
            Gdx.app.log("SecurityTurretTrace", "damage blocked targetSide=" + reactionTargetSide);
        } else {
            applyWardenDamage(target, 14f);
            damageAppliedTargetSide = reactionTargetSide;
            Gdx.app.log("SecurityTurretTrace", "damage applied targetSide=" + reactionTargetSide
                + " health=" + target.health);
        }
        clearReactionAttack();
        // The initial Warden-turn broadcast starts the client animation. This second snapshot
        // commits the resulting health change without replaying that animation.
        broadcast();
    }

    private void updatePendingDroneAttack(float delta) {
        if (pendingDroneTarget == null || pendingDroneDamageTimer <= 0f) return;
        pendingDroneDamageTimer -= delta;
        if (pendingDroneDamageTimer > 0f) return;

        Player target = pendingDroneTarget;
        pendingDroneTarget = null;
        pendingDroneDamageTimer = -1f;
        if (reactionBlocksDamage) {
            Gdx.app.log("DefenseDroneTrace", "damage blocked targetSide=" + reactionTargetSide);
        } else {
            applyWardenDamage(target, 9f);
            damageAppliedTargetSide = reactionTargetSide;
            Gdx.app.log("DefenseDroneTrace", "damage applied targetSide=" + reactionTargetSide
                + " health=" + target.health);
        }
        clearReactionAttack();
        // The attack event already started the animation on both peers; this only commits damage.
        broadcast();
    }

    private void applyWardenDamage(Player target, float base) {
        float damage = base * nextWardenDamageMultiplier;
        boolean braced = target == player1 ? p1Braced : p2Braced;
        if (braced) damage *= 0.5f;
        if (listenerProtected && roleOf(target) == Role.LISTENER) damage *= 0.5f;
        target.takeDamage(damage);
        target.playDamagedFeedback();
    }

    private void rollWardenAction() {
        if (warden.getState() == WardenState.DIRECTIVE_CONFLICT) {
            rolledWardenAction = (wardenActionCursor++ % 2 == 0)
                ? WardenActionType.INCREASE_CONTAINMENT_WARNING
                : WardenActionType.DEFENSIVE_SCAN;
            return;
        }
        WardenActionType[] actions = {
            WardenActionType.DEPLOY_DRONE,
            WardenActionType.ACTIVATE_TURRET,
            WardenActionType.DEFENSIVE_SCAN,
            WardenActionType.INCREASE_CONTAINMENT_WARNING
        };
        rolledWardenAction = actions[wardenActionCursor % actions.length];
        wardenActionCursor++;
    }

    private Player higherHealth() { return player1.health >= player2.health ? player1 : player2; }
    private Player lowerHealth() { return player1.health <= player2.health ? player1 : player2; }
    private Role roleForSide(int side) { return side == 1 ? sideOneRole : sideOneRole.other(); }
    private Role roleOf(Player player) { return player == player1 ? sideOneRole : sideOneRole.other(); }
    private String callSignOf(Player player) { return roleOf(player).callSign(); }

    private void queueBanner(int id) {
        bannerSeq++;
        pendingBannerId = id;
    }

    private void broadcast() {
        PlayerActionType p1Action = turnManager.getP1Action();
        PlayerActionType p2Action = turnManager.getP2Action();
        int turretTargetSide = pendingTurretTarget == player1 ? 1
            : pendingTurretTarget == player2 ? 2 : 0;
        if (hostSession != null) {
            hostSession.send(new Level3TurnStateMessage(turnManager.getPhase(), warden.getState(),
                warden.getStability(), warden.getDualMeter(), drone.isActive(), turret.isActive(),
                player1.health, player2.health, bannerSeq, pendingBannerId, wardenLine, breakerLine, listenerLine,
                wardenAttacked, wardenDamaged, droneAttacked, turretAttacked,
                p1Action == null ? -1 : p1Action.ordinal(), p2Action == null ? -1 : p2Action.ordinal(),
                p1ConsumedSlot, p2ConsumedSlot, warden.getDirectiveConflict(),
                drone.getActiveCount(), turret.getActiveCount(), droneDamagedIndex, turretDamagedIndex,
                turretDestroyed, turretAiming, turretTargetSide, memoryRecovered,
                reactionOpen, reactionAttackType == null ? -1 : reactionAttackType.ordinal(),
                reactionTargetSide, reactionSelectionOrdinal, damageAppliedTargetSide,
                reactionUnitIndex, restorationStarted, restorationCompleted));
            if (gun != null) hostSession.send(gun.toMessage());
        }
        wardenAttacked = false;
        wardenDamaged = false;
        droneAttacked = false;
        turretAiming = false;
        turretAttacked = false;
        droneDamagedIndex = -1;
        turretDamagedIndex = -1;
        turretDestroyed = false;
        reactionSelectionOrdinal = -1;
        damageAppliedTargetSide = 0;
        restorationStarted = false;
        restorationCompleted = false;
        p1ConsumedSlot = -1;
        p2ConsumedSlot = -1;
    }

    public void reset() {
        turnManager.reset();
        warden.reset();
        drone.reset();
        turret.reset();
        rolledWardenAction = WardenActionType.DEPLOY_DRONE;
        wardenActionCursor = 0;
        nextWardenDamageMultiplier = 1f;
        p1Braced = false;
        p2Braced = false;
        listenerProtected = false;
        breakerSupported = false;
        pendingDroneTarget = null;
        pendingDroneDamageTimer = -1f;
        pendingTurretTarget = null;
        pendingTurretDamageTimer = -1f;
        standDownTimer = -1f;
        endingTimer = -1f;
        endingReady = false;
        memoryRecovered = false;
        reactionOpen = false;
        reactionAttackType = null;
        reactionTargetSide = 0;
        reactionUnitIndex = -1;
        reactionBlocksDamage = false;
        reactionSelectionOrdinal = -1;
        damageAppliedTargetSide = 0;
        p1InsideTurretRadius = false;
        p2InsideTurretRadius = false;
        turretRangeMissLogged = false;
        defenseRestorationInProgress = false;
        restorationStarted = false;
        restorationCompleted = false;
        bannerSeq = 0;
        pendingBannerId = -1;
        wardenLine = "";
        breakerLine = "";
        listenerLine = "";
        wardenAttacked = false;
        wardenDamaged = false;
        droneAttacked = false;
        turretAiming = false;
        turretAttacked = false;
        droneDamagedIndex = -1;
        turretDamagedIndex = -1;
        turretDestroyed = false;
        p1ConsumedSlot = -1;
        p2ConsumedSlot = -1;
        p1SelectedItemSlot = -1;
        p2SelectedItemSlot = -1;
        broadcast();
    }

    public void dispose() {
        warden.dispose();
        drone.dispose();
        turret.dispose();
    }
}
