package io.github.fableops.network;

public class WorldState {
    public float p1x, p1y;
    public float p2x, p2y;

    public WorldState() {}

    public WorldState(float p1x, float p1y, float p2x, float p2y) {
        this.p1x = p1x;
        this.p1y = p1y;
        this.p2x = p2x;
        this.p2y = p2y;
    }

    // serialize to simple comma-separated string — no object streams
    public String serialize() {
        return p1x + "," + p1y + "," + p2x + "," + p2y;
    }

    public static WorldState deserialize(String data) {
        String[] parts = data.split(",");
        return new WorldState(
            Float.parseFloat(parts[0]),
            Float.parseFloat(parts[1]),
            Float.parseFloat(parts[2]),
            Float.parseFloat(parts[3])
        );
    }
}