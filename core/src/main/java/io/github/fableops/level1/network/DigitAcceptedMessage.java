package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

public class DigitAcceptedMessage extends NetworkMessage {
    private int positionIndex;

    public DigitAcceptedMessage(int positionIndex) {
        this.positionIndex = positionIndex;
    }

    public int getPositionIndex() { return positionIndex; }
    public void setPositionIndex(int positionIndex) { this.positionIndex = positionIndex; }

    @Override
    public String getType() { return "DIGIT_ACCEPTED"; }

    @Override
    public String serializeBody() {
        return String.valueOf(positionIndex);
    }

    public static DigitAcceptedMessage deserialize(String body) {
        return new DigitAcceptedMessage(Integer.parseInt(body));
    }
}