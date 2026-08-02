package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

/** Host -> client — broadcast the moment either player dies or the Alert Meter maxes out, before the actual restart. */
public class MissionFailedMessage extends NetworkMessage {
    @Override
    public String getType() { return "MISSION_FAILED"; }

    @Override
    public String serializeBody() { return ""; }
}