package io.github.fableops.level3;

import java.util.Random;

import com.badlogic.gdx.Gdx;

import io.github.fableops.Player;
import io.github.fableops.Role;
import io.github.fableops.inventory.Inventory;
import io.github.fableops.inventory.InventoryItem;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.level3.network.Level3ActionMessage;
import io.github.fableops.level3.network.Level3TurnStateMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

// Host-authoritative: runs the Warden encounter's turn-by-turn logic (TurnManager +
// WardenController + the two defense unit controllers), the same "host decides, client renders"
// split Level1Controller/Level2Controller use. hostSession is null in debug, where this machine
// plays both operators locally
public class Level3Controller {

    // One-shot banner ids, shared with Level3Screen (both host/debug, reading these getters
    // directly, and the client, reading them off Level3TurnStateMessage) so both sides show
    // identical copy without duplicating the text
    public static final int BANNER_MEMORY = 0;
    public static final int BANNER_CONFLICT = 1;
    public static final int BANNER_DUAL = 2;
    public static final int BANNER_LOG = 3;

    private static final float STABILITY_MEMORY = 30f;
    private static final float STABILITY_CONFLICT = 65f;
    private static final float DUAL_CAP = 100f;

    private static final float WARDEN_TURN_DELAY = 1.3f;
    private static final float RESOLUTION_DELAY = 2.2f;
    private static final float STAND_DOWN_DELAY = 3.5f;

    private final HostSession hostSession;
    private final PlayerInventories inventories;
    private final Player player1;
    private final Player player2;
    private final Role sideOneRole; // player1's role; player2 is always the other one
    private final TurnManager turnManager = new TurnManager();
    private final WardenController warden;
    private final DefenseDroneController drone;
    private final SecurityTurretController turret;
    private final Random random = new Random();

    private WardenActionType rolledWardenAction = WardenActionType.DEFENSIVE_SCAN;
    private WardenActionType lastWardenAction = null;
    private float nextWardenDamageMultiplier = 1f;
    private boolean p1Braced = false;
    private boolean p2Braced = false;
    private boolean logShown = false;
    private float standDownTimer = -1f;
    private boolean endingReady = false;

    private int bannerSeq = 0;
    private int pendingBannerId = -1;
    private String wardenLine = "";
    private String breakerLine = "";
    private String listenerLine = "";

    public Level3Controller(HostSession hostSession, Level3Map world, PlayerInventories inventories,
                            Player player1, Player player2, Role sideOneRole) {
        this.hostSession = hostSession;
        this.inventories = inventories;
        this.player1 = player1;
        this.player2 = player2;
        this.sideOneRole = sideOneRole;

        float[] wardenAnchor = world.getWardenAnchor();
        float[] droneAnchor = world.getDroneAnchor();
        float[] turretAnchor = world.getTurretAnchor();
        warden = new WardenController(wardenAnchor[0], wardenAnchor[1]);
        drone = new DefenseDroneController(droneAnchor[0], droneAnchor[1]);
        turret = new SecurityTurretController(turretAnchor[0], turretAnchor[1]);
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

    // Whether that side currently carries something Use Item would act on - the fourth menu row
    // only appears when this is true. Static and public so Level3Screen's client path (which mirrors
    // its own copy of PlayerInventories, already kept in sync the same way Level 2's loot pickups are)
    // can ask the same question locally without a Level3Controller instance, which only exists on
    // the host
    public static boolean hasUsableItem(PlayerInventories inventories, int side) {
        Inventory inv = inventories.forPlayer(side);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && (item.isConsumable() || isShieldItem(item))) return true;
        }
        return false;
    }

    // Client E/action presses arrive as LEVEL3_ACTION for player 2. Handled on the render thread
    public MessageListener asMessageListener() {
        return (type, body) -> {
            if ("LEVEL3_ACTION".equals(type)) {
                Level3ActionMessage msg = Level3ActionMessage.deserialize(body);
                Gdx.app.postRunnable(() -> confirmLocal(2, PlayerActionType.values()[msg.getActionOrdinal()]));
            }
        };
    }

    // Host's own local player (and, in debug/solo, the other one too) confirms here directly
    public void confirmLocal(int side, PlayerActionType action) {
        if (side == 1) turnManager.confirmP1(action);
        else turnManager.confirmP2(action);
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
            return; // frozen while dual authorization resolves - no more turns
        }
        if (warden.getState() == WardenState.DUAL_AUTHORIZATION || warden.getState().isStoodDown()) return;

        switch (turnManager.getPhase()) {
            case PLAYER_TURN:
                if (turnManager.bothConfirmed()) {
                    rollWardenAction();
                    turnManager.advanceTo(TurnManager.Phase.WARDEN_TURN, WARDEN_TURN_DELAY);
                    broadcast();
                }
                break;
            case WARDEN_TURN:
                if (turnManager.tick(delta)) {
                    resolveRound();
                    turnManager.advanceTo(TurnManager.Phase.RESOLUTION, RESOLUTION_DELAY);
                    broadcast();
                }
                break;
            case RESOLUTION:
                if (turnManager.tick(delta)) {
                    turnManager.resetForNextRound();
                    broadcast();
                }
                break;
            default:
                break;
        }
    }

    // True once, the moment the Warden has fully stood down - Level3Screen fires StoryBeat.ENDING then
    public boolean consumeEndingReady() {
        if (!endingReady) return false;
        endingReady = false;
        return true;
    }

    private void rollWardenAction() {
        WardenActionType[] all = WardenActionType.values();
        WardenActionType choice = all[random.nextInt(all.length)];
        if (choice == lastWardenAction) choice = all[random.nextInt(all.length)]; // one reroll, avoids most repeats
        lastWardenAction = choice;
        rolledWardenAction = choice;
    }

    private void resolveRound() {
        WardenState stateBefore = warden.getState();
        nextWardenDamageMultiplier = 1f;
        p1Braced = false;
        p2Braced = false;

        boolean p1IsBreaker = sideOneRole == Role.BREAKER;
        Player breaker = p1IsBreaker ? player1 : player2;
        Player listener = p1IsBreaker ? player2 : player1;
        PlayerActionType breakerAction = p1IsBreaker ? turnManager.getP1Action() : turnManager.getP2Action();
        PlayerActionType listenerAction = p1IsBreaker ? turnManager.getP2Action() : turnManager.getP1Action();

        breakerLine = resolvePlayerAction(breakerAction, breaker);
        listenerLine = resolvePlayerAction(listenerAction, listener);

        advanceStateIfNeeded();

        if (stateBefore == WardenState.DIRECTIVE_CONFLICT) {
            boolean coordinated = breakerAction == PlayerActionType.BREAKER_INTERACT_MECHANISM
                && listenerAction == PlayerActionType.LISTENER_REDUCE_SUBROUTINE;
            warden.setDualMeter(warden.getDualMeter() + (coordinated ? 50f : 22f));
            if (warden.getDualMeter() >= DUAL_CAP) {
                warden.setState(WardenState.DUAL_AUTHORIZATION);
                queueBanner(BANNER_DUAL);
                standDownTimer = STAND_DOWN_DELAY;
            }
        }

        resolveWardenAction();
    }

    private void advanceStateIfNeeded() {
        WardenState state = warden.getState();
        if (state == WardenState.DEFENSE_ACTIVE && warden.getStability() >= STABILITY_MEMORY) {
            warden.setState(WardenState.MEMORY_RECOVERY);
            queueBanner(BANNER_MEMORY);
        } else if (state == WardenState.MEMORY_RECOVERY && warden.getStability() >= STABILITY_CONFLICT) {
            warden.setState(WardenState.DIRECTIVE_CONFLICT);
            queueBanner(BANNER_CONFLICT);
        }
    }

    private String resolvePlayerAction(PlayerActionType action, Player self) {
        if (action == null) return "";
        String name = callSignOf(self);
        switch (action) {
            case BREAKER_DAMAGE_DEFENSES: {
                boolean hit = turret.isActive() || drone.isActive();
                if (turret.isActive()) turret.destroy();
                else if (drone.isActive()) drone.destroy();
                if (hit) warden.playDamagedFlash();
                warden.setStability(warden.getStability() + (hit ? 8f : 5f));
                return name + " " + action.flavorVerb() + (hit ? "." : " - nothing there to break.");
            }
            case BREAKER_DISABLE_DRONE: {
                boolean hit = drone.isActive();
                if (hit) {
                    drone.destroy();
                    warden.playDamagedFlash();
                }
                warden.setStability(warden.getStability() + (hit ? 10f : 4f));
                return name + " " + action.flavorVerb() + (hit ? ", and it goes dark." : ", but finds no drone in reach.");
            }
            case BREAKER_INTERACT_MECHANISM:
                warden.setStability(warden.getStability() + 10f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_SCAN_WARDEN:
                warden.setStability(warden.getStability() + 8f);
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_REDUCE_SUBROUTINE:
                warden.setStability(warden.getStability() + 10f);
                nextWardenDamageMultiplier = 0.6f;
                return name + " " + action.flavorVerb() + ".";
            case LISTENER_RECOVER_LOGS: {
                warden.setStability(warden.getStability() + 6f);
                boolean reveal = !logShown && warden.getState().ordinal() >= WardenState.MEMORY_RECOVERY.ordinal();
                if (reveal) {
                    logShown = true;
                    queueBanner(BANNER_LOG);
                }
                return name + " " + action.flavorVerb()
                    + (reveal ? " - and finds a log the Warden never meant to keep." : ".");
            }
            case USE_ITEM:
                return useItem(self);
            default:
                return "";
        }
    }

    private String useItem(Player self) {
        int side = (self == player1) ? 1 : 2;
        Inventory inv = inventories.forPlayer(side);
        String name = callSignOf(self);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && item.isConsumable()) {
                self.heal(item.getHealAmount());
                inv.remove(i);
                return name + " uses the " + item.getName() + ".";
            }
        }
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            InventoryItem item = inv.get(i);
            if (item != null && isShieldItem(item)) {
                inv.remove(i);
                if (self == player1) p1Braced = true;
                else p2Braced = true;
                return name + " braces with the " + item.getName() + ".";
            }
        }
        return name + " has nothing left to use.";
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
        warden.playAttackFlash();
        switch (action) {
            case DEPLOY_DRONE: {
                drone.spawn();
                drone.playAttackFlash();
                Player target = higherHealth();
                applyWardenDamage(target, 9f);
                wardenLine = "The Warden " + action.flavorVerb() + " - it strikes " + callSignOf(target) + ".";
                break;
            }
            case ACTIVATE_TURRET: {
                turret.activate();
                turret.playAttackFlash();
                Player target = lowerHealth();
                applyWardenDamage(target, 14f);
                wardenLine = "The Warden " + action.flavorVerb() + " - it fires on " + callSignOf(target) + ".";
                break;
            }
            case DEFENSIVE_SCAN:
                wardenLine = "The Warden " + action.flavorVerb() + ".";
                break;
            case INCREASE_CONTAINMENT_WARNING:
                applyWardenDamage(player1, 6f);
                applyWardenDamage(player2, 6f);
                wardenLine = "The Warden " + action.flavorVerb() + " - the floor lights up red.";
                break;
            default:
                break;
        }
    }

    private void applyWardenDamage(Player target, float base) {
        float dmg = base * nextWardenDamageMultiplier;
        boolean braced = (target == player1) ? p1Braced : p2Braced;
        if (braced) dmg *= 0.5f;
        target.takeDamage(dmg);
    }

    private Player higherHealth() { return player1.health >= player2.health ? player1 : player2; }

    private Player lowerHealth() { return player1.health <= player2.health ? player1 : player2; }

    private String callSignOf(Player player) {
        Role role = (player == player1) ? sideOneRole : sideOneRole.other();
        return role.callSign();
    }

    private void queueBanner(int id) {
        bannerSeq++;
        pendingBannerId = id;
    }

    private void broadcast() {
        if (hostSession == null) return;
        hostSession.send(new Level3TurnStateMessage(turnManager.getPhase(), warden.getState(),
            warden.getStability(), warden.getDualMeter(), drone.isActive(), turret.isActive(),
            player1.health, player2.health, bannerSeq, pendingBannerId, wardenLine, breakerLine, listenerLine));
    }

    public void reset() {
        turnManager.reset();
        warden.setState(WardenState.DEFENSE_ACTIVE);
        warden.setStability(0f);
        warden.setDualMeter(0f);
        drone.reset();
        turret.reset();
        lastWardenAction = null;
        nextWardenDamageMultiplier = 1f;
        p1Braced = false;
        p2Braced = false;
        logShown = false;
        standDownTimer = -1f;
        endingReady = false;
        bannerSeq = 0;
        pendingBannerId = -1;
        wardenLine = "";
        breakerLine = "";
        listenerLine = "";
    }

    public void dispose() {
        warden.dispose();
        drone.dispose();
        turret.dispose();
    }
}
