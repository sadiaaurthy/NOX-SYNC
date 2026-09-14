package io.github.fableops.network;

import io.github.fableops.Player;

public class WorldState {
    public float p1x, p1y, p1attack;
    public float p2x, p2y, p2attack;
    public int p1direction, p2direction;

    public WorldState() {}

    public WorldState(Player p1, Player p2) {
        p1x = p1.x;
        p1y = p1.y;
        p1direction = p1.getDirection();
        p1attack = p1.getAttackTimer();
        p2x = p2.x;
        p2y = p2.y;
        p2direction = p2.getDirection();
        p2attack = p2.getAttackTimer();
    }

    public String serialize() {
        return p1x + "," + p1y + "," + p1direction + "," + p1attack + ","
            + p2x + "," + p2y + "," + p2direction + "," + p2attack;
    }

    public static WorldState deserialize(String data) {
        String[] parts = data.split(",");
        WorldState state = new WorldState();
        state.p1x = Float.parseFloat(parts[0]);
        state.p1y = Float.parseFloat(parts[1]);
        state.p1direction = Integer.parseInt(parts[2]);
        state.p1attack = Float.parseFloat(parts[3]);
        state.p2x = Float.parseFloat(parts[4]);
        state.p2y = Float.parseFloat(parts[5]);
        state.p2direction = Integer.parseInt(parts[6]);
        state.p2attack = Float.parseFloat(parts[7]);
        return state;
    }

    // Client side
    public void applyTo(Player p1, Player p2) {
        p1.applyRemote(p1x, p1y, p1direction, p1attack);
        p2.applyRemote(p2x, p2y, p2direction, p2attack);
    }
}
