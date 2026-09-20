package io.github.fableops.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.badlogic.gdx.Gdx;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import io.github.fableops.lwjgl3.Lwjgl3Launcher;
import io.github.fableops.story.StoryBeat;
import io.github.fableops.story.StoryGate;

// Borderless, screen-sized window for the scenario scenes and the mission failed scene. The game window
// hides while it's up, so ENTER and ESC come here instead of going to the game
final class StoryWindow implements StoryGate.View {

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final StoryController controller;
    // FX thread only
    private StoryBeat beat;
    private boolean failure;
    private boolean confirmed;
    private Runnable onConfirm;
    private Runnable onRestart;

    StoryWindow() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("story.fxml"));
        Parent root;
        try {
            root = loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        controller = loader.getController();

        Rectangle2D bounds = Screen.getPrimary().getBounds();
        Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight());
        scene.getStylesheets().add(getClass().getResource("story.css").toExternalForm());
        scene.setOnKeyPressed(e -> onKey(e.getCode()));
        stage.setTitle("FableOps");
        stage.setScene(scene);
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
    }

    // FX thread. The launcher opens the first scene itself, before the game window exists
    void open(StoryBeat beat) {
        this.beat = beat;
        failure = false;
        confirmed = false;
        onConfirm = null;
        controller.show(beat);
        bringToFront();
    }

    // FX thread
    void hide() {
        controller.finishTyping();
        stage.hide();
        beat = null;
        failure = false;
        onConfirm = null;
        onRestart = null;
    }

    @Override
    public void show(StoryBeat beat, Runnable onConfirm) {
        Platform.runLater(() -> {
            if (beat != this.beat || !stage.isShowing()) open(beat);
            this.onConfirm = onConfirm;
            // ENTER may have been pressed while the game was still starting
            if (confirmed) onConfirm.run();
            hideGameWindow();
        });
    }

    @Override
    public void setReady(int players) {
        Platform.runLater(() -> controller.setReady(players));
    }

    @Override
    public void showFailure(String heading, String narration, String[][] rows, Runnable onRestart) {
        Platform.runLater(() -> {
            beat = null;
            failure = true;
            confirmed = false;
            this.onRestart = onRestart;
            controller.showFailure(heading, narration, rows, onRestart != null);
            bringToFront();
            hideGameWindow();
        });
    }

    @Override
    public void close() {
        // The game window comes back first, so the desktop never shows in between
        Lwjgl3Launcher.setGameWindowVisible(true);
        Platform.runLater(() -> {
            hide();
            // Hiding this window can hand focus to another app, so take it back
            Gdx.app.postRunnable(() -> Lwjgl3Launcher.setGameWindowVisible(true));
        });
    }

    private void bringToFront() {
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private static void hideGameWindow() {
        Gdx.app.postRunnable(() -> Lwjgl3Launcher.setGameWindowVisible(false));
    }

    private void onKey(KeyCode code) {
        if (code == KeyCode.ESCAPE) {
            // Leaves the match, like ESC in the game
            if (Gdx.app != null) Gdx.app.exit();
            return;
        }
        if (code != KeyCode.ENTER || beat == StoryBeat.ENDING || (beat == null && !failure)) return;
        if (controller.isTyping()) {
            controller.finishTyping();
        } else if (!confirmed && (!failure || onRestart != null)) {
            confirmed = true;
            controller.setConfirmed(true);
            Runnable action = failure ? onRestart : onConfirm;
            if (action != null) action.run();
        }
    }
}
