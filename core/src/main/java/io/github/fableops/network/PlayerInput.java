package io.github.fableops.network;

public class PlayerInput {
    public boolean up, down, left, right;
    public boolean attack;
    public boolean reload;

    public PlayerInput() {}

    public PlayerInput(boolean up, boolean down, boolean left, boolean right) {
        this(up, down, left, right, false, false);
    }

    public PlayerInput(boolean up, boolean down, boolean left, boolean right, boolean attack) {
        this(up, down, left, right, attack, false);
    }

    public PlayerInput(boolean up, boolean down, boolean left, boolean right, boolean attack, boolean reload) {
        this.up = up;
        this.down = down;
        this.left = left;
        this.right = right;
        this.attack = attack;
        this.reload = reload;
    }

    public String serialize() {
        return (up ? 1 : 0) + "," + (down ? 1 : 0) + "," + (left ? 1 : 0) + "," + (right ? 1 : 0) + ","
            + (attack ? 1 : 0) + "," + (reload ? 1 : 0);
    }

    public static PlayerInput deserialize(String data) {
        String[] p = data.split(",");
        return new PlayerInput(p[0].equals("1"), p[1].equals("1"), p[2].equals("1"), p[3].equals("1"),
            p[4].equals("1"), p[5].equals("1"));
    }
}
