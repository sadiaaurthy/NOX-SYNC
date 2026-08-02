package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

/** Host -> client, fired alongside AlertMeterUpdateMessage so the client knows who to spawn enemies for. */
public class WrongAnswerMessage extends NetworkMessage {
    private int offendingPlayerId;

    public WrongAnswerMessage(int offendingPlayerId) {
        this.offendingPlayerId = offendingPlayerId;
    }

    public int getOffendingPlayerId() { return offendingPlayerId; }
    public void setOffendingPlayerId(int offendingPlayerId) { this.offendingPlayerId = offendingPlayerId; }

    @Override
    public String getType() { return "WRONG_ANSWER"; }

    @Override
    public String serializeBody() { return String.valueOf(offendingPlayerId); }

    public static WrongAnswerMessage deserialize(String body) {
        return new WrongAnswerMessage(Integer.parseInt(body));
    }
}