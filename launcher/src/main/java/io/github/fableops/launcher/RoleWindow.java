package io.github.fableops.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import io.github.fableops.Role;
import io.github.fableops.network.messages.RoleLockMessage;
import io.github.fableops.network.session.MessageChannel;

// Operator select, shown once both machines are connected. There are only two operators, so one
// locked pick settles both sides and nothing has to agree on a second value
final class RoleWindow {

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final RoleController controller;

    // 1 on the host and in debug, 2 on the client
    private int localPlayerId;
    // null in debug, where one machine picks for both sides
    private MessageChannel session;
    // Handed side 1's operator, which is all the game needs
    private Consumer<Role> onReady;
    private Runnable onCancel;

    private Role selected = Role.BREAKER;
    private boolean locked;
    private Role remoteLocked;
    private boolean finished;

    RoleWindow() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("roles.fxml"));
        Parent root;
        try {
            root = loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        controller = loader.getController();

        Rectangle2D bounds = Screen.getPrimary().getBounds();
        Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight());
        scene.getStylesheets().add(getClass().getResource("roles.css").toExternalForm());
        scene.setOnKeyPressed(e -> onKey(e.getCode()));
        stage.setTitle("FableOps");
        stage.setScene(scene);
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
    }

    RoleWindow(int localPlayerId, MessageChannel session, Consumer<Role> onReady, Runnable onCancel) {
        this();
        this.localPlayerId = localPlayerId;
        this.session = session;
        this.onReady = onReady;
        this.onCancel = onCancel;
    }

    // FX thread
    void open(int localPlayerId, MessageChannel session, Consumer<Role> onReady, Runnable onCancel) {
        this.localPlayerId = localPlayerId;
        this.session = session;
        this.onReady = onReady;
        this.onCancel = onCancel;
        this.selected = Role.BREAKER;
        this.locked = false;
        this.remoteLocked = null;
        this.finished = false;

        open();
    }

    // FX thread
    void open() {
        controller.setTitle(session == null ? "ASSIGN OPERATORS" : "SELECT YOUR OPERATOR");
        // Messages arriving before this point were parked by the channel and replay now
        if (session != null) {
            session.setListener((type, body) -> {
                if (!RoleLockMessage.TYPE.equals(type)) return;
                RoleLockMessage lock = RoleLockMessage.deserialize(body);
                Platform.runLater(() -> onRemoteLock(lock.getRole()));
            });
        }
        refresh();
        stage.show();
        stage.setAlwaysOnTop(true);
        stage.toFront();
        stage.requestFocus();
        stage.setAlwaysOnTop(false);
    }

    private void onKey(KeyCode code) {
        switch (code) {
            case LEFT: case A: case RIGHT: case D:
                // Only two operators, so any move is a move to the other one
                if (!locked) select(selected.other());
                break;
            case ENTER:
                lockIn();
                break;
            case ESCAPE:
                cancel();
                break;
            default:
                break;
        }
    }

    private void select(Role role) {
        // The one the other operator locked is not available
        if (role == remoteLocked) return;
        selected = role;
        refresh();
    }

    private void lockIn() {
        if (locked || finished || selected == remoteLocked) return;
        locked = true;
        if (session != null) session.send(new RoleLockMessage(localPlayerId, selected));
        refresh();
        finishIfReady();
    }

    // FX thread
    private void onRemoteLock(Role role) {
        if (finished) return;

        if (!locked) {
            remoteLocked = role;
            if (selected == role) selected = role.other();
        } else if (role != selected) {
            remoteLocked = role;
        } else if (localPlayerId == 1) {
            // Both locked the same operator in the same instant. The host keeps it and the
            // client yields, so the two machines settle on the same pair without another round trip
            remoteLocked = selected.other();
        } else {
            selected = selected.other();
            remoteLocked = role;
            session.send(new RoleLockMessage(localPlayerId, selected));
        }
        refresh();
        finishIfReady();
    }

    private void finishIfReady() {
        // Debug has no second machine to wait for
        if (!locked || (session != null && remoteLocked == null)) return;
        finished = true;
        // Hand the channel back so the level's own listener gets everything from here
        if (session != null) session.setListener(null);
        onReady.accept(localPlayerId == 1 ? selected : selected.other());
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        if (session != null) session.setListener(null);
        hide();
        onCancel.run();
    }

    private void refresh() {
        controller.render(selected, remoteLocked, locked);
        controller.setStatus(status());
    }

    private String status() {
        if (session == null) return "Player 2 takes the other operator.";
        if (locked && remoteLocked == null) return "Locked in. Waiting for the other operator…";
        if (remoteLocked != null && !locked) return remoteLocked.callSign() + " is taken. Lock in to start.";
        return "Choose your operator. The other one goes to your partner.";
    }

    // FX thread
    void hide() {
        stage.hide();
    }
}
