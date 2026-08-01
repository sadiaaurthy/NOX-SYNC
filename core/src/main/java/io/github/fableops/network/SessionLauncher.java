package io.github.fableops.network;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;

import java.util.function.Consumer;

import io.github.fableops.level1.Level1Screen;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;

/**
 * Starts a host/join/debug session and hands the game off to Level1Screen once ready.
 * Shared by LobbyScreen (in-game H/J/L/D menu) and the JavaFX launcher, so both entry
 * points drive the exact same connection logic.
 */
public class SessionLauncher {

    public static void host(Game game, Consumer<String> onFailure) {
        GameServer server = new GameServer();
        HostSession hostSession = new HostSession();
        new Thread(() -> {
            try {
                server.start();
                hostSession.start();
                Gdx.app.postRunnable(() ->
                    game.setScreen(new Level1Screen(server, null, hostSession, null))
                );
            } catch (Exception e) {
                if (onFailure != null) {
                    Gdx.app.postRunnable(() -> onFailure.accept("Failed to start server: " + e.getMessage()));
                }
            }
        }).start();
    }

    public static void join(Game game, String ip, Runnable onFailure) {
        GameClient client = new GameClient();
        ClientSession clientSession = new ClientSession();
        new Thread(() -> {
            try {
                client.connect(ip);
                clientSession.connect(ip);
                Gdx.app.postRunnable(() ->
                    game.setScreen(new Level1Screen(null, client, null, clientSession))
                );
            } catch (Exception e) {
                if (onFailure != null) Gdx.app.postRunnable(onFailure);
            }
        }).start();
    }

    public static void debug(Game game) {
        game.setScreen(new Level1Screen(null, null, null, null));
    }
}
