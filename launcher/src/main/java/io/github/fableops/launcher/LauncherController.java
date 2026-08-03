package io.github.fableops.launcher;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import io.github.fableops.ResultsScreen;
import io.github.fableops.lwjgl3.Lwjgl3Launcher;

public class LauncherController {

    private static final String TITLE = "FABLEOPS";
    private static final int ACCENT_FROM = 5; // "OPS" is the accented tail

    @FXML private StackPane root;
    @FXML private Canvas starsCanvas;
    @FXML private HBox titleBox;
    @FXML private Label clockLabel;
    @FXML private TextField ipField;
    @FXML private Label statusLabel;
    @FXML private Button hostButton;
    @FXML private Button joinLanButton;
    @FXML private Button joinLocalButton;
    @FXML private Button debugButton;

    private final List<Star> stars = new ArrayList<>();
    private final Random random = new Random();
    private long frame = 0;

    @FXML
    private void initialize() {
        buildTitle();
        startClock();
        setUpStars();
    }

    private void buildTitle() {
        double[] rotations = {-1, 1.5, -0.5, -1, 1.5, -0.5, -1, 1.5};
        for (int i = 0; i < TITLE.length(); i++) {
            Label letter = new Label(String.valueOf(TITLE.charAt(i)));
            letter.getStyleClass().add("title-letter");
            if (i >= ACCENT_FROM) letter.getStyleClass().add("accent");
            letter.setRotate(rotations[i % rotations.length]);
            titleBox.getChildren().add(letter);
        }
    }

    private void startClock() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), e ->
            clockLabel.setText(LocalTime.now().format(fmt))
        ));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        clockLabel.setText(LocalTime.now().format(fmt));
    }

    private void setUpStars() {
        starsCanvas.widthProperty().bind(root.widthProperty());
        starsCanvas.heightProperty().bind(root.heightProperty());

        for (int i = 0; i < 45; i++) {
            stars.add(new Star(random.nextDouble() * 1000, random.nextDouble() * 680, random.nextBoolean() && random.nextBoolean()));
        }

        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double w = starsCanvas.getWidth();
                double h = starsCanvas.getHeight();
                for (Star s : stars) {
                    s.x += s.vx;
                    s.y += s.vy;
                    if (s.x < 0) s.x = w; if (s.x > w) s.x = 0;
                    if (s.y < 0) s.y = h; if (s.y > h) s.y = 0;
                }
                // Redraw (including the O(n^2) link-line pass) only every 3rd frame —
                // motion still reads smooth at this drift speed, but the draw cost drops 3x.
                if (frame % 3 == 0) drawStars(w, h);
                frame++;
            }
        };
        timer.start();
    }

    private void drawStars(double w, double h) {
        GraphicsContext gc = starsCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        double linkDist = 90;
        gc.setLineWidth(1);
        for (int i = 0; i < stars.size(); i++) {
            for (int j = i + 1; j < stars.size(); j++) {
                Star a = stars.get(i), b = stars.get(j);
                double dx = a.x - b.x, dy = a.y - b.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < linkDist) {
                    double alpha = (1 - dist / linkDist) * 0.22;
                    gc.setStroke(Color.rgb(0, 229, 255, alpha));
                    gc.strokeLine(Math.round(a.x), Math.round(a.y), Math.round(b.x), Math.round(b.y));
                }
            }
        }
        for (Star s : stars) {
            double flicker = s.accent ? 0.75 : 0.4;
            gc.setFill(s.accent ? Color.rgb(255, 138, 61, flicker) : Color.rgb(237, 237, 232, flicker));
            gc.fillRect(Math.round(s.x), Math.round(s.y), 2, 2);
        }
    }

    @FXML
    private void onHost() {
        launchGame("host", null);
    }

    @FXML
    private void onJoinLan() {
        String ip = ipField.getText();
        if (ip == null || ip.isBlank()) {
            statusLabel.setText("■ enter a host IP above, then press J or Enter");
            ipField.requestFocus();
            return;
        }
        launchGame("join", ip.trim());
    }

    @FXML
    private void onJoinLocal() {
        launchGame("join", "127.0.0.1");
    }

    @FXML
    private void onDebug() {
        launchGame("debug", null);
    }

    private void launchGame(String mode, String ip) {
        // Keep the JavaFX toolkit alive after this window closes, otherwise JavaFX tears
        // itself down with its last window and the results screen could never be shown.
        Platform.setImplicitExit(false);
        ResultsScreen.setPresenter(new ResultsWindow());

        Stage stage = (Stage) root.getScene().getWindow();
        stage.close();
        // Runs on a plain (non-daemon) thread so the JVM stays alive alongside the
        // now-idle JavaFX Application Thread.
        new Thread(() -> {
            Lwjgl3Launcher.launch(mode, ip);
            // The LibGDX window has closed for good — let JavaFX exit too, or the JVM
            // would hang on the still-running toolkit thread.
            Platform.exit();
        }, "fableops-game").start();
    }

    private static final class Star {
        double x, y;
        final double vx, vy;
        final boolean accent;

        Star(double x, double y, boolean accent) {
            this.x = x;
            this.y = y;
            this.accent = accent;
            double angle = Math.random() * Math.PI * 2;
            double speed = 0.15;
            this.vx = Math.cos(angle) * speed;
            this.vy = Math.sin(angle) * speed;
        }
    }
}
