package io.github.fableops;

// The two operators. There are only two, so one pick settles the whole match: whoever takes a role,
// the other side gets the other one. That's why nothing has to negotiate a second value over the network
public enum Role {

        BREAKER("KADE", "THE BREAKER", "brawlspritesheet", "brawlSelectionImage.png",
            "Goes in first and breaks what's in the way. Heavy melee, and the one who holds the line."),

        LISTENER("WREN", "THE LISTENER", "hackerspritesheet", "hackerSelectionImage.png",
            "Hears what the station won't say. Clean terminal work, and the steadier hand at range.");

    private final String callSign;
    private final String archetype;
    private final String sheetName;
    private final String portrait;
    private final String blurb;

    Role(String callSign, String archetype, String sheetName, String portrait, String blurb) {
        this.callSign = callSign;
        this.archetype = archetype;
        this.sheetName = sheetName;
        this.portrait = portrait;
        this.blurb = blurb;
    }

    public String callSign() { return callSign; }

    // The one-word tag under the name, like a shooter's agent roles
    public String archetype() { return archetype; }

    // Player loads sheetName.png plus its Attacking and Death sheets
    public String sheetName() { return sheetName; }

    public String portrait() { return portrait; }

    public String blurb() { return blurb; }

    public Role other() {
        return (this == BREAKER) ? LISTENER : BREAKER;
    }
}
