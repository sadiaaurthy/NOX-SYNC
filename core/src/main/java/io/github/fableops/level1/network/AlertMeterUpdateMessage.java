package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

public class AlertMeterUpdateMessage extends NetworkMessage {
    private int value;

    public AlertMeterUpdateMessage(int value) {
        this.value = value;
    }

    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }

    @Override
    public String getType() { return "ALERT_METER_UPDATE"; }

    @Override
    public String serializeBody() {
        return String.valueOf(value);
    }

    public static AlertMeterUpdateMessage deserialize(String body) {
        return new AlertMeterUpdateMessage(Integer.parseInt(body));
    }
}