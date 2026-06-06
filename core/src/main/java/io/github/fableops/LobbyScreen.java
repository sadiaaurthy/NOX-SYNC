package io.github.fableops;

import java.net.InetAddress;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;

public class LobbyScreen implements Screen {

    private final Main game;
    private ShapeRenderer shape;
    private BitmapFont font;
    private SpriteBatch batch;

    private String ipInput = "";
    private String statusMessage = "H = Host  |  J = Join (LAN)  |  L = Join Local  |  D = Debug";
    private boolean waitingForIP = false;

    public LobbyScreen(Main game) {
        this.game = game;
        shape = new ShapeRenderer();
        font = new BitmapFont();
        font.getData().setScale(2f);
        batch = new SpriteBatch();
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.08f, 0.12f, 0.18f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        shape.begin(ShapeRenderer.ShapeType.Filled);
        shape.setColor(0.12f, 0.18f, 0.26f, 1f);
        shape.rect(600, 440, 720, 200);
        shape.setColor(0f, 0.83f, 1f, 1f);
        shape.rectLine(600, 640, 1320, 640, 3f);
        shape.end();

        batch.begin();
        font.setColor(Color.CYAN);
        font.draw(batch, "FableOps", 820, 580);
        font.setColor(Color.WHITE);
        font.draw(batch, statusMessage, 620, 520);
        if (waitingForIP) {
            font.draw(batch, "IP: " + ipInput, 620, 470);
        }
        batch.end();

        handleInput();
    }

    private void handleInput() {
        if (!waitingForIP) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.H)) {
                statusMessage = "Hosting... your IP: " + getLocalIP();
                startHost();
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.J)) {
                waitingForIP = true;
                statusMessage = "Type host IP then press ENTER:";
                ipInput = "";
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.L)) {
                statusMessage = "Connecting to localhost...";
                startClient("127.0.0.1");
            }
            // D = debug mode, no networking, both players local
            if (Gdx.input.isKeyJustPressed(Input.Keys.D)) {
                game.setScreen(new GameScreen(null, null));
            }
        } else {
            for (int k = Input.Keys.NUM_0; k <= Input.Keys.NUM_9; k++) {
                if (Gdx.input.isKeyJustPressed(k)) {
                    ipInput += (k - Input.Keys.NUM_0);
                }
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.PERIOD)) ipInput += ".";
            if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE) && !ipInput.isEmpty()) {
                ipInput = ipInput.substring(0, ipInput.length() - 1);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
                statusMessage = "Connecting to " + ipInput + "...";
                startClient(ipInput);
            }
        }
    }

    private void startHost() {
        GameServer server = new GameServer();
        new Thread(() -> {
            try {
                server.start();
                Gdx.app.postRunnable(() ->
                    game.setScreen(new GameScreen(server, null))
                );
            } catch (Exception e) {
                Gdx.app.postRunnable(() ->
                    statusMessage = "Failed to start server: " + e.getMessage()
                );
            }
        }).start();
    }

    private void startClient(String ip) {
        GameClient client = new GameClient();
        new Thread(() -> {
            try {
                client.connect(ip);
                Gdx.app.postRunnable(() ->
                    game.setScreen(new GameScreen(null, client))
                );
            } catch (Exception e) {
                Gdx.app.postRunnable(() -> {
                    statusMessage = "Connection failed. Check IP and try again.";
                    waitingForIP = false;
                });
            }
        }).start();
    }

    private String getLocalIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override public void show() {}
    @Override public void resize(int w, int h) {}
    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {}

    @Override
    public void dispose() {
        shape.dispose();
        font.dispose();
        batch.dispose();
    }
}