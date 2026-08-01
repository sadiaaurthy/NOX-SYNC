package io.github.fableops.level1.model;

import io.github.fableops.level1.network.CodeFragmentPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stage 1 — Binary Conversion and Alternating Placement.
 * Player 1 (host) gets the "Odd" binary number, Player 2 (client) gets "Even".
 * Each converts their binary number to decimal in their head and splits it into
 * two digits; the final code alternates Odd-tens, Even-tens, Odd-ones, Even-ones.
 */
public class Stage1Data extends StageData {
    private static final int CODE_LENGTH = 4;

    private final String oddBinary;
    private final String evenBinary;

    public Stage1Data() {
        super(CODE_LENGTH);
        Random random = new Random();

        int oddDecimal = 10 + random.nextInt(90);  // 10-99, guarantees 2 decimal digits
        int evenDecimal = 10 + random.nextInt(90);

        oddBinary = Integer.toBinaryString(oddDecimal);
        evenBinary = Integer.toBinaryString(evenDecimal);

        // Position 0: Odd tens digit, Position 1: Even tens digit
        // Position 2: Odd ones digit, Position 3: Even ones digit
        correctValues[0] = String.valueOf(oddDecimal / 10);
        correctValues[1] = String.valueOf(evenDecimal / 10);
        correctValues[2] = String.valueOf(oddDecimal % 10);
        correctValues[3] = String.valueOf(evenDecimal % 10);

        ownerPlayerId[0] = 1;
        ownerPlayerId[1] = 2;
        ownerPlayerId[2] = 1;
        ownerPlayerId[3] = 2;
    }

    @Override
    public CodeFragmentPayload viewFor(int playerId) {
        List<String> displayLines = new ArrayList<>();
        List<Integer> ownedPositions = new ArrayList<>();

        if (playerId == 1) {
            displayLines.add("Odd binary number: " + oddBinary);
        } else {
            displayLines.add("Even binary number: " + evenBinary);
        }
        displayLines.add("Convert to decimal, split into 2 digits.");

        for (int i = 0; i < totalPositions; i++) {
            if (ownerPlayerId[i] == playerId) ownedPositions.add(i);
        }

        return new CodeFragmentPayload(1, displayLines, ownedPositions);
    }
}