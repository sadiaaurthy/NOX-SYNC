package io.github.fableops;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.maps.tiled.TiledMap;

public class WorldMap implements Collidable {

    // world is larger than one screen — players explore it
    public static final float WORLD_W = 1920f;
    public static final float WORLD_H = 1080f;

    private TiledMap map;
    private OrthogonalTiledMapRenderer renderer;

    public WorldMap() {
        map      = new TmxMapLoader().load("Map1.tmx");  // your file name
        renderer = new OrthogonalTiledMapRenderer(map);
    }

     public void render(OrthographicCamera camera) {
        renderer.setView(camera);
        renderer.render();
    }

    public void dispose() {
        map.dispose();
        renderer.dispose();
    }

    @Override
    public boolean collides(float x, float y, float w, float h, int playerSide) {
        if (x < 0 || y < 0 || x + w > WORLD_W || y + h > WORLD_H) return true;
        return false;
    }

    @Override
    public float getWorldWidth() { return WORLD_W; }

    @Override
    public float getWorldHeight() { return WORLD_H; }

 /*    // walls: x, y, width, height
    private static final float[][] WALLS = {
        {400,  200, 120,  30},
        {800,  400,  30, 160},
        {200,  600, 180,  30},
        {600,  700,  90,  90},
        {1100, 300, 140,  30},
        {1400, 500,  30, 180},
        {900,  800, 200,  30},
        {1600, 200, 100, 100},
        {300,  850, 160,  30},
        {1200, 700,  30, 140},
    };

    public void draw(ShapeRenderer shape) {
        // floor
        shape.setColor(0.08f, 0.12f, 0.16f, 1f);
        shape.rect(0, 0, WORLD_W, WORLD_H);

        // floor grid lines — subtle cyberpunk grid
        shape.setColor(0.12f, 0.18f, 0.22f, 1f);
        for (int x = 0; x < WORLD_W; x += 80) {
            shape.rectLine(x, 0, x, WORLD_H, 0.8f);
        }
        for (int y = 0; y < WORLD_H; y += 80) {
            shape.rectLine(0, y, WORLD_W, y, 0.8f);
        }

        // world border — cyan glow edge
        shape.setColor(0.0f, 0.8f, 0.8f, 1f);
        shape.rectLine(0,       0,       WORLD_W, 0,       3f);
        shape.rectLine(0,       0,       0,       WORLD_H, 3f);
        shape.rectLine(WORLD_W, 0,       WORLD_W, WORLD_H, 3f);
        shape.rectLine(0,       WORLD_H, WORLD_W, WORLD_H, 3f);

        // walls — dark steel
        shape.setColor(0.25f, 0.30f, 0.36f, 1f);
        for (float[] w : WALLS) {
            shape.rect(w[0], w[1], w[2], w[3]);
        }

        // wall top/right edge highlight
        shape.setColor(0.45f, 0.55f, 0.65f, 1f);
        for (float[] w : WALLS) {
            shape.rectLine(w[0],          w[1] + w[3], w[0] + w[2], w[1] + w[3], 1.5f);
            shape.rectLine(w[0] + w[2],   w[1],        w[0] + w[2], w[1] + w[3], 1.5f);
        }
    }

    public boolean collides(float x, float y, float w, float h) {
        // world boundary
        if (x < 0 || y < 0 || x + w > WORLD_W || y + h > WORLD_H) return true;

        // wall AABB
        for (float[] wall : WALLS) {
            if (x < wall[0] + wall[2] && x + w > wall[0] &&
                y < wall[1] + wall[3] && y + h > wall[1]) {
                return true;
            }
        }
        return false;
    } */
}