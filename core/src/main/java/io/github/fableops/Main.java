package io.github.fableops;

import com.badlogic.gdx.Game;

public class Main extends Game {

    @Override
    public void create() {
        setScreen(new LobbyScreen(this));
    }
}