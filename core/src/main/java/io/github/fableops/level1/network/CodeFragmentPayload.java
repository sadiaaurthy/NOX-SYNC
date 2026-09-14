package io.github.fableops.level1.network;

import io.github.fableops.network.messages.NetworkMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// What one player's terminal shows for the current stage
public class CodeFragmentPayload extends NetworkMessage {
    private final int stageNumber;
    private final List<String> displayLines;
    private final List<Integer> ownedPositions;

    public CodeFragmentPayload(int stageNumber, List<String> displayLines, List<Integer> ownedPositions) {
        this.stageNumber = stageNumber;
        this.displayLines = displayLines;
        this.ownedPositions = ownedPositions;
    }

    public int getStageNumber() { return stageNumber; }

    public List<String> getDisplayLines() { return displayLines; }

    public List<Integer> getOwnedPositions() { return ownedPositions; }

    @Override
    public String getType() { return "CODE_FRAGMENT"; }

    @Override
    public String serializeBody() {
        StringBuilder positions = new StringBuilder();
        for (int i = 0; i < ownedPositions.size(); i++) {
            if (i > 0) positions.append('~');
            positions.append(ownedPositions.get(i));
        }
        return stageNumber + ";" + String.join("~", displayLines) + ";" + positions;
    }

    public static CodeFragmentPayload deserialize(String body) {
        String[] parts = body.split(";", -1);
        List<String> displayLines = parts[1].isEmpty()
            ? new ArrayList<>()
            : new ArrayList<>(Arrays.asList(parts[1].split("~")));
        List<Integer> ownedPositions = new ArrayList<>();
        if (!parts[2].isEmpty()) {
            for (String p : parts[2].split("~")) ownedPositions.add(Integer.parseInt(p));
        }
        return new CodeFragmentPayload(Integer.parseInt(parts[0]), displayLines, ownedPositions);
    }
}
