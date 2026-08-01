package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CodeFragmentPayload extends NetworkMessage {
    private int stageNumber;
    private List<String> displayLines;
    private List<Integer> ownedPositions;

    public CodeFragmentPayload(int stageNumber, List<String> displayLines, List<Integer> ownedPositions) {
        this.stageNumber = stageNumber;
        this.displayLines = displayLines;
        this.ownedPositions = ownedPositions;
    }

    public int getStageNumber() { return stageNumber; }
    public void setStageNumber(int stageNumber) { this.stageNumber = stageNumber; }

    public List<String> getDisplayLines() { return displayLines; }
    public void setDisplayLines(List<String> displayLines) { this.displayLines = displayLines; }

    public List<Integer> getOwnedPositions() { return ownedPositions; }
    public void setOwnedPositions(List<Integer> ownedPositions) { this.ownedPositions = ownedPositions; }

    @Override
    public String getType() { return "CODE_FRAGMENT"; }

    @Override
    public String serializeBody() {
        String linesPart = String.join("~", displayLines);
        StringBuilder positionsPart = new StringBuilder();
        for (int i = 0; i < ownedPositions.size(); i++) {
            if (i > 0) positionsPart.append("~");
            positionsPart.append(ownedPositions.get(i));
        }
        return stageNumber + ";" + linesPart + ";" + positionsPart;
    }

    public static CodeFragmentPayload deserialize(String body) {
        String[] parts = body.split(";", -1);
        int stageNumber = Integer.parseInt(parts[0]);

        List<String> displayLines = parts[1].isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(parts[1].split("~")));

        List<Integer> ownedPositions = new ArrayList<>();
        if (parts.length > 2 && !parts[2].isEmpty()) {
            for (String p : parts[2].split("~")) {
                ownedPositions.add(Integer.parseInt(p));
            }
        }

        return new CodeFragmentPayload(stageNumber, displayLines, ownedPositions);
    }
}