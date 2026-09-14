package io.github.fableops;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;

import io.github.fableops.level1.Level1Screen;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;

// The launcher connects first and passes the connections in (all null in debug)
public class Main extends Game {

    // Caps the frame time so one long frame can't move a body through a wall
    private static final float MAX_DELTA = 1f / 20f;

    private final GameServer server;
    private final GameClient client;
    private final HostSession hostSession;
    private final ClientSession clientSession;

    public Main(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession) {
        this.server = server;
        this.client = client;
        this.hostSession = hostSession;
        this.clientSession = clientSession;
    }

    @Override
    public void create() {
        setScreen(new Level1Screen(this, server, client, hostSession, clientSession));
    }

    @Override
    public void render() {
        if (screen != null) screen.render(Math.min(Gdx.graphics.getDeltaTime(), MAX_DELTA));
    }

    // Game.dispose() only calls hide(), so dispose the screen here
    @Override
    public void dispose() {
        if (screen != null) screen.dispose();
    }
}
