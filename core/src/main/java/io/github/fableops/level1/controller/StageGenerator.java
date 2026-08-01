package io.github.fableops.level1.controller;

import io.github.fableops.level1.model.Stage1Data;
import io.github.fableops.level1.model.Stage2Data;
import io.github.fableops.level1.model.Stage3Data;
import io.github.fableops.level1.model.StageData;

public class StageGenerator {
    public static StageData generate(int stageNumber) {
        switch (stageNumber) {
            case 1: return new Stage1Data();
            case 2: return new Stage2Data();
            case 3: return new Stage3Data();
            default: throw new IllegalArgumentException("Invalid stage number: " + stageNumber);
        }
    }
}