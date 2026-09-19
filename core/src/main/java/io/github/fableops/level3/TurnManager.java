package io.github.fableops.level3;

// The Warden encounter's turn order (STORY.md's TURN ORDER example). A small, reusable state
// container - it only tracks whose turn it is and what's been chosen; Level3Controller decides
// what each phase actually does and drives the transitions
public class TurnManager {

    public enum Phase { PLAYER_TURN, RESOLUTION, WARDEN_TURN }

    private Phase phase = Phase.PLAYER_TURN;
    private PlayerActionType p1Action;
    private PlayerActionType p2Action;
    private float timer = 0f;

    public Phase getPhase() { return phase; }

    // Only takes effect during PLAYER_TURN; a stray late confirmation is silently ignored
    public void confirmP1(PlayerActionType action) {
        if (phase == Phase.PLAYER_TURN) p1Action = action;
    }

    public void confirmP2(PlayerActionType action) {
        if (phase == Phase.PLAYER_TURN) p2Action = action;
    }

    public boolean p1Confirmed() { return p1Action != null; }

    public boolean p2Confirmed() { return p2Action != null; }

    public boolean bothConfirmed() { return p1Confirmed() && p2Confirmed(); }

    public PlayerActionType getP1Action() { return p1Action; }

    public PlayerActionType getP2Action() { return p2Action; }

    // Moves to a display phase that holds for displayDelay seconds.
    public void advanceTo(Phase next, float displayDelay) {
        phase = next;
        timer = displayDelay;
    }

    // Counts down a display phase's timer. No-op (returns false) during PLAYER_TURN, which has no
    // time limit - STORY.md's Level 3 scenario card already promises "Time Limit: None"
    public boolean tick(float delta) {
        if (phase == Phase.PLAYER_TURN) return false;
        timer -= delta;
        return timer <= 0f;
    }

    public void resetForNextRound() {
        phase = Phase.PLAYER_TURN;
        p1Action = null;
        p2Action = null;
        timer = 0f;
    }

    public void reset() {
        resetForNextRound();
    }
}
