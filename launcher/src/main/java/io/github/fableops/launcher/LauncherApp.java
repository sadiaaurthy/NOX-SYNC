package io.github.fableops.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** Pre-game JavaFX launcher — host/join/debug entry point, styled to match the in-game HUD. */
public class LauncherApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("launcher.fxml"));
        Parent root = loader.load();

        // Borderless, sized to the full screen — matches the game window's own
        // windowed-fullscreen treatment instead of floating as a small centered window.
        Rectangle2D bounds = Screen.getPrimary().getBounds();
        Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight());
        scene.getStylesheets().add(getClass().getResource("launcher.css").toExternalForm());
        // No title bar in undecorated mode means no OS-provided close button either.
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) Platform.exit();
        });

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("FableOps");
        stage.setScene(scene);
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
