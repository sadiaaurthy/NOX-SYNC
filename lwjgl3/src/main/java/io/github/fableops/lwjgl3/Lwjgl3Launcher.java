package io.github.fableops.lwjgl3;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;

public final class Lwjgl3Launcher {

    // Everything uses delta time, so 30 FPS just saves CPU and GPU
    private static final int FPS = 30;

    // Blocks until the window closes
    public static void launch(ApplicationListener game) {
        new Lwjgl3Application(game, configuration());
    }

    // Menu thread. Ends the app that was preloading behind the menu, when the launcher quits
    public static void stop() {
        Application app = Gdx.app;
        if (app != null) app.exit();
    }

    // Game thread only. Hidden while a scenario window is up, so the keys go to that window
    public static void setGameWindowVisible(boolean visible) {
        Lwjgl3Window window = ((Lwjgl3Graphics) Gdx.graphics).getWindow();
        window.setVisible(visible);
        if (visible) window.focusWindow();
    }

    private static Lwjgl3ApplicationConfiguration configuration() {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("FableOps");
        // Borderless window instead of fullscreen so Alt+Tab works
        Graphics.DisplayMode display = Lwjgl3ApplicationConfiguration.getDisplayMode();
        config.setWindowedMode(display.width, display.height);
        config.setDecorated(false);
        // Starts hidden, Level 1 loads behind the opening scenario window
        config.setInitialVisible(false);
        config.useVsync(true);
        config.setForegroundFPS(FPS);
        // No sound in the game
        config.disableAudio(true);
        config.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");
        return config;
    }

    private Lwjgl3Launcher() {}
}
