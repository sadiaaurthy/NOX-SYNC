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

    public Level3TurnStateMessage(TurnManager.Phase phase, WardenState wardenState, float stability,
                                  float dualMeter, boolean droneActive, boolean turretActive,
                                  float healthP1, float healthP2, int bannerSeq, int bannerId,
                                  String wardenLine, String breakerLine, String listenerLine,
                                  boolean wardenAttacked, boolean wardenDamaged, boolean droneAttacked,
                                  boolean turretAttacked) {
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

    @Override
    public String getType() { return "LEVEL3_TURN_STATE"; }

    @Override
    public String serializeBody() {
        return phase.name() + ";" + wardenState.name() + ";" + stability + ";" + dualMeter + ";"
            + bit(droneActive) + ";" + bit(turretActive) + ";" + healthP1 + ";" + healthP2 + ";"
            + bannerSeq + ";" + bannerId + ";" + escape(wardenLine) + ";" + escape(breakerLine) + ";"
            + escape(listenerLine) + ";" + bit(wardenAttacked) + ";" + bit(wardenDamaged) + ";"
            + bit(droneAttacked) + ";" + bit(turretAttacked);
    }

    public static Level3TurnStateMessage deserialize(String body) {
        String[] p = body.split(";", -1);
        return new Level3TurnStateMessage(TurnManager.Phase.valueOf(p[0]), WardenState.valueOf(p[1]),
            Float.parseFloat(p[2]), Float.parseFloat(p[3]), p[4].equals("1"), p[5].equals("1"),
            Float.parseFloat(p[6]), Float.parseFloat(p[7]), Integer.parseInt(p[8]), Integer.parseInt(p[9]),
            p[10], p[11], p[12], p[13].equals("1"), p[14].equals("1"), p[15].equals("1"), p[16].equals("1"));
    }

    private static int bit(boolean b) { return b ? 1 : 0; }

    // Flavour text never contains ';', but keep this safe against a stray one anyway
    private static String escape(String s) { return s.replace(';', ','); }
}
