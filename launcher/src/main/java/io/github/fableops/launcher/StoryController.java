package io.github.fableops.launcher;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import io.github.fableops.story.StoryBeat;

// Fills story.fxml: the narration types itself out and the scenario window unfolds.
// The mission failed scene uses the same layout in red
public class StoryController {

    private static final String CURSOR = "▌";
    private static final String FAILED_STYLE = "failed";

    @FXML private StackPane root;
    @FXML private Label narration;
    // Same text but invisible, so the window below doesn't move while the narration types
    @FXML private Label narrationSpace;
    @FXML private VBox scenarioWindow;
    @FXML private Label heading;
    @FXML private GridPane rows;
    @FXML private Label prompt;
    @FXML private Label ready;

    private final Timeline typing = new Timeline(new KeyFrame(Duration.millis(22), e -> typeNext()));
    private final FadeTransition fadeIn = new FadeTransition(Duration.millis(220));
    private final ScaleTransition unfold = new ScaleTransition(Duration.millis(260));
    private final TranslateTransition shake = new TranslateTransition(Duration.millis(45));
    private String text = "";
    private int typed;
    private boolean failure;

    @FXML
    private void initialize() {
        typing.setCycleCount(Animation.INDEFINITE);
        fadeIn.setNode(root);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        unfold.setNode(scenarioWindow);
        unfold.setFromY(0);
        unfold.setToY(1);
        shake.setNode(scenarioWindow);
        shake.setFromX(-7);
        shake.setToX(7);
        shake.setCycleCount(6);
        shake.setAutoReverse(true);
        shake.setOnFinished(e -> scenarioWindow.setTranslateX(0));
    }

    void show(StoryBeat beat) {
        failure = false;
        root.getStyleClass().remove(FAILED_STYLE);
        ready.setVisible(true);
        fill(beat.getHeading(), beat.getNarration(), beat.getRows());
        setReady(0);
        setConfirmed(false);
    }

    // canRestart is false on the client, which waits for the host
    void showFailure(String heading, String narration, String[][] rows, boolean canRestart) {
        failure = true;
        if (!root.getStyleClass().contains(FAILED_STYLE)) root.getStyleClass().add(FAILED_STYLE);
        ready.setVisible(false);
        fill(heading, narration, rows);
        prompt.setText(canRestart ? "[ENTER] RESTART     [ESC] MAIN MENU" : "WAITING FOR THE HOST     [ESC] MAIN MENU");
        shake.playFromStart();
    }

    boolean isTyping() {
        return typing.getStatus() == Animation.Status.RUNNING;
    }

    void finishTyping() {
        typing.stop();
        narration.setText(text);
    }

    void setReady(int players) {
        ready.setText("■ " + players + "/2 READY");
    }

    void setConfirmed(boolean confirmed) {
        if (failure) {
            if (confirmed) prompt.setText("RESTARTING...");
        } else {
            prompt.setText(confirmed ? "WAITING FOR YOUR PARTNER" : "[ENTER] CONFIRM");
        }
    }

    private void fill(String title, String story, String[][] data) {
        text = "◆ " + story;
        narrationSpace.setText(text + CURSOR);
        typed = 0;
        narration.setText(CURSOR);
        typing.playFromStart();

        heading.setText("[ " + title + " ]");
        rows.getChildren().clear();
        for (int i = 0; i < data.length; i++) {
            Label label = new Label(data[i][0]);
            Label colon = new Label(":");
            Label value = new Label(data[i][1]);
            label.getStyleClass().add("row-label");
            colon.getStyleClass().add("row-label");
            value.getStyleClass().add("row-value");
            value.setWrapText(true);
            value.setMaxWidth(Double.MAX_VALUE);
            rows.addRow(i, label, colon, value);
            // Long values wrap, so the label stays level with their first line
            GridPane.setValignment(label, VPos.TOP);
            GridPane.setValignment(colon, VPos.TOP);
        }
        fadeIn.playFromStart();
        unfold.playFromStart();
    }

    private void typeNext() {
        if (typed >= text.length()) {
            finishTyping();
            return;
        }
        typed++;
        narration.setText(text.substring(0, typed) + CURSOR);
    }
}
