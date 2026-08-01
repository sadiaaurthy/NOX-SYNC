package io.github.fableops.level1.model;

import io.github.fableops.level1.network.CodeFragmentPayload;

public abstract class StageData {
    protected final int totalPositions;
    protected final boolean[] solved;
    protected final String[] correctValues;
    protected final int[] ownerPlayerId;

    protected StageData(int totalPositions) {
        this.totalPositions = totalPositions;
        this.solved = new boolean[totalPositions];
        this.correctValues = new String[totalPositions];
        this.ownerPlayerId = new int[totalPositions];
    }

    public boolean isOwnedBy(int positionIndex, int playerId) {
        return ownerPlayerId[positionIndex] == playerId;
    }

    public String correctValue(int positionIndex) {
        return correctValues[positionIndex];
    }

    public void markSolved(int positionIndex) {
        solved[positionIndex] = true;
    }

    public boolean allPositionsSolved() {
        for (boolean b : solved) {
            if (!b) return false;
        }
        return true;
    }

    public abstract CodeFragmentPayload viewFor(int playerId);
}