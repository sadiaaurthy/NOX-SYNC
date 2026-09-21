package io.github.fableops.level3.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: a TNT charge just went off at (x, y). The turret's own damage rides on
// Level3TurnStateMessage; this only tells the client where to play the explosion.
public class Level3TntMessage extends NetworkMessage {

    private final float x;
    private final float y;

    public Level3TntMessage(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public float getX() { return x; }

    public float getY() { return y; }

    @Override
    public String getType() { return "LEVEL3_TNT"; }

    @Override
    public String serializeBody() { return x + ";" + y; }

    public static Level3TntMessage deserialize(String body) {
        String[] p = body.split(";", -1);
        return new Level3TntMessage(Float.parseFloat(p[0]), Float.parseFloat(p[1]));
    }
}
