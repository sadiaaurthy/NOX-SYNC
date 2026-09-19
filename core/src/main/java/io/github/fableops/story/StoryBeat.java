package io.github.fableops.story;

// The ORV-style scenario windows around the levels, text from STORY.md
public enum StoryBeat {

    START("MAIN SCENARIO #1 — ACCESS RING",
        "They tell it still, in the academies that train the next watch: of the two who were sent where "
            + "one alone could not go, into a station that had stopped answering, to reach a light that had "
            + "stopped behaving like light.",
        new String[][]{
            {"Category", "Main"},
            {"Difficulty", "C"},
            {"Clear Condition", "Solve the split terminals together, hold both pressure plates, "
                + "then walk through the exit gate side by side."},
            {"Time Limit", "None"},
            {"Compensation", "Access to the reactor floor"},
            {"Failure", "The alert meter maxes out, or an operator falls."}
        }),

    LEVEL_2("MAIN SCENARIO #2 — UNSTABLE CORE MAZE",
        "Others came before them and did not leave. Their tools remain, keyed to hands that will not "
            + "return — and something down here is still guarding a promise it no longer remembers making.",
        new String[][]{
            {"Category", "Main"},
            {"Difficulty", "B"},
            {"Clear Condition", "Carry the Unstable Core to the reactor socket, then leave through the exit together."},
            {"Time Limit", "None"},
            {"Compensation", "Gear the last team left behind. Rare caches open only while the core is carried."},
            {"Failure", "???"}
        }),

    LEVEL_3("THE WARDEN",
        "It was never told to hate them. Only to protect — a directive followed so faithfully, for so long, "
            + "it forgot what it was protecting them for. They did not come to end it. They came to remind it.",
        new String[][]{
            {"Category", "Main"},
            {"Difficulty", "A"},
            {"Objective", "Stabilize the core. Restore authorization. Work together."},
            {"Time Limit", "None"},
            {"Equipment", "Use what the last team left behind."},
            {"Failure", "Both operators fall."}
        }),

    ENDING("THE FABLE ENDS, AS THESE DO\nA LIGHT BEHAVES AGAIN",
        "The core steadies. The Warden stands down, its directive finally, quietly, fulfilled. Above, "
            + "the grid accepts a clean signal it has waited a generation for — and a new fable begins, "
            + "of two who did not rush, and so did not fail.",
        new String[][]{
            {"Result", "Cleared"},
            {"Containment", "Restored"},
            {"Compensation", "A clean signal for the grid"},
            {"Transmission", "Sent. Mission complete."}
        });

    private final String heading;
    private final String narration;
    private final String[][] rows; // {label, value}

    StoryBeat(String heading, String narration, String[][] rows) {
        this.heading = heading;
        this.narration = narration;
        this.rows = rows;
    }

    public String getHeading() { return heading; }

    public String getNarration() { return narration; }

    public String[][] getRows() { return rows; }
}
