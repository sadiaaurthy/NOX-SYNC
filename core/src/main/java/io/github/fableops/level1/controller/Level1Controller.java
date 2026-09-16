package io.github.fableops.level1.controller;

import com.badlogic.gdx.Gdx;

import io.github.fableops.level1.model.AlertMeter;
import io.github.fableops.level1.model.StageData;
import io.github.fableops.level1.network.AlertMeterUpdateMessage;
import io.github.fableops.level1.network.CodeFragmentPayload;
import io.github.fableops.level1.network.EnteredDigitMessage;
import io.github.fableops.level1.network.ReactorUnlockMessage;
import io.github.fableops.level1.network.WrongAnswerMessage;
import io.github.fableops.network.messages.LevelRestartMessage;
import io.github.fableops.network.messages.NetworkMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

// Level 1 puzzle logic, run on the host. hostSession is null in debug mode
public class Level1Controller {
    private static final int WRONG_ANSWER_ALERT_INCREASE = 15;

    private final HostSession hostSession;
    private final Level1Listener listener;
    private final AlertMeter alertMeter = new AlertMeter();

    private int stageNumber;
    private StageData stageData;

    public Level1Controller(HostSession hostSession, Level1Listener listener) {
        this.hostSession = hostSession;
        this.listener = listener;
    }

    // Client digits are handled on the render thread, not the network thread
    public MessageListener asMessageListener() {
        return (type, body) -> {
            if ("ENTERED_DIGIT".equals(type)) {
                EnteredDigitMessage entered = EnteredDigitMessage.deserialize(body);
                Gdx.app.postRunnable(() -> handleEnteredDigit(entered));
            }
        };
    }

    public void startLevel() {
        alertMeter.reset();
        enterStage(1);
    }

    private void enterStage(int number) {
        stageNumber = number;
        stageData = StageGenerator.generate(number);
        // The host plays Player 1 locally; Player 2's view goes to the client.
        listener.onLocalView(stageData.viewFor(1));
        CodeFragmentPayload remoteView = stageData.viewFor(2);
        listener.onRemoteView(remoteView);
        send(remoteView);
    }

    public void submitFromLocalPlayer(int positionIndex, String guess) {
        handleEnteredDigit(new EnteredDigitMessage(positionIndex, guess, 1));
    }

    public void handleEnteredDigit(EnteredDigitMessage message) {
        int position = message.getPositionIndex();
        boolean correct = stageData.isOwnedBy(position, message.getPlayerId())
            && stageData.correctValue(position).equals(message.getGuess());

        if (correct) {
            stageData.markSolved(position);
            if (stageData.allPositionsSolved()) advance();
            return;
        }
        alertMeter.increase(WRONG_ANSWER_ALERT_INCREASE);
        reportAlertMeter();
        send(new WrongAnswerMessage());
        listener.onWrongAnswer(message.getPlayerId());
        if (alertMeter.isMax()) listener.onMissionFailed();
    }

    // Debug only (K key)
    public void skipCurrentStage() {
        advance();
    }

    private void advance() {
        if (stageNumber < 3) {
            enterStage(stageNumber + 1);
            return;
        }
        alertMeter.reset();
        reportAlertMeter();
        listener.onReactorUnlocked();
        send(new ReactorUnlockMessage());
    }

    public void restartLevel1() {
        listener.onLevelRestart();
        send(new LevelRestartMessage());
        startLevel();
        reportAlertMeter();
    }

    private void reportAlertMeter() {
        listener.onAlertMeterChanged(alertMeter.getValue());
        send(new AlertMeterUpdateMessage(alertMeter.getValue()));
    }

    private void send(NetworkMessage message) {
        if (hostSession != null) hostSession.send(message);
    }
}
