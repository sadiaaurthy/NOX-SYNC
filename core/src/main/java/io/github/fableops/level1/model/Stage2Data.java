package io.github.fableops.level1.model;

import io.github.fableops.level1.network.CodeFragmentPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Stage 2 — Symbol Translation Protocol.
 * Player 1 sees the code as a sequence of symbols and enters the digits.
 * Player 2 holds the legend saying what each symbol is worth, and never submits.
 *
 * The split is deliberately total: Player 1's screen contains no digits at all, and
 * Player 2's legend is listed in a shuffled order that carries no positional meaning.
 * Neither screen is solvable on its own, which is what the previous version got wrong —
 * it spelled the comparison values out ("less than 7", "between 5 and 9"), so Player 1
 * could read every number straight off their own screen and never had to ask.
 */
public class Stage2Data extends StageData {
    private static final int POSITION_COUNT = 3;
    // No "=" in the pool — the legend is rendered as "<symbol> = <digit>", so an "="
    // symbol would print the unreadable "= = 4".
    private static final String[] SYMBOL_POOL = {"@", "#", "$", "%", "&", "*", "+", "?"};

    private final String[] symbolAtPosition = new String[POSITION_COUNT];
    private final List<String> legendForPlayer2 = new ArrayList<>();

    public Stage2Data() {
        super(POSITION_COUNT);
        Random random = new Random();

        List<String> symbols = new ArrayList<>(List.of(SYMBOL_POOL));
        Collections.shuffle(symbols);

        // Distinct digits, so no two symbols share a value and the legend stays unambiguous.
        List<Integer> digits = new ArrayList<>();
        while (digits.size() < POSITION_COUNT) {
            int d = random.nextInt(10);
            if (!digits.contains(d)) digits.add(d);
        }

        for (int i = 0; i < POSITION_COUNT; i++) {
            symbolAtPosition[i] = symbols.get(i);
            correctValues[i] = String.valueOf(digits.get(i));
            ownerPlayerId[i] = 1; // only Player 1 submits this stage
            legendForPlayer2.add(symbols.get(i) + " = " + digits.get(i));
        }

        // Shuffled so the legend's order never hints at the code's order.
        Collections.shuffle(legendForPlayer2);
    }

    @Override
    public CodeFragmentPayload viewFor(int playerId) {
        List<String> displayLines = new ArrayList<>();
        List<Integer> ownedPositions = new ArrayList<>();

        if (playerId == 1) {
            displayLines.add("Code reads:  " + String.join("  ", symbolAtPosition));
            displayLines.add("Ask your partner what each symbol is worth.");
            for (int i = 0; i < totalPositions; i++) {
                displayLines.add("Position " + i + " = symbol " + symbolAtPosition[i]);
                ownedPositions.add(i);
            }
        } else {
            displayLines.add("Symbol legend:");
            for (String entry : legendForPlayer2) {
                displayLines.add("   " + entry);
            }
            displayLines.add("Read these out - you don't submit this stage.");
        }

        return new CodeFragmentPayload(2, displayLines, ownedPositions);
    }
}
