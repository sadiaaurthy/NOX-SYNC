package io.github.fableops.launcher;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import io.github.fableops.ResultsScreen;

/**
 * JavaFX results screen shown when Level 1 ends, styled from results.css so it matches
 * the launcher rather than the in-game HUD renderer.
 *
 * Threading: the game calls {@link #show} from the LibGDX render thread, so every touch
 * of the scene graph is marshalled onto the JavaFX Application Thread via
 * Platform.runLater. That thread only stays alive after the launcher window closes
 * because LauncherController disables implicit exit — without that, JavaFX shuts its
 * toolkit down when its last window closes and this would never appear.
 */
public class ResultsWindow implements ResultsScreen.Presenter {

    private Stage stage;

    @Override
    public void show(boolean victory, String detail) {
        Platform.runLater(() -> showOnFxThread(victory, detail));
    }

    private void showOnFxThread(boolean victory, String detail) {
        if (stage != null && stage.isShowing()) return; // already up — don't stack windows

        String state = victory ? "victory" : "defeat";

        Label eyebrow = new Label(victory ? "// REACTOR SECURED" : "// CRITICAL FAILURE");
        eyebrow.getStyleClass().add("results-eyebrow");

        Label title = new Label(victory ? "LEVEL 1 COMPLETE" : "MISSION FAILED");
        title.getStyleClass().addAll("results-title", state);

        Region rule = new Region();
        rule.getStyleClass().addAll("results-rule", state);

        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("results-detail");
        detailLabel.setWrapText(true);

        Label hint = new Label("Close this window to return to the game.");
        hint.getStyleClass().add("results-hint");

        Button dismiss = new Button("Dismiss");
        dismiss.getStyleClass().add("results-button");
        dismiss.setOnAction(e -> stage.close());

        VBox panel = new VBox(eyebrow, title, rule, detailLabel, hint, dismiss);
        panel.getStyleClass().add("results-panel");
        panel.setSpacing(18);
        panel.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(dismiss, new Insets(10, 0, 0, 0));

        StackPane root = new StackPane(panel);
        root.getStyleClass().add("results-root");
        root.setAlignment(Pos.CENTER);

        Rectangle2D bounds = Screen.getPrimary().getBounds();
        Scene scene = new Scene(root, bounds.getWidth() * 0.5, bounds.getHeight() * 0.5);
        scene.getStylesheets().add(getClass().getResource("results.css").toExternalForm());

        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("FableOps");
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.setAlwaysOnTop(true); // the game runs borderless-fullscreen underneath
        stage.show();
        stage.toFront();
    }
}
