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
import io.github.fableops.level2.Gun;
import io.github.fableops.level3.network.Level3ActionMessage;
import io.github.fableops.level3.network.Level3TurnStateMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

// Host-authoritative turn encounter. The Warden has no health: operator actions stabilize its
// directive, expose the old safety decision, and finally prove dual authorization.
public class Level3Controller {

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
    private float standDownTimer = -1f;
    private boolean endingReady;

    private int bannerSeq;
    private int pendingBannerId = -1;
    private String wardenLine = "";
    private String breakerLine = "";
    private String listenerLine = "";

    private boolean wardenAttacked;
    private boolean wardenDamaged;
    private boolean droneAttacked;
    private boolean turretAttacked;
    private int droneDamagedIndex = -1;
    private int turretDamagedIndex = -1;
    private int p1ConsumedSlot = -1;
    private int p2ConsumedSlot = -1;

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
        return gun != null && gun.getOwner() == side && gun.hasAmmo()
            && hasNamedItem(inventories, side, "Sidearm");
    }

    public static boolean hasShield(PlayerInventories inventories, int side) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && isShieldItem(item)) return true;
        }
        return false;
    }

    public static boolean hasMedkit(PlayerInventories inventories, int side) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && item.isConsumable()) return true;
        }
        return false;
    }

    public static PlayerActionType[] availableActions(PlayerInventories inventories, Gun gun,
                                                       int side, Role role) {
        List<PlayerActionType> actions = new ArrayList<>();
        if (role == Role.BREAKER) {
            actions.add(PlayerActionType.BREAKER_PHYSICAL_STRIKE);
            if (hasSidearm(inventories, gun, side)) actions.add(PlayerActionType.BREAKER_WEAPON_ATTACK);
            actions.add(PlayerActionType.BREAKER_REPAIR_MECHANISM);
            if (hasShield(inventories, side)) actions.add(PlayerActionType.BREAKER_SHIELD_DEFENSE);
        } else {
            actions.add(PlayerActionType.LISTENER_SCAN_WARDEN);
            actions.add(PlayerActionType.LISTENER_REDUCE_SUBROUTINE);
            actions.add(PlayerActionType.LISTENER_RECOVER_LOGS);
            actions.add(PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT);
            actions.add(PlayerActionType.LISTENER_SUPPORT_BREAKER);
        }
        if (hasMedkit(inventories, side)) actions.add(PlayerActionType.USE_MEDKIT);
        return actions.toArray(new PlayerActionType[0]);
    }

    public MessageListener asMessageListener() {
        return (type, body) -> {
            if (!"LEVEL3_ACTION".equals(type)) return;
            try {
                Level3ActionMessage msg = Level3ActionMessage.deserialize(body);
                int ordinal = msg.getActionOrdinal();
                PlayerActionType[] actions = PlayerActionType.values();
                if (ordinal < 0 || ordinal >= actions.length) return;
                Gdx.app.postRunnable(() -> confirmLocal(2, actions[ordinal]));
            } catch (RuntimeException ignored) {
                // Malformed event-channel input must not take down the render thread.
            }
        };
    }

    public void confirmLocal(int side, PlayerActionType action) {
        if (turnManager.getPhase() != TurnManager.Phase.PLAYER_TURN || action == null) return;
        Role role = roleForSide(side);
        if (action == PlayerActionType.USE_ITEM) {
            if (!hasUsableItem(inventories, side)) return;
        } else if (!Arrays.asList(availableActions(inventories, gun, side, role)).contains(action)) {
            return;
        }

        boolean confirmed = side == 1 ? turnManager.p1Confirmed() : turnManager.p2Confirmed();
        if (confirmed) return;
        if (side == 1) turnManager.confirmP1(action);
        else if (side == 2) turnManager.confirmP2(action);
        else return;
        broadcast();
    }

    public void update(float delta) {
        warden.update(delta);
        drone.update(delta);
        turret.update(delta);

        if (standDownTimer > 0f) {
            standDownTimer -= delta;
            if (standDownTimer <= 0f) {
                warden.setState(WardenState.STAND_DOWN);
                drone.setCalm(true);
                turret.setCalm(true);
                endingReady = true;
                standDownTimer = -1f;
                broadcast();
            }
            return;
        }
        if (warden.getState() == WardenState.DUAL_AUTHORIZATION || warden.getState().isStoodDown()) return;

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
        breakerLine = resolvePlayerAction(breakerAction, breaker);
        listenerLine = resolvePlayerAction(listenerAction, listener);
        advanceStateIfNeeded();

        boolean coordinated = warden.getState() == WardenState.DIRECTIVE_CONFLICT
            && breakerAction == PlayerActionType.BREAKER_REPAIR_MECHANISM
            && listenerAction == PlayerActionType.LISTENER_AUTHORIZATION_ATTEMPT;
        if (coordinated) {
            warden.setDualMeter(warden.getDualMeter() + 50f);
            warden.setDirectiveConflict(warden.getDirectiveConflict() - 15f);
            if (warden.getDualMeter() >= DUAL_CAP) {
                warden.setState(WardenState.DUAL_AUTHORIZATION);
                queueBanner(BANNER_DUAL);
                standDownTimer = STAND_DOWN_DELAY;
            }
        }
    }

    private void advanceStateIfNeeded() {
        WardenState state = warden.getState();
        if (state == WardenState.DEFENSE_ACTIVE
            && (warden.getStability() >= STABILITY_MEMORY
                || warden.getDirectiveConflict() <= DIRECTIVE_MEMORY)) {
            warden.setState(WardenState.MEMORY_RECOVERY);
            queueBanner(BANNER_MEMORY);
        } else if (state == WardenState.MEMORY_RECOVERY
            && (warden.getStability() >= STABILITY_CONFLICT
                || warden.getDirectiveConflict() <= DIRECTIVE_CONFLICT)) {
            warden.setState(WardenState.DIRECTIVE_CONFLICT);
            queueBanner(BANNER_CONFLICT);
        }
    }

    private String resolvePlayerAction(PlayerActionType action, Player self) {
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
            case BREAKER_WEAPON_ATTACK: {
                self.startAttack();
                if (!hasSidearm(inventories, gun, self == player1 ? 1 : 2)
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
                self.startAttack();
                return useShield(self);
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
                addProgress(6f, 7f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_AUTHORIZATION_ATTEMPT:
                self.startAttack();
                addProgress(6f, 5f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_SUPPORT_BREAKER:
                self.startAttack();
                addProgress(4f, 4f);
                return name + " " + action.flavorVerb() + ".";
            case USE_MEDKIT:
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

    private boolean damageDrone(float damage) {
        int before = drone.getActiveCount();
        droneDamagedIndex = drone.damage(damage);
        return droneDamagedIndex >= 0 && (before > 0);
    }

    private boolean damageTurret(float damage) {
        int before = turret.getActiveCount();
        turretDamagedIndex = turret.damage(damage);
        return turretDamagedIndex >= 0 && (before > 0);
    }

    private String useItem(Player self) {
        int side = self == player1 ? 1 : 2;
        Inventory inv = inventories.forPlayer(side);
        String name = callSignOf(self);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && item.isConsumable()) {
                self.heal(item.getHealAmount());
                inv.remove(i);
                recordConsumedSlot(side, i);
                return name + " uses the " + item.getName() + " and restores health.";
            }
        }
        return useShield(self);
    }

    private String useShield(Player self) {
        int side = self == player1 ? 1 : 2;
        Inventory inv = inventories.forPlayer(side);
        String name = callSignOf(self);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && isShieldItem(item)) {
                inv.remove(i);
                recordConsumedSlot(side, i);
                if (side == 1) p1Braced = true;
                else p2Braced = true;
                return name + " braces with the " + item.getName() + ".";
            }
        }
        return name + " has no usable gear.";
    }

    private static boolean hasNamedItem(PlayerInventories inventories, int side, String name) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && name.equalsIgnoreCase(item.getName())) return true;
        }
        return false;
    }

    private void recordConsumedSlot(int side, int slot) {
        if (side == 1) p1ConsumedSlot = slot;
        else p2ConsumedSlot = slot;
    }

    private static boolean isShieldItem(InventoryItem item) {
        return "Shield Cell".equals(item.getName()) || "Rare Plating".equals(item.getName());
    }

    private void resolveWardenAction() {
        if (warden.getState() == WardenState.DUAL_AUTHORIZATION || warden.getState().isStoodDown()) {
            wardenLine = "The Warden holds position.";
            return;
        }

        WardenActionType action = rolledWardenAction;
        switch (action) {
            case DEPLOY_DRONE: {
                drone.spawn();
                drone.playAttackFlash();
                warden.playAttackFlash();
                droneAttacked = true;
                wardenAttacked = true;
                Player target = higherHealth();
                applyWardenDamage(target, 9f);
                wardenLine = "The Warden " + action.flavorVerb() + "; it intercepts " + callSignOf(target) + ".";
                break;
            }
            case ACTIVATE_TURRET: {
                turret.activate();
                turret.playAttackFlash();
                warden.playAttackFlash();
                turretAttacked = true;
                wardenAttacked = true;
                Player target = lowerHealth();
                applyWardenDamage(target, 14f);
                wardenLine = "The Warden " + action.flavorVerb() + "; it fires on " + callSignOf(target) + ".";
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

    private void applyWardenDamage(Player target, float base) {
        float damage = base * nextWardenDamageMultiplier;
        boolean braced = target == player1 ? p1Braced : p2Braced;
        if (braced) damage *= 0.5f;
        if (listenerProtected && roleOf(target) == Role.LISTENER) damage *= 0.5f;
        target.takeDamage(damage);
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
        if (hostSession != null) {
            hostSession.send(new Level3TurnStateMessage(turnManager.getPhase(), warden.getState(),
                warden.getStability(), warden.getDualMeter(), drone.isActive(), turret.isActive(),
                player1.health, player2.health, bannerSeq, pendingBannerId, wardenLine, breakerLine, listenerLine,
                wardenAttacked, wardenDamaged, droneAttacked, turretAttacked,
                p1Action == null ? -1 : p1Action.ordinal(), p2Action == null ? -1 : p2Action.ordinal(),
                p1ConsumedSlot, p2ConsumedSlot, warden.getDirectiveConflict(),
                drone.getActiveCount(), turret.getActiveCount(), droneDamagedIndex, turretDamagedIndex));
            if (gun != null) hostSession.send(gun.toMessage());
        }
        wardenAttacked = false;
        wardenDamaged = false;
        droneAttacked = false;
        turretAttacked = false;
        droneDamagedIndex = -1;
        turretDamagedIndex = -1;
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
        standDownTimer = -1f;
        endingReady = false;
        bannerSeq = 0;
        pendingBannerId = -1;
        wardenLine = "";
        breakerLine = "";
        listenerLine = "";
        wardenAttacked = false;
        wardenDamaged = false;
        droneAttacked = false;
        turretAttacked = false;
        droneDamagedIndex = -1;
        turretDamagedIndex = -1;
        p1ConsumedSlot = -1;
        p2ConsumedSlot = -1;
        broadcast();
    }

    public void dispose() {
        warden.dispose();
        drone.dispose();
        turret.dispose();
    }
}
