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
import io.github.fableops.level2.Sidearms;
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
    // Every hit an operator takes in the Warden encounter, in one place. Tuned against the 100 HP
    // in Player.MAX_HEALTH: a turret is the heavy hit, a drone lunge the common one, and the
    // containment warning the unavoidable chip that lands on both operators at once
    private static final float TURRET_DAMAGE = 14f;
    private static final float DRONE_DAMAGE = 9f;
    private static final float CONTAINMENT_WARNING_DAMAGE = 6f;
    private static final float RANGE_TRACE_INTERVAL = 2f;
    // Sidearm damage against a turret (MAX_HEALTH 30), so three shots finish one - two with the
    // ammo cache, which is what that pickup buys. TNT is the one-shot alternative
    private static final float TURRET_SIDEARM_DAMAGE = 10f;
    // The ammo cache raises turn damage from 30 to 45; the same 1.5x carries to turret fire
    private static final float AMMO_CACHE_MULTIPLIER = 1.5f;
    // One charge takes down one turret. Kept well above turret MAX_HEALTH on purpose, so retuning
    // turret HP never quietly turns a charge into a partial hit
    private static final float TNT_DAMAGE = 999f;
    // Disable Drone with no sidearm to hand: the operator still pulls the kill switch by hand
    private static final float BARE_HANDED_DRONE_DAMAGE = 30f;

    private final HostSession hostSession;
    private final PlayerInventories inventories;
    private final Sidearms sidearms;
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
    // Rare Plating: once sealed, that operator takes no damage for the rest of the encounter.
    // Index = side - 1. Cleared only by a level restart
    private final boolean[] plated = new boolean[2];
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
                            Sidearms sidearms, Player player1, Player player2, Role sideOneRole) {
        this.hostSession = hostSession;
        this.inventories = inventories;
        this.sidearms = sidearms;
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
            if ((sidearms != null && sidearms.forSide(side).hasAmmo() && itemSupportsAction(weapon, item))
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

    public static boolean hasSidearm(PlayerInventories inventories, Sidearms sidearms, int side) {
        return sidearms != null && hasNamedItem(inventories, side, "Sidearm");
    }

    public static boolean hasShield(PlayerInventories inventories, int side) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && isShieldItem(item)) return true;
        }
        return isShieldItem(inventories.sharedItem());
    }

    public static boolean hasPlating(PlayerInventories inventories, int side) {
        return hasNamedItem(inventories, side, "Rare Plating");
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

    public static boolean hasTnt(PlayerInventories inventories, int side) {
        return hasNamedItem(inventories, side, "TNT");
    }

    public static boolean requiresEquipment(PlayerActionType action) {
        return action == PlayerActionType.BREAKER_WEAPON_ATTACK
            || action == PlayerActionType.LISTENER_WEAPON_ATTACK
            || action == PlayerActionType.BREAKER_SHIELD_DEFENSE
            || action == PlayerActionType.LISTENER_SHIELD_DEFENSE
            || action == PlayerActionType.USE_MEDKIT
            || action == PlayerActionType.USE_TNT
            || action == PlayerActionType.USE_PLATING;
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
            case USE_PLATING:
                return isPlatingItem(item);
            case USE_TNT:
                return "TNT".equalsIgnoreCase(item.getName());
            case USE_MEDKIT:
                return item.isConsumable();
            default:
                return false;
        }
    }

    // How many carried items could satisfy this action. One or fewer means there is nothing to
    // choose between, so the caller can skip the equipment picker entirely
    public static int compatibleSlotCount(PlayerInventories inventories, int side,
                                          PlayerActionType action) {
        int count = 0;
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            if (itemSupportsAction(action, inventories.forPlayer(side).get(i))) count++;
        }
        if (itemSupportsAction(action, inventories.sharedItem())) count++;
        return count;
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

    public static PlayerActionType[] availableActions(PlayerInventories inventories, Sidearms sidearms,
                                                       int side, Role role) {
        List<PlayerActionType> actions = new ArrayList<>();
        // Role actions first, in a fixed order. There is no inventory column any more: carried gear
        // appears below as its own row, and only while that operator is actually carrying it
        if (role == Role.BREAKER) {
            actions.add(PlayerActionType.BREAKER_PHYSICAL_STRIKE);
            actions.add(PlayerActionType.BREAKER_DISABLE_DRONE);
            actions.add(PlayerActionType.BREAKER_PROTECT_LISTENER);
            actions.add(PlayerActionType.BREAKER_REPAIR_MECHANISM);
        } else {
            actions.add(PlayerActionType.LISTENER_SCAN_WARDEN);
            actions.add(PlayerActionType.LISTENER_REDUCE_SUBROUTINE);
            actions.add(PlayerActionType.LISTENER_RECOVER_LOGS);
            actions.add(PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT);
            actions.add(PlayerActionType.LISTENER_SUPPORT_BREAKER);
        }
        // Gear rows, common to both operators
        if (hasShield(inventories, side)) {
            actions.add(role == Role.BREAKER
                ? PlayerActionType.BREAKER_SHIELD_DEFENSE : PlayerActionType.LISTENER_SHIELD_DEFENSE);
        }
        if (hasPlating(inventories, side)) actions.add(PlayerActionType.USE_PLATING);
        if (hasMedkit(inventories, side)) actions.add(PlayerActionType.USE_MEDKIT);
        if (hasTnt(inventories, side)) actions.add(PlayerActionType.USE_TNT);
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
        // A Sidearm can change hands through the shared slot, so re-read who is armed on both sides
        if (sidearms != null) sidearms.syncOwnership(inventories);
        if (hostSession != null) {
            hostSession.send(message);
            if (sidearms != null) {
                hostSession.send(sidearms.forSide(1).toMessage());
                hostSession.send(sidearms.forSide(2).toMessage());
            }
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
        } else if (action == PlayerActionType.USE_PLATING && hasPlating(inventories, side)) {
            // Gear rows are built from the inventory, not from a fixed role list
        } else if (action == PlayerActionType.USE_TNT && hasTnt(inventories, side)) {
            // TNT is reached from the inventory column rather than a role action row, so it never
            // appears in availableActions and would otherwise be rejected by the check below.
        } else if (!Arrays.asList(availableActions(inventories, sidearms, side, role)).contains(action)) {
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
                if (!hasSidearm(inventories, sidearms, side) || !sidearms.forSide(side).hasAmmo()
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
            sidearms.forSide(side).useTurnBasedRound();
            float hitX = drone.unitX(reactionUnitIndex);
            float hitY = drone.unitY(reactionUnitIndex);
            boolean hit = damageReactionDrone(sidearms.forSide(side).turnBasedDamage());
            sidearms.forSide(side).showTurnBasedShot(target, hitX, hitY, hit);
            setOperatorLine(side, callSignOf(target) + " fires the Sidearm into the incoming attack.");
            Gdx.app.log("Level3ReactionTrace", "Player Sidearm used side=" + side
                + " ammoRemaining=" + sidearms.forSide(side).getTotalRounds() + " hit=" + hit);
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
            sidearms.forSide(side).useTurnBasedRound();
            int before = turret.getActiveCount();
            float hitX = turret.unitX(unit);
            float hitY = turret.unitY(unit);
            turretDamagedIndex = turret.damage(unit, TURRET_SIDEARM_DAMAGE);
            turretDestroyed = turretDamagedIndex >= 0 && turret.getActiveCount() < before;
            sidearms.forSide(side).showTurnBasedShot(target, hitX, hitY, turretDamagedIndex >= 0);
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
                // Physical Attack is turret fire. Three sidearm rounds finish a housing, two with
                // the ammo cache; TNT is the one-shot alternative
                int side = self == player1 ? 1 : 2;
                // Target and weapon are both checked before a round is spent: firing at an empty
                // gantry, or with no sidearm at all, must not quietly cost ammo
                if (turret.getActiveCount() == 0) {
                    return name + " sweeps the gantry, but no turret is still online.";
                }
                if (!hasSidearm(inventories, sidearms, side)) {
                    return name + " has no sidearm to bring against the turret housing.";
                }
                Gun weapon = sidearms.forSide(side);
                String blocked = spendRoundOrReload(weapon, name);
                if (blocked != null) return blocked;

                float damage = TURRET_SIDEARM_DAMAGE
                    * (weapon.hasAmmoCache() ? AMMO_CACHE_MULTIPLIER : 1f);
                int before = turret.getActiveCount();
                boolean hit = damageTurret(damage);
                boolean destroyed = turret.getActiveCount() < before;
                if (hit) {
                    weapon.showTurnBasedShot(self,
                        turret.unitX(turretDamagedIndex), turret.unitY(turretDamagedIndex), true);
                }
                addProgress(hit ? 5f : 3f, 4f);
                return name + " " + action.flavorVerb()
                    + (destroyed ? "; the housing splits open and the turret goes dark."
                        : hit ? "; rounds spark off the turret housing."
                        : ", but the shot finds nothing.");
            }
            case BREAKER_DISABLE_DRONE: {
                // Disable Drone is drone fire. TNT does nothing to a drone, so this is their answer
                int side = self == player1 ? 1 : 2;
                if (drone.getActiveCount() == 0) {
                    return name + " " + action.flavorVerb() + ", but finds no deployed drone.";
                }
                Gun weapon = sidearms.forSide(side);
                // The ammo rule only binds an operator who actually carries a sidearm. Unarmed,
                // they reach the kill switch by hand and no phantom weapon is drained
                boolean armed = hasSidearm(inventories, sidearms, side);
                if (armed) {
                    String blocked = spendRoundOrReload(weapon, name);
                    if (blocked != null) return blocked;
                }
                float damage = armed ? weapon.turnBasedDamage() : BARE_HANDED_DRONE_DAMAGE;
                boolean hit = damageDrone(damage);
                if (hit) {
                    weapon.showTurnBasedShot(self,
                        drone.unitX(droneDamagedIndex), drone.unitY(droneDamagedIndex), true);
                }
                addProgress((hit ? 7f : 3f) + (breakerSupported ? 3f : 0f), 6f);
                return name + " " + action.flavorVerb()
                    + (weapon.hasAmmoCache() ? " with the ammo cache's high-output load" : "")
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
            case USE_TNT:
                return useTnt(self, inventorySlot);
            case USE_PLATING:
                return usePlating(self, inventorySlot);
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

    // Unused since turn attacks became drone-only: turrets now answer to TNT, or to the Sidearm
    // reaction when one opens fire. Kept as the single place that would re-link the two if that
    // ever changes back
    @SuppressWarnings("unused")
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
        // heal() clamps to MAX_HEALTH, so using one at full health would burn the kit for nothing
        if (self.health >= Player.MAX_HEALTH) {
            return name + " is already at full health; the medkit stays sealed.";
        }
        float before = self.health;
        self.heal(item.getHealAmount());
        removeItemAt(side, slot);
        recordConsumedSlot(side, slot);
        Gdx.app.log("Level3MedkitTrace", "side=" + side + " slot=" + slot
            + " item=" + item.getName() + " heal=" + item.getHealAmount()
            + " health " + before + " -> " + self.health);
        return name + " uses the " + item.getName() + " and restores health.";
    }

    // The ammo rule both attack rows share. Returns null when a round was spent and the attack can
    // go ahead, or the line to report when the turn is spent reloading instead
    private String spendRoundOrReload(Gun weapon, String name) {
        if (weapon.getMagazineRounds() > 0) {
            weapon.useTurnBasedRound();
            return null;
        }
        if (weapon.getSpareRounds() > 0) {
            weapon.reloadForTurn();
            return name + " runs the sidearm dry and spends the turn reloading.";
        }
        return name + " finds the sidearm empty, with nothing left to load.";
    }

    // Rare Plating seals an operator for the rest of the encounter: no Warden damage reaches them
    private String usePlating(Player self, int slot) {
        int side = self == player1 ? 1 : 2;
        InventoryItem item = itemAt(inventories, side, slot);
        String name = callSignOf(self);
        if (!itemSupportsAction(PlayerActionType.USE_PLATING, item)) {
            return name + " has no rare plating to seal into.";
        }
        if (plated[side - 1]) return name + " is already sealed in rare plating.";
        plated[side - 1] = true;
        removeItemAt(side, slot);
        recordConsumedSlot(side, slot);
        addProgress(5f, 5f);
        Gdx.app.log("Level3PlatingTrace", "side=" + side + " slot=" + slot
            + " sealed - no further Warden damage reaches this operator");
        return name + " " + PlayerActionType.USE_PLATING.flavorVerb()
            + "; nothing the Warden fields will reach them again.";
    }

    // One charge, one turret. A blocked attempt still costs the turn but never spends the charge
    private String useTnt(Player self, int slot) {
        int side = self == player1 ? 1 : 2;
        InventoryItem item = itemAt(inventories, side, slot);
        String name = callSignOf(self);
        if (!itemSupportsAction(PlayerActionType.USE_TNT, item)) {
            return name + " has no demolition charge selected.";
        }
        if (turret.getActiveCount() == 0) {
            return name + " finds no turret left to bring down.";
        }

        damageTurret(TNT_DAMAGE);
        removeItemAt(side, slot);
        recordConsumedSlot(side, slot);
        addProgress(8f, 8f);
        Gdx.app.log("SecurityTurretTrace", "TNT destroyed turretID=" + turretDamagedIndex
            + " remaining=" + turret.getActiveCount());
        return name + " " + PlayerActionType.USE_TNT.flavorVerb()
            + "; the housing blows open and the turret goes dark.";
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

    // Rare Plating is deliberately NOT a shield any more: Use Shield is the one-round brace,
    // Use Rare Plating seals the operator for the rest of the fight. Two rows, two effects
    private static boolean isShieldItem(InventoryItem item) {
        if (item == null) return false;
        String name = item.getName();
        return "Shield".equalsIgnoreCase(name) || "Shield Cell".equalsIgnoreCase(name);
    }

    private static boolean isPlatingItem(InventoryItem item) {
        return item != null && "Rare Plating".equalsIgnoreCase(item.getName());
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
                applyWardenDamage(player1, CONTAINMENT_WARNING_DAMAGE);
                applyWardenDamage(player2, CONTAINMENT_WARNING_DAMAGE);
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
            applyWardenDamage(target, DRONE_DAMAGE);
            damageAppliedTargetSide = reactionTargetSide;
            Gdx.app.log("DefenseDroneTrace", "damage applied targetSide=" + reactionTargetSide
                + " health=" + target.health);
        }
        clearReactionAttack();
        // The attack event already started the animation on both peers; this only commits damage.
        broadcast();
    }

    private void applyWardenDamage(Player target, float base) {
        // Rare Plating is absolute and permanent: nothing the Warden fields gets through
        if (plated[(target == player1 ? 1 : 2) - 1]) return;
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
            if (sidearms != null) {
                hostSession.send(sidearms.forSide(1).toMessage());
                hostSession.send(sidearms.forSide(2).toMessage());
            }
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
        // Plating is permanent within a run, so a restart is the only thing that clears it
        Arrays.fill(plated, false);
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
