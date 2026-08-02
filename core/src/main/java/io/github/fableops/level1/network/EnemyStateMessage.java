package io.github.fableops.level1.network;

import io.github.fableops.Player;
import io.github.fableops.network.messages.NetworkMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Host -> client, sent every frame so a joined client's split-screen shows both swarms.
 * Also carries both players' health, since only the host actually simulates contact
 * damage — the client just mirrors whatever the host last reported, same as positions.
 */
public class EnemyStateMessage extends NetworkMessage {
    private final List<float[]> enemiesP1;
    private final List<float[]> enemiesP2;
    private final float healthP1;
    private final float healthP2;

    public EnemyStateMessage(List<float[]> enemiesP1, List<float[]> enemiesP2, float healthP1, float healthP2) {
        this.enemiesP1 = enemiesP1;
        this.enemiesP2 = enemiesP2;
        this.healthP1 = healthP1;
        this.healthP2 = healthP2;
    }

    public List<float[]> getEnemiesP1() { return enemiesP1; }
    public List<float[]> getEnemiesP2() { return enemiesP2; }
    public float getHealthP1() { return healthP1; }
    public float getHealthP2() { return healthP2; }

    @Override
    public String getType() { return "ENEMY_STATE"; }

    @Override
    public String serializeBody() {
        return serializeList(enemiesP1) + ";" + serializeList(enemiesP2) + ";" + healthP1 + ";" + healthP2;
    }

    private static String serializeList(List<float[]> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("~");
            sb.append(list.get(i)[0]).append(",").append(list.get(i)[1]);
        }
        return sb.toString();
    }

    public static EnemyStateMessage deserialize(String body) {
        String[] parts = body.split(";", -1);
        return new EnemyStateMessage(
            deserializeList(parts[0]),
            deserializeList(parts.length > 1 ? parts[1] : ""),
            parts.length > 2 ? Float.parseFloat(parts[2]) : Player.MAX_HEALTH,
            parts.length > 3 ? Float.parseFloat(parts[3]) : Player.MAX_HEALTH
        );
    }

    private static List<float[]> deserializeList(String s) {
        List<float[]> list = new ArrayList<>();
        if (s.isEmpty()) return list;
        for (String entry : s.split("~")) {
            String[] xy = entry.split(",");
            list.add(new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])});
        }
        return list;
    }
}
