package io.github.fableops.story;

import io.github.fableops.network.messages.NetworkMessage;

// Either side -> the other: this machine's player confirmed the scenario window for beat
public class StoryReadyMessage extends NetworkMessage {

    static final String TYPE = "STORY_READY";

    private final StoryBeat beat;

    public StoryReadyMessage(StoryBeat beat) {
        this.beat = beat;
    }

    @Override
    public String getType() { return TYPE; }

    @Override
    public String serializeBody() { return beat.name(); }
}
