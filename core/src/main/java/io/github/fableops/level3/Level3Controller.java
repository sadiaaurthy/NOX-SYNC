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
    private static final int DRONE_COUNT = 4;
    private static final int TURRET_COUNT = 4;
    private static final float TURRET_DAMAGE = 14f;
    private static final float RANGE_TRACE_INTERVAL = 2f;
    // One Side Arm hit destroys a Security Turret (MAX_HEALTH 30). Kept above that on purpose so a
    // later change to turret HP does not quietly turn this back into a multi-shot fight. Applies
    // only to Side Arm damage against turrets; the drone and every other weapon value are unchanged.
    private static final float TURRET_SIDEARM_DAMAGE = 50f;

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
    private float standDownTimer = -1f;
    private float endingTimer = -1f;
    private boolean endingReady;
    // recoverMemoryCompleted: set as soon as the Listener confirms Recover Memory with the defenses down.
    private boolean memoryRecovered;
    // The turn effects of Recover Memory (progress, Warden state, banner) ran; once, at resolution.
    private boolean memoryEffectsApplied;
    private boolean reactionOpen;
    private EnemyAttackType reactionAttackType;
    private int reactionTargetSide;
    private int reactionUnitIndex = -1;
    private boolean reactionBlocksDamage;
    private int reactionSelectionOrdinal = -1;
    private int damageAppliedTargetSide;
    // Set while a story overlay covers the arena: no new turret engagements begin, shots already
    // in flight still land.
    private boolean turretAiPaused;
    // Set only when the targeted player completes a Side Arm / Shield / Medkit reaction (or has
    // nothing to prepare). Cleared when that player is out of every turret's range. Index = side - 1.
    private final boolean[] playerCombatReady = new boolean[2];
    // A Shield reaction blocks the next turret hit on that player. Index = side - 1.
    private final boolean[] turretShieldBlock = new boolean[2];
    // The single turret currently allowed to attack (at most one attacker at a time, so at most one
    // per player); other turrets in range stay ALERTED/WAITING.
    private int turretCombatUnit = -1;
    private float traceRangeTimer;
    // Which unit/target the next broadcast announces as "just started tracking".
    private int turretEventUnit = -1;
    private int turretEventSide;
    private final SecurityTurretController.AnimationState[] tracedTurretState =
        new SecurityTurretController.AnimationState[SecurityTurretController.UNIT_COUNT];
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
        Arrays.fill(tracedTurretState, SecurityTurretController.AnimationState.IDLE_ROTATING);
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

    public void setTurretAiPaused(boolean paused) { turretAiPaused = paused; }

    public boolean isPlayerCombatReady(int side) {
        return (side == 1 || side == 2) && playerCombatReady[side - 1];
    }

    // Each turret is a stationary enemy that measures its own distance to each player.
    //   IDLE -> ALERTED -> WAITING_FOR_PLAYER_RESPONSE -> COMBAT_READY -> AIMING -> FIRING
    // Alert and waiting come from range alone (SecurityTurretController.updateAlert) and never fire.
    // A waiting turret opens a reaction for its target; only when that player has prepared does the
    // host release it (beginTurretCombat). Damage lands once, at the impact frame, then cools down.
    private void updateTurretAI(float delta) {
        traceRangeTimer -= delta;
        if (traceRangeTimer <= 0f) {
            traceRangeTimer = RANGE_TRACE_INTERVAL;
            traceRange(1, player1);
            traceRange(2, player2);
        }
        boolean hostile = !warden.getState().isStoodDown()
            && warden.getState() != WardenState.DUAL_AUTHORIZATION;
        boolean detected1 = false;
        boolean detected2 = false;
        for (int i = 0; i < SecurityTurretController.UNIT_COUNT; i++) {
            traceTurretState(i);
            detected1 |= turretInRange(i, player1);
            detected2 |= turretInRange(i, player2);
        }
        if (!detected1) resetPlayerPreparation(1);
        if (!detected2) resetPlayerPreparation(2);

        if (reactionOpen && reactionAttackType == EnemyAttackType.TURRET
            && turret.stateOf(reactionUnitIndex) != SecurityTurretController.AnimationState.WAITING) {
            // The target left the range, or the turret was hit or destroyed, before any response.
            Gdx.app.log("SecurityTurretTrace", "alert cancelled turretID=" + reactionUnitIndex);
            clearReactionAttack();
            broadcast();
        }
        for (int i = 0; i < SecurityTurretController.UNIT_COUNT; i++) {
            int impactSide = turret.consumeImpactSide(i);
            if (impactSide != 0) applyTurretImpact(i, impactSide, hostile);
        }
        if (turretCombatUnit >= 0 && !turret.isInCombat(turretCombatUnit)) turretCombatUnit = -1;
        allocateTurretAttack(hostile);
    }

    // Periodic proof that far-away players are not detected: nearest turret, distance, range.
    private void traceRange(int side, Player player) {
        int nearest = -1;
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < SecurityTurretController.UNIT_COUNT; i++) {
            float d = turret.distanceSquaredToUnit(i, player.centreX(), player.centreY());
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        if (nearest < 0) return;
        float distance = (float) Math.sqrt(best);
        Gdx.app.log("SecurityTurretTrace", "range check turretID=" + nearest + " playerID=P" + side
            + " distance=" + Math.round(distance)
            + " attackRange=" + Math.round(SecurityTurretController.TURRET_ATTACK_RANGE)
            + " currentState=" + SecurityTurretController.traceName(turret.stateOf(nearest))
            + " detected=" + (distance <= SecurityTurretController.TURRET_ATTACK_RANGE));
    }

    private boolean turretInRange(int unit, Player player) {
        if (player.health <= 0f) return false;
        float limit = SecurityTurretController.TURRET_ATTACK_RANGE
            * SecurityTurretController.TURRET_ATTACK_RANGE;
        return turret.distanceSquaredToUnit(unit, player.centreX(), player.centreY()) <= limit;
    }

    private void resetPlayerPreparation(int side) {
        if (!playerCombatReady[side - 1] && !turretShieldBlock[side - 1]) return;
        playerCombatReady[side - 1] = false;
        turretShieldBlock[side - 1] = false;
        Gdx.app.log("SecurityTurretTrace", "playerID=P" + side
            + " left every turret range: playerCombatReady=false");
    }

    // At most one turret attacks at a time. The first waiting turret either attacks (its target
    // already prepared) or opens the alert reaction; the rest keep waiting their turn.
    private void allocateTurretAttack(boolean hostile) {
        if (!hostile || turretAiPaused || turretCombatUnit >= 0 || reactionOpen
            || pendingDroneDamageTimer > 0f) return;
        for (int i = 0; i < SecurityTurretController.UNIT_COUNT; i++) {
            if (turret.stateOf(i) != SecurityTurretController.AnimationState.WAITING) continue;
            int side = turret.targetSide(i);
            Player target = side == 1 ? player1 : side == 2 ? player2 : null;
            if (target == null || target.health <= 0f) continue;
            if (playerCombatReady[side - 1]) beginTurretCombat(i, side);
            else openTurretAlertReaction(i, side, target);
            return;
        }
    }

    private void openTurretAlertReaction(int unit, int side, Player target) {
        reactionOpen = true;
        reactionAttackType = EnemyAttackType.TURRET;
        reactionTargetSide = side;
        reactionUnitIndex = unit;
        reactionBlocksDamage = false;
        wardenLine = "A security turret has locked onto " + callSignOf(target)
            + ". Prepare Side Arm, Shield or Medkit.";
        Gdx.app.log("SecurityTurretTrace", "waiting for player response turretID=" + unit
            + " playerID=P" + side + " playerCombatReady=false");
        broadcast();
    }

    private void beginTurretCombat(int unit, int side) {
        turret.beginCombat(unit, side);
        if (!turret.isInCombat(unit)) return;
        turretCombatUnit = unit;
        turretAiming = true;
        turretEventUnit = unit;
        turretEventSide = side;
        traceTurretState(unit);
        broadcast();
    }

    private void applyTurretImpact(int unit, int side, boolean hostile) {
        Player target = side == 1 ? player1 : player2;
        boolean blocked = turretShieldBlock[side - 1];
        turretShieldBlock[side - 1] = false;
        boolean applied = hostile && !blocked && target.health > 0f;
        if (applied) {
            applyWardenDamage(target, TURRET_DAMAGE);
            damageAppliedTargetSide = side;
        }
        if (unit == turretCombatUnit) turretCombatUnit = -1;
        traceTurret(unit, side, "TURRET_IMPACT" + (blocked ? " (shield blocked)" : ""), applied);
        // Commits the health change; the client already started the animation itself.
        if (applied) broadcast();
    }

    private boolean hasReactionEquipment(int side) {
        Inventory inventory = inventories.forPlayer(side);
        PlayerActionType weapon = roleForSide(side) == Role.BREAKER
            ? PlayerActionType.BREAKER_WEAPON_ATTACK : PlayerActionType.LISTENER_WEAPON_ATTACK;
        PlayerActionType shield = roleForSide(side) == Role.BREAKER
            ? PlayerActionType.BREAKER_SHIELD_DEFENSE : PlayerActionType.LISTENER_SHIELD_DEFENSE;
        for (int slot = 0; slot < Inventory.CAPACITY; slot++) {
            InventoryItem item = inventory.get(slot);
            if ((gun != null && gun.hasAmmo() && itemSupportsAction(weapon, item))
                || itemSupportsAction(shield, item)
                || itemSupportsAction(PlayerActionType.USE_MEDKIT, item)) return true;
        }
        return false;
    }

    private void traceTurretState(int unit) {
        SecurityTurretController.AnimationState state = turret.stateOf(unit);
        if (state == tracedTurretState[unit]) return;
        tracedTurretState[unit] = state;
        if (state == SecurityTurretController.AnimationState.IDLE_ROTATING
            || state == SecurityTurretController.AnimationState.ALERTED
            || state == SecurityTurretController.AnimationState.WAITING
            || state == SecurityTurretController.AnimationState.COMBAT_READY
            || state == SecurityTurretController.AnimationState.AIMING
            || state == SecurityTurretController.AnimationState.FIRING) {
            traceTurret(unit, turret.targetSide(unit), SecurityTurretController.traceName(state), false);
        }
    }

    private void traceTurret(int unit, int side, String state, boolean damageApplied) {
        Player target = side == 1 ? player1 : side == 2 ? player2 : null;
        float distanceSquared = target == null ? Float.POSITIVE_INFINITY
            : turret.distanceSquaredToUnit(unit, target.centreX(), target.centreY());
        String distance = Float.isInfinite(distanceSquared) ? "n/a"
            : String.valueOf(Math.round(Math.sqrt(distanceSquared)));
        Gdx.app.log("SecurityTurretTrace", "turretID=" + unit
            + " playerID=" + (side == 0 ? "none" : "P" + side)
            + " distance=" + distance
            + " attackRange=" + Math.round(SecurityTurretController.TURRET_ATTACK_RANGE)
            + " currentState=" + state
            + " playerCombatReady=" + isPlayerCombatReady(side)
            + " damageApplied=" + damageApplied);
    }

    // The three checks the Restore Authorization flow hangs on. Menu rows, action validation, the
    // ENTER confirmation and the HUD hint all read these, so they can never disagree.
    public boolean isDefensesCleared() {
        return defensesCleared();
    }

    public boolean isRecoverMemoryCompleted() {
        return memoryRecovered;
    }

    public boolean isAuthorizationUnlocked() {
        return isDefensesCleared() && isRecoverMemoryCompleted();
    }

    public String authorizationLockReason() {
        if (!isDefensesCleared()) return "Restore Authorization requires all defenses disabled";
        if (!isRecoverMemoryCompleted()) return "Restore Authorization requires recovered memory";
        return "";
    }

    public void startEncounter() {
        drone.setActiveCount(DRONE_COUNT);
        turret.setActiveCount(TURRET_COUNT);
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
            if (!isAuthorizationUnlocked()) return;
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
        // The memory is recovered the moment the Listener commits to it, not when the partner's turn
        // finally resolves, so the Turn Menu can offer Restore Authorization straight away. The turn
        // effects (progress, banner) still apply once, at resolution.
        if (action == PlayerActionType.LISTENER_RECOVER_LOGS && isDefensesCleared() && !memoryRecovered) {
            memoryRecovered = true;
            Gdx.app.log("Level3EndingTrace", "Recover Memory confirmed recoverMemoryCompleted=true"
                + " authorizationUnlocked=" + isAuthorizationUnlocked());
        }
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
                // A turret is released only by preparing (X/H/M). With no usable equipment there
                // is nothing to prepare, so taking the hit releases it; otherwise NONE is ignored.
                if (reactionAttackType == EnemyAttackType.TURRET && hasReactionEquipment(side)) return;
                inventorySlot = -1;
                break;
            default:
                return;
        }

        if (reactionAttackType == EnemyAttackType.TURRET) {
            confirmTurretReaction(side, target, reaction, inventorySlot, shieldAction);
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
            float hitX = drone.unitX(reactionUnitIndex);
            float hitY = drone.unitY(reactionUnitIndex);
            boolean hit = damageReactionDrone(gun.turnBasedDamage());
            gun.showTurnBasedShot(target, hitX, hitY, hit);
            setOperatorLine(side, callSignOf(target) + " fires the Sidearm into the incoming attack.");
            Gdx.app.log("Level3ReactionTrace", "Player Sidearm used side=" + side
                + " ammoRemaining=" + gun.getTotalRounds() + " hit=" + hit);
            if (!reactionSourceActive()) {
                wardenLine = "The attacking defense drone is destroyed before impact.";
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

    // The targeted player answered a turret alert. Nothing hits them yet: this only applies the
    // chosen equipment and sets playerCombatReady, which is what lets the waiting turret start.
    private void confirmTurretReaction(int side, Player target, ReactionType reaction,
                                       int inventorySlot, PlayerActionType shieldAction) {
        int unit = reactionUnitIndex;
        if (reaction == ReactionType.SIDEARM && !turretSidearmTargetValid(unit, target)) {
            Gdx.app.log("SecurityTurretTrace", "sidearm rejected turretID=" + unit
                + " (turret inactive or target out of range)");
            return;
        }
        reactionSelectionOrdinal = reaction.ordinal();
        Gdx.app.log("SecurityTurretTrace", "player response side=" + side + " reaction=" + reaction
            + " turretID=" + unit);
        if (reaction == ReactionType.SIDEARM) {
            target.startShooting();
            gun.useTurnBasedRound();
            int before = turret.getActiveCount();
            float hitX = turret.unitX(unit);
            float hitY = turret.unitY(unit);
            turretDamagedIndex = turret.damage(unit, TURRET_SIDEARM_DAMAGE);
            turretDestroyed = turretDamagedIndex >= 0 && turret.getActiveCount() < before;
            gun.showTurnBasedShot(target, hitX, hitY, turretDamagedIndex >= 0);
            setOperatorLine(side, callSignOf(target) + (turretDestroyed
                ? " destroys the locked turret with one Sidearm shot."
                : " opens fire on the locked turret."));
            Gdx.app.log("SecurityTurretTrace", "sidearm hit turretID=" + unit
                + " destroyed=" + turretDestroyed);
        } else if (reaction == ReactionType.SHIELD) {
            target.startShielding();
            setOperatorLine(side, useShield(target, inventorySlot, shieldAction));
            turretShieldBlock[side - 1] = true;
        } else if (reaction == ReactionType.MEDKIT) {
            setOperatorLine(side, useMedkit(target, inventorySlot));
        } else {
            setOperatorLine(side, callSignOf(target) + " has nothing to deploy against the turret.");
        }
        playerCombatReady[side - 1] = true;
        Gdx.app.log("SecurityTurretTrace", "playerID=P" + side + " playerCombatReady=true");
        clearReactionAttack();
        broadcast();
    }

    // The Side Arm may only hit the turret that is locked onto this player: it must exist, be
    // active, and be inside its own attack range (well within the Side Arm's 520 unit reach).
    private boolean turretSidearmTargetValid(int unit, Player target) {
        if (!turret.isUnitActive(unit)) return false;
        float limit = SecurityTurretController.TURRET_ATTACK_RANGE
            * SecurityTurretController.TURRET_ATTACK_RANGE;
        return turret.distanceSquaredToUnit(unit, target.centreX(), target.centreY()) <= limit;
    }

    private void setOperatorLine(int side, String line) {
        if (roleForSide(side) == Role.BREAKER) breakerLine = line;
        else listenerLine = line;
    }

    public void update(float delta) {
        warden.update(delta);
        drone.update(delta);
        turret.setTrackedPlayers(player1.centreX(), player1.centreY(),
            player2.centreX(), player2.centreY());
        turret.update(delta);
        if (defenseRestorationInProgress && drone.isRestorationComplete()
            && turret.isRestorationComplete()) {
            defenseRestorationInProgress = false;
            restorationCompleted = true;
            Gdx.app.log("Level3RestorationTrace", "Defense network restoration completed");
            broadcast();
        }
        updateTurretAI(delta);
        updatePendingDroneAttack(delta);

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
        if (reactionOpen || pendingDroneDamageTimer > 0f || turretCombatUnit >= 0) return;

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
        boolean memoryWasApplied = memoryEffectsApplied;
        breakerLine = resolvePlayerAction(breakerAction, breaker, breakerSlot);
        listenerLine = resolvePlayerAction(listenerAction, listener, listenerSlot);
        boolean memoryRecoveredThisTurn = !memoryWasApplied && memoryEffectsApplied;
        if (!memoryRecoveredThisTurn) advanceStateIfNeeded();

        boolean authorizationRequested = listenerAction == PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT;
        if (authorizationRequested) {
            logAuthorizationValidation();
            if (isAuthorizationUnlocked()) beginRestoration("defenses cleared and memory recovered");
        }
    }

    private void logAuthorizationValidation() {
        Gdx.app.log("Level3EndingTrace", "Before Restore Authorization: remaining drones="
            + drone.getActiveCount() + " remaining turrets=" + turret.getActiveCount()
            + " memoryRecovered=" + memoryRecovered
            + " authorizationAllowed=" + isAuthorizationUnlocked()
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
                int side = self == player1 ? 1 : 2;
                InventoryItem selected = itemAt(inventories, side, inventorySlot);
                if (!itemSupportsAction(action, selected) || !hasSidearm(inventories, gun, side)
                    || !gun.useTurnBasedRound()) {
                    return name + " finds the recovered sidearm empty.";
                }
                float damage = gun.turnBasedDamage();
                boolean hitDefense = drone.isActive()
                    ? damageDrone(damage) : damageTurret(TURRET_SIDEARM_DAMAGE);
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
                boolean hit = damageDrone(30f);
                addProgress((hit ? 7f : 3f) + (breakerSupported ? 3f : 0f), 6f);
                return name + " " + action.flavorVerb()
                    + (hit ? ", and it powers down." : ", but finds no deployed drone.");
            }
            case BREAKER_REPAIR_MECHANISM:
                addProgress(9f + (breakerSupported ? 3f : 0f), 8f);
                return name + " " + action.flavorVerb() + ".";
            case BREAKER_PROTECT_LISTENER:
                listenerProtected = true;
                addProgress(4f, 5f);
                return name + " " + action.flavorVerb() + ".";
            case BREAKER_SHIELD_DEFENSE:
            case LISTENER_SHIELD_DEFENSE:
                return useShield(self, inventorySlot, action);
            case LISTENER_SCAN_WARDEN:
                addProgress(7f, 6f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_REDUCE_SUBROUTINE:
                addProgress(8f, 9f);
                nextWardenDamageMultiplier = 0.6f;
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_RECOVER_LOGS:
                if (!defensesCleared()) {
                    return name + " cannot recover the Warden's memory while defenses remain online.";
                }
                if (memoryEffectsApplied) return name + " confirms the recovered Warden memory.";
                memoryRecovered = true;
                memoryEffectsApplied = true;
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
                addProgress(6f, 5f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_SUPPORT_BREAKER:
                addProgress(4f, 4f);
                return name + " " + action.flavorVerb() + ".";
            case USE_MEDKIT:
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

        // Deployed drones get the first response. Turrets never wait for the Warden's turn: they
        // engage on their own the moment a player is inside their radius (updateTurretAI).
        if (drone.isActive()) {
            openDroneReaction();
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
                wardenLine = turret.isActive()
                    ? "The security turrets rotate, tracking anything that enters their range."
                    : "The Warden attempts to activate a turret, but none remain online.";
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

    private boolean reactionSourceActive() {
        return reactionAttackType == EnemyAttackType.DRONE
            && drone.isUnitActive(reactionUnitIndex);
    }

    private void clearReactionAttack() {
        reactionOpen = false;
        reactionAttackType = null;
        reactionUnitIndex = -1;
        reactionBlocksDamage = false;
        pendingDroneTarget = null;
        pendingDroneDamageTimer = -1f;
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
        if (hostSession != null) {
            hostSession.send(new Level3TurnStateMessage(turnManager.getPhase(), warden.getState(),
                warden.getStability(), warden.getDualMeter(), drone.isActive(), turret.isActive(),
                player1.health, player2.health, bannerSeq, pendingBannerId, wardenLine, breakerLine, listenerLine,
                wardenAttacked, wardenDamaged, droneAttacked, false,
                p1Action == null ? -1 : p1Action.ordinal(), p2Action == null ? -1 : p2Action.ordinal(),
                p1ConsumedSlot, p2ConsumedSlot, warden.getDirectiveConflict(),
                drone.getActiveCount(), turret.getActiveCount(), droneDamagedIndex, turretDamagedIndex,
                turretDestroyed, turretAiming, turretEventSide, memoryRecovered,
                reactionOpen, reactionAttackType == null ? -1 : reactionAttackType.ordinal(),
                reactionTargetSide, reactionSelectionOrdinal, damageAppliedTargetSide,
                turretAiming ? turretEventUnit : reactionUnitIndex,
                restorationStarted, restorationCompleted));
            if (gun != null) hostSession.send(gun.toMessage());
        }
        wardenAttacked = false;
        wardenDamaged = false;
        droneAttacked = false;
        turretAiming = false;
        turretEventUnit = -1;
        turretEventSide = 0;
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
        standDownTimer = -1f;
        endingTimer = -1f;
        endingReady = false;
        memoryRecovered = false;
        memoryEffectsApplied = false;
        reactionOpen = false;
        reactionAttackType = null;
        reactionTargetSide = 0;
        reactionUnitIndex = -1;
        reactionBlocksDamage = false;
        reactionSelectionOrdinal = -1;
        damageAppliedTargetSide = 0;
        turretEventUnit = -1;
        turretEventSide = 0;
        turretCombatUnit = -1;
        playerCombatReady[0] = false;
        playerCombatReady[1] = false;
        turretShieldBlock[0] = false;
        turretShieldBlock[1] = false;
        Arrays.fill(tracedTurretState, SecurityTurretController.AnimationState.IDLE_ROTATING);
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
