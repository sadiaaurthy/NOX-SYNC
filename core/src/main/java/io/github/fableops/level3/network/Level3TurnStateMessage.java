package io.github.fableops.level3.network;

import io.github.fableops.level3.TurnManager;
import io.github.fableops.level3.WardenState;
import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: one round's worth of encounter state. Sent whenever the phase changes and again
// after resolution, so the client's mirrored WardenController/DefenseDroneController/
// SecurityTurretController render the same thing the host does without running any of the turn
// logic themselves - same "host decides, client renders" split as every other level.
//
// bannerSeq only increments when a new one-shot banner should show; the client compares it against
// the last one it displayed instead of re-showing the same banner every tick. The four "just
// happened" flags are true only in the single broadcast sent right after a round resolves, so the
// client can replay the same attack/damaged flash the host just played instead of only ever seeing
// the idle pose
public class Level3TurnStateMessage extends NetworkMessage {

    private final TurnManager.Phase phase;
    private final WardenState wardenState;
    private final float stability;
    private final float dualMeter;
    private final boolean droneActive;
    private final boolean turretActive;
    private final float healthP1;
    private final float healthP2;
    private final int bannerSeq;
    private final int bannerId; // -1 = none, see Level3Controller.BANNER_*
    private final String wardenLine;
    private final String breakerLine;
    private final String listenerLine;
    private final boolean wardenAttacked;
    private final boolean wardenDamaged;
    private final boolean droneAttacked;
    private final boolean turretAttacked;
    private final int p1ActionOrdinal;
    private final int p2ActionOrdinal;
    private final int p1ConsumedSlot;
    private final int p2ConsumedSlot;
    private final float directiveConflict;
    private final int droneCount;
    private final int turretCount;
    private final int droneDamagedIndex;
    private final int turretDamagedIndex;
    private final boolean turretDestroyed;
    private final boolean turretAiming;
    private final int turretTargetSide;
    private final boolean memoryRecovered;
    private final boolean reactionOpen;
    private final int reactionAttackOrdinal;
    private final int reactionTargetSide;
    private final int reactionSelectionOrdinal;
    private final int damageAppliedTargetSide;
    private final int reactionUnitIndex;
    private final boolean restorationStarted;
    private final boolean restorationCompleted;

    public Level3TurnStateMessage(TurnManager.Phase phase, WardenState wardenState, float stability,
                                  float dualMeter, boolean droneActive, boolean turretActive,
                                  float healthP1, float healthP2, int bannerSeq, int bannerId,
                                  String wardenLine, String breakerLine, String listenerLine,
                                  boolean wardenAttacked, boolean wardenDamaged, boolean droneAttacked,
                                  boolean turretAttacked, int p1ActionOrdinal, int p2ActionOrdinal,
                                  int p1ConsumedSlot, int p2ConsumedSlot, float directiveConflict,
                                  int droneCount, int turretCount, int droneDamagedIndex,
                                  int turretDamagedIndex, boolean turretDestroyed) {
        this(phase, wardenState, stability, dualMeter, droneActive, turretActive, healthP1,
            healthP2, bannerSeq, bannerId, wardenLine, breakerLine, listenerLine, wardenAttacked,
            wardenDamaged, droneAttacked, turretAttacked, p1ActionOrdinal, p2ActionOrdinal,
            p1ConsumedSlot, p2ConsumedSlot, directiveConflict, droneCount, turretCount,
            droneDamagedIndex, turretDamagedIndex, turretDestroyed, false, 0, false);
    }

    public Level3TurnStateMessage(TurnManager.Phase phase, WardenState wardenState, float stability,
                                  float dualMeter, boolean droneActive, boolean turretActive,
                                  float healthP1, float healthP2, int bannerSeq, int bannerId,
                                  String wardenLine, String breakerLine, String listenerLine,
                                  boolean wardenAttacked, boolean wardenDamaged, boolean droneAttacked,
                                  boolean turretAttacked, int p1ActionOrdinal, int p2ActionOrdinal,
                                  int p1ConsumedSlot, int p2ConsumedSlot, float directiveConflict,
                                  int droneCount, int turretCount, int droneDamagedIndex,
                                  int turretDamagedIndex, boolean turretDestroyed,
                                  boolean turretAiming, int turretTargetSide) {
        this(phase, wardenState, stability, dualMeter, droneActive, turretActive, healthP1,
            healthP2, bannerSeq, bannerId, wardenLine, breakerLine, listenerLine, wardenAttacked,
            wardenDamaged, droneAttacked, turretAttacked, p1ActionOrdinal, p2ActionOrdinal,
            p1ConsumedSlot, p2ConsumedSlot, directiveConflict, droneCount, turretCount,
            droneDamagedIndex, turretDamagedIndex, turretDestroyed, turretAiming,
            turretTargetSide, false);
    }

    public Level3TurnStateMessage(TurnManager.Phase phase, WardenState wardenState, float stability,
                                  float dualMeter, boolean droneActive, boolean turretActive,
                                  float healthP1, float healthP2, int bannerSeq, int bannerId,
                                  String wardenLine, String breakerLine, String listenerLine,
                                  boolean wardenAttacked, boolean wardenDamaged, boolean droneAttacked,
                                  boolean turretAttacked, int p1ActionOrdinal, int p2ActionOrdinal,
                                  int p1ConsumedSlot, int p2ConsumedSlot, float directiveConflict,
                                  int droneCount, int turretCount, int droneDamagedIndex,
                                  int turretDamagedIndex, boolean turretDestroyed,
                                  boolean turretAiming, int turretTargetSide,
                                  boolean memoryRecovered) {
        this(phase, wardenState, stability, dualMeter, droneActive, turretActive, healthP1,
            healthP2, bannerSeq, bannerId, wardenLine, breakerLine, listenerLine, wardenAttacked,
            wardenDamaged, droneAttacked, turretAttacked, p1ActionOrdinal, p2ActionOrdinal,
            p1ConsumedSlot, p2ConsumedSlot, directiveConflict, droneCount, turretCount,
            droneDamagedIndex, turretDamagedIndex, turretDestroyed, turretAiming,
            turretTargetSide, memoryRecovered, false, -1, 0, -1, 0, -1,
            false, false);
    }

    public Level3TurnStateMessage(TurnManager.Phase phase, WardenState wardenState, float stability,
                                  float dualMeter, boolean droneActive, boolean turretActive,
                                  float healthP1, float healthP2, int bannerSeq, int bannerId,
                                  String wardenLine, String breakerLine, String listenerLine,
                                  boolean wardenAttacked, boolean wardenDamaged, boolean droneAttacked,
                                  boolean turretAttacked, int p1ActionOrdinal, int p2ActionOrdinal,
                                  int p1ConsumedSlot, int p2ConsumedSlot, float directiveConflict,
                                  int droneCount, int turretCount, int droneDamagedIndex,
                                  int turretDamagedIndex, boolean turretDestroyed,
                                  boolean turretAiming, int turretTargetSide,
                                  boolean memoryRecovered, boolean reactionOpen,
                                  int reactionAttackOrdinal, int reactionTargetSide,
                                  int reactionSelectionOrdinal, int damageAppliedTargetSide,
                                  int reactionUnitIndex) {
        this(phase, wardenState, stability, dualMeter, droneActive, turretActive, healthP1,
            healthP2, bannerSeq, bannerId, wardenLine, breakerLine, listenerLine, wardenAttacked,
            wardenDamaged, droneAttacked, turretAttacked, p1ActionOrdinal, p2ActionOrdinal,
            p1ConsumedSlot, p2ConsumedSlot, directiveConflict, droneCount, turretCount,
            droneDamagedIndex, turretDamagedIndex, turretDestroyed, turretAiming, turretTargetSide,
            memoryRecovered, reactionOpen, reactionAttackOrdinal, reactionTargetSide,
            reactionSelectionOrdinal, damageAppliedTargetSide, reactionUnitIndex, false, false);
    }

    public Level3TurnStateMessage(TurnManager.Phase phase, WardenState wardenState, float stability,
                                  float dualMeter, boolean droneActive, boolean turretActive,
                                  float healthP1, float healthP2, int bannerSeq, int bannerId,
                                  String wardenLine, String breakerLine, String listenerLine,
                                  boolean wardenAttacked, boolean wardenDamaged, boolean droneAttacked,
                                  boolean turretAttacked, int p1ActionOrdinal, int p2ActionOrdinal,
                                  int p1ConsumedSlot, int p2ConsumedSlot, float directiveConflict,
                                  int droneCount, int turretCount, int droneDamagedIndex,
                                  int turretDamagedIndex, boolean turretDestroyed,
                                  boolean turretAiming, int turretTargetSide,
                                  boolean memoryRecovered, boolean reactionOpen,
                                  int reactionAttackOrdinal, int reactionTargetSide,
                                  int reactionSelectionOrdinal, int damageAppliedTargetSide,
                                  int reactionUnitIndex, boolean restorationStarted,
                                  boolean restorationCompleted) {
        this.phase = phase;
        this.wardenState = wardenState;
        this.stability = stability;
        this.dualMeter = dualMeter;
        this.droneActive = droneActive;
        this.turretActive = turretActive;
        this.healthP1 = healthP1;
        this.healthP2 = healthP2;
        this.bannerSeq = bannerSeq;
        this.bannerId = bannerId;
        this.wardenLine = wardenLine;
        this.breakerLine = breakerLine;
        this.listenerLine = listenerLine;
        this.wardenAttacked = wardenAttacked;
        this.wardenDamaged = wardenDamaged;
        this.droneAttacked = droneAttacked;
        this.turretAttacked = turretAttacked;
        this.p1ActionOrdinal = p1ActionOrdinal;
        this.p2ActionOrdinal = p2ActionOrdinal;
        this.p1ConsumedSlot = p1ConsumedSlot;
        this.p2ConsumedSlot = p2ConsumedSlot;
        this.directiveConflict = directiveConflict;
        this.droneCount = droneCount;
        this.turretCount = turretCount;
        this.droneDamagedIndex = droneDamagedIndex;
        this.turretDamagedIndex = turretDamagedIndex;
        this.turretDestroyed = turretDestroyed;
        this.turretAiming = turretAiming;
        this.turretTargetSide = turretTargetSide;
        this.memoryRecovered = memoryRecovered;
        this.reactionOpen = reactionOpen;
        this.reactionAttackOrdinal = reactionAttackOrdinal;
        this.reactionTargetSide = reactionTargetSide;
        this.reactionSelectionOrdinal = reactionSelectionOrdinal;
        this.damageAppliedTargetSide = damageAppliedTargetSide;
        this.reactionUnitIndex = reactionUnitIndex;
        this.restorationStarted = restorationStarted;
        this.restorationCompleted = restorationCompleted;
    }

    public TurnManager.Phase getPhase() { return phase; }

    public WardenState getWardenState() { return wardenState; }

    public float getStability() { return stability; }

    public float getDualMeter() { return dualMeter; }

    public boolean isDroneActive() { return droneActive; }

    public boolean isTurretActive() { return turretActive; }

    public float getHealthP1() { return healthP1; }

    public float getHealthP2() { return healthP2; }

    public int getBannerSeq() { return bannerSeq; }

    public int getBannerId() { return bannerId; }

    public String getWardenLine() { return wardenLine; }

    public String getBreakerLine() { return breakerLine; }

    public String getListenerLine() { return listenerLine; }

    public boolean isWardenAttacked() { return wardenAttacked; }

    public boolean isWardenDamaged() { return wardenDamaged; }

    public boolean isDroneAttacked() { return droneAttacked; }

    public boolean isTurretAttacked() { return turretAttacked; }

    public int getP1ActionOrdinal() { return p1ActionOrdinal; }

    public int getP2ActionOrdinal() { return p2ActionOrdinal; }

    public int getP1ConsumedSlot() { return p1ConsumedSlot; }

    public int getP2ConsumedSlot() { return p2ConsumedSlot; }

    public float getDirectiveConflict() { return directiveConflict; }

    public int getDroneCount() { return droneCount; }

    public int getTurretCount() { return turretCount; }

    public int getDroneDamagedIndex() { return droneDamagedIndex; }

    public int getTurretDamagedIndex() { return turretDamagedIndex; }

    public boolean isTurretDestroyed() { return turretDestroyed; }

    public boolean isTurretAiming() { return turretAiming; }

    public int getTurretTargetSide() { return turretTargetSide; }

    public boolean isMemoryRecovered() { return memoryRecovered; }

    public boolean isReactionOpen() { return reactionOpen; }

    public int getReactionAttackOrdinal() { return reactionAttackOrdinal; }

    public int getReactionTargetSide() { return reactionTargetSide; }

    public int getReactionSelectionOrdinal() { return reactionSelectionOrdinal; }

    public int getDamageAppliedTargetSide() { return damageAppliedTargetSide; }

    public int getReactionUnitIndex() { return reactionUnitIndex; }

    public boolean isRestorationStarted() { return restorationStarted; }

    public boolean isRestorationCompleted() { return restorationCompleted; }

    @Override
    public String getType() { return "LEVEL3_TURN_STATE"; }

    @Override
    public String serializeBody() {
        return phase.name() + ";" + wardenState.name() + ";" + stability + ";" + dualMeter + ";"
            + bit(droneActive) + ";" + bit(turretActive) + ";" + healthP1 + ";" + healthP2 + ";"
            + bannerSeq + ";" + bannerId + ";" + escape(wardenLine) + ";" + escape(breakerLine) + ";"
            + escape(listenerLine) + ";" + bit(wardenAttacked) + ";" + bit(wardenDamaged) + ";"
            + bit(droneAttacked) + ";" + bit(turretAttacked) + ";" + p1ActionOrdinal + ";"
            + p2ActionOrdinal + ";" + p1ConsumedSlot + ";" + p2ConsumedSlot + ";" + directiveConflict + ";"
            + droneCount + ";" + turretCount + ";" + droneDamagedIndex + ";" + turretDamagedIndex + ";"
            + bit(turretDestroyed) + ";" + bit(turretAiming) + ";" + turretTargetSide + ";"
            + bit(memoryRecovered) + ";" + bit(reactionOpen) + ";" + reactionAttackOrdinal + ";"
            + reactionTargetSide + ";" + reactionSelectionOrdinal + ";" + damageAppliedTargetSide
            + ";" + reactionUnitIndex + ";" + bit(restorationStarted) + ";"
            + bit(restorationCompleted);
    }

    public static Level3TurnStateMessage deserialize(String body) {
        String[] p = body.split(";", -1);
        int p1Action = p.length > 17 ? Integer.parseInt(p[17]) : -1;
        int p2Action = p.length > 18 ? Integer.parseInt(p[18]) : -1;
        int p1Consumed = p.length > 19 ? Integer.parseInt(p[19]) : -1;
        int p2Consumed = p.length > 20 ? Integer.parseInt(p[20]) : -1;
        float directiveConflict = p.length > 21 ? Float.parseFloat(p[21]) : 100f;
        int droneCount = p.length > 22 ? Integer.parseInt(p[22]) : (p[4].equals("1") ? 1 : 0);
        int turretCount = p.length > 23 ? Integer.parseInt(p[23]) : (p[5].equals("1") ? 1 : 0);
        int droneDamagedIndex = p.length > 24 ? Integer.parseInt(p[24]) : -1;
        int turretDamagedIndex = p.length > 25 ? Integer.parseInt(p[25]) : -1;
        boolean turretDestroyed = p.length > 26
            ? p[26].equals("1") : turretDamagedIndex >= turretCount;
        boolean turretAiming = p.length > 27 && p[27].equals("1");
        int turretTargetSide = p.length > 28 ? Integer.parseInt(p[28]) : 0;
        boolean memoryRecovered = p.length > 29 && p[29].equals("1");
        boolean reactionOpen = p.length > 30 && p[30].equals("1");
        int reactionAttackOrdinal = p.length > 31 ? Integer.parseInt(p[31]) : -1;
        int reactionTargetSide = p.length > 32 ? Integer.parseInt(p[32]) : 0;
        int reactionSelectionOrdinal = p.length > 33 ? Integer.parseInt(p[33]) : -1;
        int damageAppliedTargetSide = p.length > 34 ? Integer.parseInt(p[34]) : 0;
        int reactionUnitIndex = p.length > 35 ? Integer.parseInt(p[35]) : -1;
        boolean restorationStarted = p.length > 36 && p[36].equals("1");
        boolean restorationCompleted = p.length > 37 && p[37].equals("1");
        return new Level3TurnStateMessage(TurnManager.Phase.valueOf(p[0]), WardenState.valueOf(p[1]),
            Float.parseFloat(p[2]), Float.parseFloat(p[3]), p[4].equals("1"), p[5].equals("1"),
            Float.parseFloat(p[6]), Float.parseFloat(p[7]), Integer.parseInt(p[8]), Integer.parseInt(p[9]),
            p[10], p[11], p[12], p[13].equals("1"), p[14].equals("1"), p[15].equals("1"), p[16].equals("1"),
            p1Action, p2Action, p1Consumed, p2Consumed, directiveConflict,
            droneCount, turretCount, droneDamagedIndex, turretDamagedIndex, turretDestroyed,
            turretAiming, turretTargetSide, memoryRecovered, reactionOpen,
            reactionAttackOrdinal, reactionTargetSide, reactionSelectionOrdinal,
            damageAppliedTargetSide, reactionUnitIndex, restorationStarted,
            restorationCompleted);
    }

    private static int bit(boolean b) { return b ? 1 : 0; }

    // Flavour text never contains ';', but keep this safe against a stray one anyway
    private static String escape(String s) { return s.replace(';', ','); }
}
