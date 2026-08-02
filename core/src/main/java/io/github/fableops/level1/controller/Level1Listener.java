package io.github.fableops.level1.controller;

import io.github.fableops.level1.network.CodeFragmentPayload;

public interface Level1Listener {
    void onLocalView(CodeFragmentPayload payload);
    void onRemoteView(CodeFragmentPayload payload);
    void onAlertMeterChanged(int value);
    void onDigitAccepted(int positionIndex);
    void onReactorUnlocked();
    void onLevelRestart();
    void onWrongAnswer(int offendingPlayerId);
    void onMissionFailed();
}