package io.github.fableops.launcher;

import javafx.animation.ScaleTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import io.github.fableops.Role;

// Draws the operator cards. Every string and picture comes from the Role enum
public class RoleController {

    private static final Role[] ROLES = Role.values();
    private static final Duration POP = Duration.millis(120);

    @FXML private StackPane root;
    @FXML private Label title;
    @FXML private Label status;

    @FXML private VBox cardOne;
    @FXML private ImageView portraitOne;
    @FXML private Label stateOne;
    @FXML private Label nameOne;
    @FXML private Label archOne;
    @FXML private Label blurbOne;

    @FXML private VBox cardTwo;
    @FXML private ImageView portraitTwo;
    @FXML private Label stateTwo;
    @FXML private Label nameTwo;
    @FXML private Label archTwo;
    @FXML private Label blurbTwo;

    private Role popped;

    @FXML
    private void initialize() {
        fill(0, portraitOne, nameOne, archOne, blurbOne);
        fill(1, portraitTwo, nameTwo, archTwo, blurbTwo);
    }

    private static void fill(int index, ImageView portrait, Label name, Label archetype, Label blurb) {
        Role role = ROLES[index];
        // assets/ is on the classpath root, packed there by the lwjgl3 module
        portrait.setImage(new Image(RoleController.class.getResourceAsStream("/" + role.portrait())));
        name.setText(role.callSign());
        archetype.setText(role.archetype());
        blurb.setText(role.blurb());
    }

    void setTitle(String text) {
        title.setText(text);
    }

    void setStatus(String text) {
        status.setText(text);
    }

    // selected is this machine's pick, taken is the one the other operator locked (null if none)
    void render(Role selected, Role taken, boolean locked) {
        paint(ROLES[0], cardOne, stateOne, selected, taken, locked);
        paint(ROLES[1], cardTwo, stateTwo, selected, taken, locked);
        // A short pop on the card that just became the pick, so the change registers
        if (selected != popped) {
            popped = selected;
            pop(selected == ROLES[0] ? cardOne : cardTwo);
        }
    }

    private static void paint(Role role, VBox card, Label state, Role selected, Role taken, boolean locked) {
        card.getStyleClass().removeAll("selected", "locked", "taken");
        state.getStyleClass().removeAll("locked", "taken");

        if (role == taken) {
            card.getStyleClass().add("taken");
            state.getStyleClass().add("taken");
            state.setText("TAKEN");
        } else if (role == selected) {
            card.getStyleClass().add(locked ? "locked" : "selected");
            if (locked) state.getStyleClass().add("locked");
            state.setText(locked ? "LOCKED IN" : "SELECTED");
        } else {
            state.setText("");
        }
        state.setVisible(!state.getText().isEmpty());
    }

    private static void pop(VBox card) {
        ScaleTransition pop = new ScaleTransition(POP, card);
        pop.setFromX(0.97);
        pop.setFromY(0.97);
        pop.setToX(1.0);
        pop.setToY(1.0);
        pop.play();
    }

    StackPane getRoot() {
        return root;
    }
}
