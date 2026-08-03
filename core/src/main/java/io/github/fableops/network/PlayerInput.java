package io.github.fableops.network;

public class PlayerInput {
    public boolean up, down, left, right;
    /** Held attack key — the host edge-detects this into single swings. */
    public boolean attack;

    public PlayerInput() {}

    public PlayerInput(boolean up, boolean down, boolean left, boolean right) {
        this(up, down, left, right, false);
    }

    public PlayerInput(boolean up, boolean down, boolean left, boolean right, boolean attack) {
        this.up     = up;
        this.down   = down;
        this.left   = left;
        this.right  = right;
        this.attack = attack;
    }

    public String serialize() {
        return (up?1:0)+","+(down?1:0)+","+(left?1:0)+","+(right?1:0)+","+(attack?1:0);
    }

    public static PlayerInput deserialize(String data) {
        String[] p = data.split(",");
        // Tolerate the older 4-field form so a stale client can't crash the host.
        boolean attack = p.length > 4 && p[4].equals("1");
        return new PlayerInput(
            p[0].equals("1"), p[1].equals("1"),
            p[2].equals("1"), p[3].equals("1"), attack
        );
    }
}