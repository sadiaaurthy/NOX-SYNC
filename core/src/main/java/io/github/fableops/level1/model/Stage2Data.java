package io.github.fableops.level1.model;

import io.github.fableops.level1.network.CodeFragmentPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Stage 2 — Split Information and Conditional Ordering.
 * Player 1 sees 3 conditions (one per position) and enters all 3 values.
 * Player 2 sees the 3 raw values (unordered) and describes them out loud —
 * Player 2 never submits anything this stage, it's purely informational.
 *
 * Conditions are generated from 3 distinct sorted values (low/mid/high) so each
 * condition is unambiguous regardless of the actual numbers rolled:
 *   low  -> "less than mid"
 *   high -> "greater than mid"
 *   mid  -> "between low and high"
 */
public class Stage2Data extends StageData {
    private static final int POSITION_COUNT = 3;

    private final int low, mid, high;
    private final String[] conditionText = new String[POSITION_COUNT];
    private final List<Integer> valuesForPlayer2 = new ArrayList<>();

    public Stage2Data() {
        super(POSITION_COUNT);
        Random random = new Random();

        List<Integer> values = new ArrayList<>();
        while (values.size() < 3) {
            int v = random.nextInt(10);
            if (!values.contains(v)) values.add(v);
        }
        Collections.sort(values);
        low = values.get(0);
        mid = values.get(1);
        high = values.get(2);

        List<Integer> assignment = new ArrayList<>(List.of(low, mid, high));
        Collections.shuffle(assignment);

        for (int i = 0; i < POSITION_COUNT; i++) {
            int value = assignment.get(i);
            correctValues[i] = String.valueOf(value);
            ownerPlayerId[i] = 1; // only Player 1 submits this stage

            if (value == low) {
                conditionText[i] = "Value less than " + mid;
            } else if (value == high) {
                conditionText[i] = "Value greater than " + mid;
            } else {
                conditionText[i] = "Value between " + low + " and " + high;
            }
        }

        valuesForPlayer2.addAll(List.of(low, mid, high));
        Collections.shuffle(valuesForPlayer2);
    }

    @Override
    public CodeFragmentPayload viewFor(int playerId) {
        List<String> displayLines = new ArrayList<>();
        List<Integer> ownedPositions = new ArrayList<>();

        if (playerId == 1) {
            for (int i = 0; i < totalPositions; i++) {
                displayLines.add("Position " + i + ": " + conditionText[i]);
                ownedPositions.add(i);
            }
        } else {
            displayLines.add("Available values: " + valuesForPlayer2);
            displayLines.add("Describe them to your partner - you don't submit this stage.");
        }

        return new CodeFragmentPayload(2, displayLines, ownedPositions);
    }
}