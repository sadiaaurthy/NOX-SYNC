package io.github.fableops.level3.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: the Warden has stood down, show the ending scenario window (StoryBeat.ENDING).
// Mirrors Level2's Level3StartMessage - an empty marker, the beat itself carries the text
public class Level3EndingMessage extends NetworkMessage {
    @Override
    public String getType() { return "LEVEL3_ENDING"; }

    @Override
    public String serializeBody() { return ""; }
}
