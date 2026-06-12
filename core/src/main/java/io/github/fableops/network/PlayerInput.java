package io.github.fableops.network;

public class PlayerInput {
    public boolean up, down, left, right;

    public PlayerInput() {}

    public PlayerInput(boolean up, boolean down, boolean left, boolean right) {
        this.up    = up;
        this.down  = down;
        this.left  = left;
        this.right = right;
    }

    public String serialize() {
        return (up?1:0)+","+(down?1:0)+","+(left?1:0)+","+(right?1:0);
    }

    public static PlayerInput deserialize(String data) {
        String[] p = data.split(",");
        return new PlayerInput(
            p[0].equals("1"), p[1].equals("1"),
            p[2].equals("1"), p[3].equals("1")
        );
    }
}