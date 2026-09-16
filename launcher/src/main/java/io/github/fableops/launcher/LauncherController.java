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
import io.github.fableops.Role;
import io.github.fableops.lwjgl3.Lwjgl3Launcher;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageChannel;
import io.github.fableops.story.StoryBeat;

public class LauncherController {

    private static final String TITLE = "FABLEOPS";
    private static final int ACCENT_FROM = 5; // "OPS" is the accented tail
    private static final String IDLE_STATUS = "■ awaiting connection…";
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    // Star colours are built once. The link pass compares every pair, so allocating a Color
    // in there would make garbage faster than the canvas draws
    private static final Color STAR_ACCENT = Color.rgb(255, 138, 61, 0.75);
    private static final Color STAR_PLAIN = Color.rgb(237, 237, 232, 0.4);
    private static final Color[] LINK_FADE = new Color[16];
    static {
        for (int i = 0; i < LINK_FADE.length; i++) {
            LINK_FADE[i] = Color.rgb(0, 229, 255, 0.22 * (i + 1) / LINK_FADE.length);
        }
    }

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

    // The game starts while this menu is still up. The window and the GL context cost about 440ms
    // and none of it needs to know the mode or the operators, so it is all done by the time
    // someone picks one
    private Main game;
    private RoleWindow roleWindow;
    private StoryWindow storyWindow;
    private volatile boolean quitting;

    // Blocks until connected
    private interface Connection {
        void open() throws IOException;
    }

    // What a mode produced. localPlayerId is 1 on the host and in debug, 2 on the client
    private static final class Session {
        final GameServer server;
        final GameClient client;
        final HostSession hostSession;
        final ClientSession clientSession;
        final int localPlayerId;

        Session(GameServer server, GameClient client, HostSession hostSession,
                ClientSession clientSession, int localPlayerId) {
            this.server = server;
            this.client = client;
            this.hostSession = hostSession;
            this.clientSession = clientSession;
            this.localPlayerId = localPlayerId;
        }

        // null in debug, where one machine plays both sides
        MessageChannel channel() {
            return (hostSession != null) ? hostSession : clientSession;
        }

        void stop() {
            if (server != null) server.stop();
            if (client != null) client.stop();
            if (hostSession != null) hostSession.stop();
            if (clientSession != null) clientSession.stop();
        }
    }

    @FXML
    void initialize() {
        buildTitle();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClock()));
        clock.setCycleCount(Timeline.INDEFINITE);
        setUpStars();
        setAnimating(true);
        preloadGame();
    }

    // Hidden window, no match yet. It sits on a black frame until an operator is locked in
    private void preloadGame() {
        game = new Main();
        Thread thread = new Thread(() -> {
            try {
                Lwjgl3Launcher.launch(game);
            } finally {
                // Quitting has already shut the toolkit down, so there is nothing to post back to
                if (!quitting) Platform.runLater(this::onGameClosed);
            }
        }, "fableops-game");
        thread.setDaemon(true);
        thread.start();
    }

    // The match ended. Bring the menu back and start warming the next one straight away
    private void onGameClosed() {
        if (quitting) {
            Platform.exit();
            return;
        }
        if (storyWindow != null) {
            storyWindow.hide();
            storyWindow = null;
        }
        statusLabel.setText(IDLE_STATUS);
        setAnimating(true);
        menuStage().show();
        preloadGame();
    }

    private Stage menuStage() {
        return (Stage) root.getScene().getWindow();
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
        // Squared distances, so the inner loop skips the square root on pairs that are too far apart
        double linkDistSq = linkDist * linkDist;
        gc.setLineWidth(1);
        for (int i = 0; i < stars.size(); i++) {
            Star a = stars.get(i);
            for (int j = i + 1; j < stars.size(); j++) {
                Star b = stars.get(j);
                double dx = a.x - b.x, dy = a.y - b.y;
                double distSq = dx * dx + dy * dy;
                if (distSq >= linkDistSq) continue;
                double fade = 1 - Math.sqrt(distSq) / linkDist;
                int step = (int) (fade * LINK_FADE.length);
                gc.setStroke(LINK_FADE[Math.min(step, LINK_FADE.length - 1)]);
                gc.strokeLine(Math.round(a.x), Math.round(a.y), Math.round(b.x), Math.round(b.y));
            }
        }
        for (Star s : stars) {
            gc.setFill(s.accent ? STAR_ACCENT : STAR_PLAIN);
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
            case H -> onHost();
            case J -> onJoinLan();
            case L -> onJoinLocal();
            case D -> onDebug();
            case ESCAPE -> {
                if (cancelConnect == null) {
                    quitting = true;
                    // Ends the game that has been preloading behind this menu
                    Lwjgl3Launcher.stop();
                    Platform.exit();
                } else {
                    cancelConnect.run();
                    cancelConnect = null;
                    statusLabel.setText(IDLE_STATUS);
                }
            }
            default -> { }
        }
    }

    @FXML
    private void onHost() {
        GameServer server = new GameServer();
        HostSession hostSession = new HostSession();
        connect("■ hosting on " + localIp() + " - waiting for player 2 (ESC cancels)",
            "■ could not host: ",
            new Session(server, null, hostSession, null, 1),
            () -> {
                server.start();
                hostSession.start();
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
        // One machine, both sides, so operator select just assigns them
        if (cancelConnect == null) chooseRoles(new Session(null, null, null, null, 1));
    }

    private void join(String ip) {
        GameClient client = new GameClient();
        ClientSession clientSession = new ClientSession();
        connect("■ connecting to " + ip + "…",
            "■ could not connect to " + ip + ": ",
            new Session(null, client, null, clientSession, 2),
            () -> {
                client.connect(ip);
                clientSession.connect(ip);
            });
    }

    // Connects in the background. Operator select opens once both machines are on the line
    private void connect(String status, String failure, Session session, Connection connection) {
        if (cancelConnect != null) return;
        Runnable cancel = session::stop;
        cancelConnect = cancel;
        statusLabel.setText(status);
        Thread thread = new Thread(() -> {
            try {
                connection.open();
                Platform.runLater(() -> {
                    if (cancelConnect != cancel) {
                        cancel.run(); // cancelled right as it connected
                        return;
                    }
                    cancelConnect = null;
                    chooseRoles(session);
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

    // Both machines are on the line, so each side picks an operator while the game keeps warming
    private void chooseRoles(Session session) {
        roleWindow = new RoleWindow(session.localPlayerId, session.channel(),
            role -> startMatch(session, role),
            () -> backToMenu(session));
        roleWindow.open();
        menuStage().hide();
        setAnimating(false);
    }

    // The opening scene goes up first, and Level 1 builds behind it on the next game frame
    private void startMatch(Session session, Role sideOneRole) {
        storyWindow = new StoryWindow();
        storyWindow.open(StoryBeat.START);
        roleWindow.hide();
        roleWindow = null;
        game.begin(session.server, session.client, session.hostSession, session.clientSession,
            storyWindow, sideOneRole);
    }

    // ESC out of operator select: drop the connection, keep the preloaded game for the next try
    private void backToMenu(Session session) {
        session.stop();
        roleWindow = null;
        statusLabel.setText(IDLE_STATUS);
        setAnimating(true);
        menuStage().show();
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
