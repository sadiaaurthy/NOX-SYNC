package io.github.fableops;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.PlayerInput;
import io.github.fableops.network.WorldState;

public class GameScreen implements Screen {

    private ShapeRenderer shape;
    private SpriteBatch batch;
    private Player player1;
    private Player player2;
    private WorldMap world;

    private final GameServer server;
    private final GameClient client;
    private final boolean isHost;
    private final boolean isDebug;

    private static final int DIVIDER = 4;

    public GameScreen(GameServer server, GameClient client) {
        this.server  = server;
        this.client  = client;
        this.isHost  = (server != null);
        this.isDebug = (server == null && client == null);

        shape = new ShapeRenderer();
        batch = new SpriteBatch();
        world = new WorldMap();

        // P1 — always WASD
        player1 = new Player(
            200, 400,
            new Color(0.20f, 0.55f, 0.75f, 1f),
            new Color(0.00f, 0.95f, 0.95f, 1f),
            Input.Keys.W, Input.Keys.S,
            Input.Keys.A, Input.Keys.D,
            world
        );

        // P2 — always arrow keys
        player2 = new Player(
            1600, 400,
            new Color(0.38f, 0.18f, 0.65f, 1f),
            new Color(0.90f, 0.25f, 0.85f, 1f),
            Input.Keys.UP,   Input.Keys.DOWN,
            Input.Keys.LEFT, Input.Keys.RIGHT,
            world
        );

        player1.setTexture("walking.jpg", "running.jpg");
        player2.setTexture("hacker_walking.png", "hacker_run.png");

    }

    @Override
    public void render(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) Gdx.app.exit();

        if (isDebug) {
            updateAsDebug(delta);
        } else if (isHost) {
            updateAsHost(delta);
        } else {
            updateAsClient(delta);
        }

        drawWorld();
    }

    // D mode — no network, both players local on one window
    private void updateAsDebug(float delta) {
        player1.update(delta); // WASD
        player2.update(delta); // arrow keys
    }

    private void updateAsHost(float delta) {
        // P1 always WASD on host window
        player1.update(delta);

        PlayerInput p2Input;
        if (server.isClientConnected()) {
            // real client — use their input
            p2Input = server.pollClientInput();
        } else {
            // no client yet — arrow keys control P2 locally
            p2Input = new PlayerInput(
                Gdx.input.isKeyPressed(Input.Keys.UP),
                Gdx.input.isKeyPressed(Input.Keys.DOWN),
                Gdx.input.isKeyPressed(Input.Keys.LEFT),
                Gdx.input.isKeyPressed(Input.Keys.RIGHT)
            );
        }

        player2.applyInput(p2Input, delta);

        server.pushState(new WorldState(
            player1.x, player1.y,
            player2.x, player2.y
        ));
    }

    private void updateAsClient(float delta) {
        // send arrow key input to host
        PlayerInput myInput = new PlayerInput(
            Gdx.input.isKeyPressed(Input.Keys.UP),
            Gdx.input.isKeyPressed(Input.Keys.DOWN),
            Gdx.input.isKeyPressed(Input.Keys.LEFT),
            Gdx.input.isKeyPressed(Input.Keys.RIGHT)
        );
        client.pushInput(myInput);

        // apply state received from host
        WorldState state = client.pollState();
        player1.x = state.p1x;
        player1.y = state.p1y;
        player2.x = state.p2x;
        player2.y = state.p2y;

        player1.updateCamera();
        player2.updateCamera();
    }

    private void drawWorld() {
    int screenW = Gdx.graphics.getWidth();
    int screenH = Gdx.graphics.getHeight();
    int half    = (screenW - DIVIDER) / 2;

    Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

    // left half — P1 camera
    Gdx.gl.glViewport(0, 0, half, screenH);
    shape.setProjectionMatrix(player1.camera.combined);
    shape.begin(ShapeRenderer.ShapeType.Filled);
    world.draw(shape);                         // P2 uses ShapeRenderer
    shape.end();

    batch.setProjectionMatrix(player1.camera.combined);
    batch.begin();
    player1.draw(batch);   
    player2.draw(batch);                       // P1 uses texture
    batch.end();

    // right half — P2 camera
    Gdx.gl.glViewport(half + DIVIDER, 0, half, screenH);
    shape.setProjectionMatrix(player2.camera.combined);
    shape.begin(ShapeRenderer.ShapeType.Filled);
    world.draw(shape);                         // P2 uses ShapeRenderer
    shape.end();

    batch.setProjectionMatrix(player2.camera.combined);
    batch.begin();
    player1.draw(batch);    
    player2.draw(batch);                      // P1 uses texture
    batch.end();

    Gdx.gl.glViewport(0, 0, screenW, screenH);
}

    @Override public void show() {}
    @Override public void resize(int w, int h) {}
    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {}

    @Override
    public void dispose() {
        shape.dispose();
        batch.dispose();
        player1.dispose();
        player2.dispose();
        if (server != null) server.stop();
        if (client != null) client.stop();
    }
}