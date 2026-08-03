package io.github.fableops.network;

import com.badlogic.gdx.Gdx;

import java.util.function.Consumer;

import io.github.fableops.Main;
import io.github.fableops.level1.Level1Screen;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;

/**
 * Starts a host/join/debug session and hands the game off to Level1Screen once ready.
 * Shared by LobbyScreen (in-game H/J/L/D menu) and the JavaFX launcher, so both entry
 * points drive the exact same connection logic.
 *
 * Takes Main rather than the generic libGDX Game: every current caller (Main.create()
 * itself, and LobbyScreen's own `game` field) already holds a Main instance, and
 * Level1Screen needs that concrete type to be able to navigate back to LobbyScreen
 * (new LobbyScreen(game)) on mission-failure ESC without an unsafe cast.
 */
public class SessionLauncher {

    public static void host(Main game, Consumer<String> onFailure) {
        GameServer server = new GameServer();
        HostSession hostSession = new HostSession();
        new Thread(() -> {
            try {
                server.start();
                hostSession.start();
                Gdx.app.postRunnable(() ->
                    game.setScreen(new Level1Screen(game, server, null, hostSession, null))
                );
            } catch (Exception e) {
                if (onFailure != null) {
                    Gdx.app.postRunnable(() -> onFailure.accept("Failed to start server: " + e.getMessage()));
                }
            }
        }).start();
    }

    public static void join(Main game, String ip, Runnable onFailure) {
        GameClient client = new GameClient();
        ClientSession clientSession = new ClientSession();
        new Thread(() -> {
            try {
                client.connect(ip);
                clientSession.connect(ip);
                Gdx.app.postRunnable(() ->
                    game.setScreen(new Level1Screen(game, null, client, null, clientSession))
                );
            } catch (Exception e) {
                if (onFailure != null) Gdx.app.postRunnable(onFailure);
            }
        }).start();
    }

    public static void debug(Main game) {
        game.setScreen(new Level1Screen(game, null, null, null, null));
    }
}
