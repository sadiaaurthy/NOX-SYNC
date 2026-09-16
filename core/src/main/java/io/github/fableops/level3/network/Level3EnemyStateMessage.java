package io.github.fableops.level3.network;

import java.util.ArrayList;
import java.util.List;

import io.github.fableops.network.messages.NetworkMessage;

// Host -> client: one enemy pool's positions and both players' health. Level 3 has two pools
// (Brawl and Hacker constructs), so it sends one of these per pool per tick, tagged by kind -
// a standalone sibling of EnemyStateMessage (same wire shape) instead of changing that shared
// class, which Level 1 and Level 2 still use completely unmodified
public class Level3EnemyStateMessage extends NetworkMessage {

    public enum Kind { BRAWL, HACKER }

    private final Kind kind;
    private final List<float[]> enemiesP1;
    private final List<float[]> enemiesP2;
    private final float healthP1;
    private final float healthP2;

    public Level3EnemyStateMessage(Kind kind, List<float[]> enemiesP1, List<float[]> enemiesP2,
                                   float healthP1, float healthP2) {
        this.kind = kind;
        this.enemiesP1 = enemiesP1;
        this.enemiesP2 = enemiesP2;
        this.healthP1 = healthP1;
        this.healthP2 = healthP2;
    }

    public Kind getKind() { return kind; }

    public List<float[]> getEnemiesP1() { return enemiesP1; }

    public List<float[]> getEnemiesP2() { return enemiesP2; }

    public float getHealthP1() { return healthP1; }

    public float getHealthP2() { return healthP2; }

    @Override
    public String getType() { return "LEVEL3_ENEMY_STATE"; }

    @Override
    public String serializeBody() {
        return kind.name() + ";" + serializeList(enemiesP1) + ";" + serializeList(enemiesP2)
            + ";" + healthP1 + ";" + healthP2;
    }

    private static String serializeList(List<float[]> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('~');
            sb.append(list.get(i)[0]).append(',').append(list.get(i)[1]);
        }
        return sb.toString();
    }

    public static Level3EnemyStateMessage deserialize(String body) {
        String[] parts = body.split(";", -1);
        return new Level3EnemyStateMessage(Kind.valueOf(parts[0]), deserializeList(parts[1]),
            deserializeList(parts[2]), Float.parseFloat(parts[3]), Float.parseFloat(parts[4]));
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
