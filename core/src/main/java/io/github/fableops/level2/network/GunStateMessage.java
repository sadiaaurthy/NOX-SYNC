package io.github.fableops.level2.network;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: one operator's sidearm - its ammo and last shot, for the HUD and the tracer.
// There is a weapon per side, so side routes the update to the right one; owner can be 0 while
// that operator is not carrying a Sidearm item, which is why it cannot do the routing itself
public class GunStateMessage extends NetworkMessage {
    private final int side;
    private final int owner;
    private final int magazine;
    private final int spare;
    private final float reloadLeft;
    private final int shots;
    private final float fromX;
    private final float fromY;
    private final float toX;
    private final float toY;
    private final boolean hit;
    private final boolean ammoCacheCollected;

    public GunStateMessage(int side, int owner, int magazine, int spare, float reloadLeft, int shots,
                           float fromX, float fromY, float toX, float toY, boolean hit,
                           boolean ammoCacheCollected) {
        this.side = side;
        this.owner = owner;
        this.magazine = magazine;
        this.spare = spare;
        this.reloadLeft = reloadLeft;
        this.shots = shots;
        this.fromX = fromX;
        this.fromY = fromY;
        this.toX = toX;
        this.toY = toY;
        this.hit = hit;
        this.ammoCacheCollected = ammoCacheCollected;
    }

    public int getSide() { return side; }

    public int getOwner() { return owner; }

    public int getMagazine() { return magazine; }

    public int getSpare() { return spare; }

    public float getReloadLeft() { return reloadLeft; }

    public int getShots() { return shots; }

    public float getFromX() { return fromX; }

    public float getFromY() { return fromY; }

    public float getToX() { return toX; }

    public float getToY() { return toY; }

    public boolean isHit() { return hit; }

    public boolean isAmmoCacheCollected() { return ammoCacheCollected; }

    @Override
    public String getType() { return "GUN_STATE"; }

    @Override
    public String serializeBody() {
        return side + "," + owner + "," + magazine + "," + spare + "," + reloadLeft + "," + shots + ","
            + fromX + "," + fromY + "," + toX + "," + toY + "," + (hit ? 1 : 0) + ","
            + (ammoCacheCollected ? 1 : 0);
    }

    public static GunStateMessage deserialize(String body) {
        String[] p = body.split(",");
        return new GunStateMessage(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
            Integer.parseInt(p[3]), Float.parseFloat(p[4]), Integer.parseInt(p[5]), Float.parseFloat(p[6]),
            Float.parseFloat(p[7]), Float.parseFloat(p[8]), Float.parseFloat(p[9]), p[10].equals("1"),
            p[11].equals("1"));
    }
}
