package io.github.fableops;

import com.badlogic.gdx.Game;

import io.github.fableops.network.SessionLauncher;

public class Main extends Game {

    /** Null when launched normally — falls back to the in-game H/J/L/D lobby. */
    private final String launchMode; // "host" | "join" | "debug" | null
    private final String launchIp;   // only used when launchMode is "join"

    public Main() {
        this(null, null);
    }

    /** Used by the JavaFX launcher to skip LobbyScreen and connect immediately. */
    public Main(String launchMode, String launchIp) {
        this.launchMode = launchMode;
        this.launchIp = launchIp;
    }

    @Override
    public void create() {
        if (launchMode == null) {
            setScreen(new LobbyScreen(this));
            return;
        }
        switch (launchMode) {
            case "host":
                SessionLauncher.host(this, message -> setScreen(new LobbyScreen(this)));
                break;
            case "join":
                SessionLauncher.join(this, launchIp, () -> setScreen(new LobbyScreen(this)));
                break;
            case "debug":
                SessionLauncher.debug(this);
                break;
            default:
                setScreen(new LobbyScreen(this));
        }
    }
}
