package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

public class EnteredDigitMessage extends NetworkMessage {
    private int positionIndex;
    private String guess;
    private int playerId;

    public EnteredDigitMessage(int positionIndex, String guess, int playerId) {
        this.positionIndex = positionIndex;
        this.guess = guess;
        this.playerId = playerId;
    }

    public int getPositionIndex() { return positionIndex; }
    public void setPositionIndex(int positionIndex) { this.positionIndex = positionIndex; }

    public String getGuess() { return guess; }
    public void setGuess(String guess) { this.guess = guess; }

    public int getPlayerId() { return playerId; }
    public void setPlayerId(int playerId) { this.playerId = playerId; }

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