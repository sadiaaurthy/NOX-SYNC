package io.github.fableops.launcher;

import java.io.IOException;
import java.net.InetAddress;
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
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import io.github.fableops.Main;
import io.github.fableops.lwjgl3.Lwjgl3Launcher;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;

public class LauncherController {

    private static final String TITLE = "FABLEOPS";
    private static final int ACCENT_FROM = 5; // "OPS" is the accented tail
    private static final String IDLE_STATUS = "■ awaiting connection…";
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML private StackPane root;
    @FXML private Canvas starsCanvas;
    @FXML private HBox titleBox;
    @FXML private Label clockLabel;
    @FXML private TextField ipField;
    @FXML private Label statusLabel;

    private final List<Star> stars = new ArrayList<>();
    private final Random random = new Random();
    private Timeline clock;
    private AnimationTimer starTimer;
    private long frame = 0;

    // Cancels the current connection attempt, null when idle
    private Runnable cancelConnect;

    // Blocks until connected
    private interface Connection {
        Main open() throws IOException;
    }

    @FXML
    private void initialize() {
        buildTitle();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClock()));
        clock.setCycleCount(Timeline.INDEFINITE);
        setUpStars();
        setAnimating(true);
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

    private void updateClock() {
        clockLabel.setText(LocalTime.now().format(CLOCK_FORMAT));
    }

    private void setUpStars() {
        starsCanvas.widthProperty().bind(root.widthProperty());
        starsCanvas.heightProperty().bind(root.heightProperty());

        for (int i = 0; i < 45; i++) {
            stars.add(new Star(random.nextDouble() * 1000, random.nextDouble() * 680, random.nextBoolean() && random.nextBoolean()));
        }

        starTimer = new AnimationTimer() {
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
                // Only redraw every 3rd frame, the O(n^2) link pass is the slow part
                if (frame % 3 == 0) drawStars(w, h);
                frame++;
            }
        };
    }

    // Stop the animations while a match is running
    private void setAnimating(boolean animating) {
        if (animating) {
            updateClock();
            clock.play();
            starTimer.start();
        } else {
            clock.stop();
            starTimer.stop();
        }
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

    // H/J/L/D = the buttons, ESC cancels a connection or quits
    void onKey(KeyCode code) {
        if (ipField.isFocused()) {
            // While typing an IP, ESC just leaves the field
            if (code == KeyCode.ESCAPE) root.requestFocus();
            return;
        }
        switch (code) {
            case H: onHost(); break;
            case J: onJoinLan(); break;
            case L: onJoinLocal(); break;
            case D: onDebug(); break;
            case ESCAPE:
                if (cancelConnect == null) {
                    Platform.exit();
                } else {
                    cancelConnect.run();
                    cancelConnect = null;
                    statusLabel.setText(IDLE_STATUS);
                }
                break;
            default:
                break;
        }
    }

    @FXML
    private void onHost() {
        GameServer server = new GameServer();
        HostSession session = new HostSession();
        connect("■ hosting on " + localIp() + " - waiting for player 2 (ESC cancels)",
            "■ could not host: ",
            () -> { server.stop(); session.stop(); },
            () -> {
                server.start();
                session.start();
                return new Main(server, null, session, null);
            });
    }

    @FXML
    private void onJoinLan() {
        String ip = ipField.getText() == null ? "" : ipField.getText().trim();
        if (ip.isEmpty()) {
            statusLabel.setText("■ enter a host IP above, then press Enter");
            ipField.requestFocus();
            return;
        }
        join(ip);
    }

    @FXML
    private void onJoinLocal() {
        join("127.0.0.1");
    }

    @FXML
    private void onDebug() {
        if (cancelConnect == null) play(new Main(null, null, null, null));
    }

    private void join(String ip) {
        GameClient client = new GameClient();
        ClientSession session = new ClientSession();
        connect("■ connecting to " + ip + "…",
            "■ could not connect to " + ip + ": ",
            () -> { client.stop(); session.stop(); },
            () -> {
                client.connect(ip);
                session.connect(ip);
                return new Main(null, client, null, session);
            });
    }

    // Connects in the background, the game window only opens once both sides are connected
    private void connect(String status, String failure, Runnable cancel, Connection connection) {
        if (cancelConnect != null) return;
        cancelConnect = cancel;
        statusLabel.setText(status);
        Thread thread = new Thread(() -> {
            try {
                Main game = connection.open();
                Platform.runLater(() -> {
                    if (cancelConnect != cancel) {
                        cancel.run(); // cancelled right as it connected
                        return;
                    }
                    cancelConnect = null;
                    play(game);
                });
            } catch (IOException e) {
                cancel.run();
                Platform.runLater(() -> {
                    if (cancelConnect != cancel) return; // a cancel closed the sockets, not a failure
                    cancelConnect = null;
                    statusLabel.setText(failure + e.getMessage());
                });
            }
        }, "fableops-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void play(Main game) {
        Stage stage = (Stage) root.getScene().getWindow();
        stage.hide();
        setAnimating(false);
        new Thread(() -> {
            try {
                Lwjgl3Launcher.launch(game);
            } finally {
                Platform.runLater(() -> {
                    statusLabel.setText(IDLE_STATUS);
                    setAnimating(true);
                    stage.show();
                });
            }
        }, "fableops-game").start();
    }

    private static String localIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            return "this machine";
        }
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
