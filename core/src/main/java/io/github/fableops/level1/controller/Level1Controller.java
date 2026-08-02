package io.github.fableops.level1.controller;

import io.github.fableops.level1.model.AlertMeter;
import io.github.fableops.level1.model.StageData;
import io.github.fableops.level1.network.AlertMeterUpdateMessage;
import io.github.fableops.level1.network.DigitAcceptedMessage;
import io.github.fableops.level1.network.EnteredDigitMessage;
import io.github.fableops.level1.network.LevelRestartMessage;
import io.github.fableops.level1.network.ReactorUnlockMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

/**
 * Host-authoritative Level 1 state machine (spec 5.6). Talks over the generic
 * HostSession/ClientSession channel when hostSession is provided (real host/client
 * play). hostSession may be null — used by Debug mode, which drives both players'
 * submissions locally with no network involved at all.
 */
public class Level1Controller {
    private static final int WRONG_ANSWER_ALERT_INCREASE = 15;

    private final HostSession hostSession; // nullable — null in Debug mode
    private final Level1Listener listener;
    private final AlertMeter alertMeter = new AlertMeter();

    private int stageNumber;
    private StageData stageData;

    public Level1Controller(HostSession hostSession, Level1Listener listener) {
        this.hostSession = hostSession;
        this.listener = listener;
    }

    /** Wire this into hostSession.setListener(...) to route incoming client messages here. */
    public MessageListener asMessageListener() {
        return (type, body) -> {
            if ("ENTERED_DIGIT".equals(type)) {
                handleEnteredDigit(EnteredDigitMessage.deserialize(body));
            }
        };
    }

    public void startLevel() {
        alertMeter.reset();
        enterStage(1);
    }

    private void enterStage(int number) {
        this.stageNumber = number;
        this.stageData = StageGenerator.generate(number);

        // Host's own player is player 1 and never goes over the socket; the client is player 2.
        listener.onLocalView(stageData.viewFor(1));
        listener.onRemoteView(stageData.viewFor(2));
        if (hostSession != null) hostSession.send(stageData.viewFor(2));
    }

    /** Entry point for the host's own local player submitting a digit. */
    public void submitFromLocalPlayer(int positionIndex, String guess) {
        handleEnteredDigit(new EnteredDigitMessage(positionIndex, guess, 1));
    }

    /** Entry point for a digit received from the client (network) or locally (Debug mode). */
    public void handleEnteredDigit(EnteredDigitMessage message) {
        boolean owned = stageData.isOwnedBy(message.getPositionIndex(), message.getPlayerId());
        boolean correct = owned && stageData.correctValue(message.getPositionIndex()).equals(message.getGuess());

        if (correct) {
            stageData.markSolved(message.getPositionIndex());
            listener.onDigitAccepted(message.getPositionIndex());
            if (hostSession != null) hostSession.send(new DigitAcceptedMessage(message.getPositionIndex()));

            if (stageData.allPositionsSolved()) {
                if (stageNumber == 3) {
                    alertMeter.reset();
                    listener.onReactorUnlocked();
                    if (hostSession != null) hostSession.send(new ReactorUnlockMessage());
                } else {
                    enterStage(stageNumber + 1);
                }
            }
        } else {
            alertMeter.increase(WRONG_ANSWER_ALERT_INCREASE);
            listener.onAlertMeterChanged(alertMeter.getValue());
            if (hostSession != null) hostSession.send(new AlertMeterUpdateMessage(alertMeter.getValue()));
            listener.onWrongAnswer(message.getPlayerId());

            if (alertMeter.isMax()) {
                listener.onMissionFailed();
            }
        }
    }

    /**
     * Debug shortcut: marks the current stage solved and moves on, exactly as if every
     * position had been entered correctly. Deliberately routed through the same
     * advance/unlock path as a real solve so it can't drift from normal progression.
     */
    public void skipCurrentStage() {
        if (stageNumber == 3) {
            alertMeter.reset();
            listener.onReactorUnlocked();
            if (hostSession != null) hostSession.send(new ReactorUnlockMessage());
        } else {
            enterStage(stageNumber + 1);
        }
    }

    /** Actually performs a restart — called once the player acknowledges the mission-failed banner. */
    public void restartLevel1() {
        listener.onLevelRestart();
        if (hostSession != null) hostSession.send(new LevelRestartMessage());
        startLevel();
    }
}