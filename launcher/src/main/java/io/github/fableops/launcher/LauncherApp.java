package io.github.fableops.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

// Main menu. Hidden during a match and shown again when the game closes
public class LauncherApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // Otherwise hiding the menu would close JavaFX
        Platform.setImplicitExit(false);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("launcher.fxml"));
        Parent root = loader.load();
        LauncherController controller = loader.getController();

        // Borderless and screen-sized, like the game window.
        Rectangle2D bounds = Screen.getPrimary().getBounds();
        Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight());
        scene.getStylesheets().add(getClass().getResource("launcher.css").toExternalForm());
        scene.setOnKeyPressed(e -> controller.onKey(e.getCode()));

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("FableOps");
        stage.setScene(scene);
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
