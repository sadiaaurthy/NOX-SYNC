package io.github.fableops.level1.model;

import io.github.fableops.level1.network.CodeFragmentPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stage 3 - Distributed Equation Puzzle.
 * Player 1 holds Value 1, and Value 2, which depends on Player 2's Value 3. Player 2 holds
 * Values 3 and 4, which both depend on Value 1. Everything is mod 10, so each value is one digit.
 */
public class Stage3Data extends StageData {
    private static final int VALUE_COUNT = 4;

    private final int value1, offset2, offset4;

    public Stage3Data() {
        super(VALUE_COUNT);
        Random random = new Random();

        value1 = random.nextInt(10);
        offset4 = 1 + random.nextInt(9);
        offset2 = 1 + random.nextInt(9);

        int value4 = (value1 + offset4) % 10;
        int value3 = (value1 + value4) % 10;
        int value2 = (value3 + offset2) % 10;

        correctValues[0] = String.valueOf(value1);
        correctValues[1] = String.valueOf(value2);
        correctValues[2] = String.valueOf(value3);
        correctValues[3] = String.valueOf(value4);

        // A right answer typed at the wrong player's terminal still counts as a mistake.
        ownerPlayerId[0] = 1;
        ownerPlayerId[1] = 1;
        ownerPlayerId[2] = 2;
        ownerPlayerId[3] = 2;
    }

    @Override
    public CodeFragmentPayload viewFor(int playerId) {
        List<String> displayLines = new ArrayList<>();
        List<Integer> ownedPositions = new ArrayList<>();

        if (playerId == 1) {
            displayLines.add("Value 1 = " + value1);
            displayLines.add("Value 2 = (Value 3 + " + offset2 + ") mod 10");
        } else {
            displayLines.add("Value 4 = (Value 1 + " + offset4 + ") mod 10");
            displayLines.add("Value 3 = (Value 1 + Value 4) mod 10");
        }

        for (int i = 0; i < totalPositions; i++) {
            if (ownerPlayerId[i] == playerId) ownedPositions.add(i);
        }

        return new CodeFragmentPayload(3, displayLines, ownedPositions);
    }
}
