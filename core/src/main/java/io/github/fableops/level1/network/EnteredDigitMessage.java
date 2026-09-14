package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

public class EnteredDigitMessage extends NetworkMessage {
    private final int positionIndex;
    private final String guess;
    private final int playerId;

    public EnteredDigitMessage(int positionIndex, String guess, int playerId) {
        this.positionIndex = positionIndex;
        this.guess = guess;
        this.playerId = playerId;
    }

    public int getPositionIndex() { return positionIndex; }

    public String getGuess() { return guess; }

    public int getPlayerId() { return playerId; }

    @Override
    public String getType() { return "ENTERED_DIGIT"; }

    @Override
    public String serializeBody() {
        return positionIndex + ";" + guess + ";" + playerId;
    }

    public static EnteredDigitMessage deserialize(String body) {
        String[] parts = body.split(";", -1);
        return new EnteredDigitMessage(Integer.parseInt(parts[0]), parts[1], Integer.parseInt(parts[2]));
    }
}
