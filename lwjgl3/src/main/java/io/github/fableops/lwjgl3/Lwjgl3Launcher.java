package io.github.fableops.lwjgl3;

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import io.github.fableops.Main;

/** Launches the desktop (LWJGL3) application. */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return; // This handles macOS support and helps on Windows.

        String mode = argValue(args, "--mode=");
        String ip = argValue(args, "--ip=");
        launch(mode, ip, hasFlag(args, "--windowed"));
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }

    private static String argValue(String[] args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) return arg.substring(prefix.length());
        }
        return null;
    }

    /**
     * Starts the game directly with a given mode ("host"/"join"/"debug"), skipping
     * LobbyScreen. Used both by the --mode= CLI flag above and by the JavaFX launcher
     * calling in-process after closing its own window. mode == null falls back to the
     * normal LobbyScreen entry point.
     */
    public static void launch(String mode, String ip) {
        launch(mode, ip, false);
    }

    /**
     * windowed=true opens a normal decorated, resizable window instead of the borderless
     * full-display one. Added for projector setup: the default window is undecorated and
     * sized to whichever display was primary at startup, so if the projector is a
     * secondary display the game appears on the laptop screen and cannot be dragged
     * across. A decorated window can be moved and resized onto the projector, and
     * Level1Screen re-derives both its UI and world cameras on every resize, so the
     * layout follows it. Default is unchanged.
     */
    public static void launch(String mode, String ip, boolean windowed) {
        new Lwjgl3Application(new Main(mode, ip), getDefaultConfiguration(windowed));
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration(boolean windowed) {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("firstJava");
        //// Vsync limits the frames per second to what your hardware can display, and helps eliminate
        //// screen tearing. This setting doesn't always work on Linux, so the line after is a safeguard.
        configuration.useVsync(true);
        //// Limits FPS to the refresh rate of the currently active monitor, plus 1 to try to match fractional
        //// refresh rates. The Vsync setting above should limit the actual FPS to match the monitor.
        configuration.setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1);
        //// If you remove the above line and set Vsync to false, you can get unlimited FPS, which can be
        //// useful for testing performance, but can also be very stressful to some hardware.
        //// You may also need to configure GPU drivers to fully disable Vsync; this can cause screen tearing.

        // Borderless window sized to the full display, not exclusive fullscreen — exclusive
        // fullscreen was blocking Alt+Tab from reliably switching away from the game.
        Graphics.DisplayMode display = Lwjgl3ApplicationConfiguration.getDisplayMode();
        if (windowed) {
            // 16:9 at a size that fits comfortably on a laptop panel, so the window can be
            // dragged onto a projector and resized there during setup.
            configuration.setWindowedMode(1280, 720);
            configuration.setDecorated(true);
        } else {
            configuration.setWindowedMode(display.width, display.height);
            configuration.setDecorated(false);
        }
        //// You can change these files; they are in lwjgl3/src/main/resources/ .
        //// They can also be loaded from the root of assets/ .
        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");

        //// This could improve compatibility with Windows machines with buggy OpenGL drivers, Macs
        //// with Apple Silicon that have to emulate compatibility with OpenGL anyway, and more.
        //// This uses the dependency `com.badlogicgames.gdx:gdx-lwjgl3-angle` to function.
        //// You would need to add this line to lwjgl3/build.gradle , below the dependency on `gdx-backend-lwjgl3`:
        ////     implementation "com.badlogicgames.gdx:gdx-lwjgl3-angle:$gdxVersion"
        //// You can choose to add the following line and the mentioned dependency if you want; they
        //// are not intended for games that use GL30 (which is compatibility with OpenGL ES 3.0).
        //// Know that it might not work well in some cases.
//        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 0, 0);

        return configuration;
    }
}
