package io.github.fableops.level1.model;

public class AlertMeter {
    public static final int MAX_VALUE = 100;

    private int value = 0;

    public int getValue() { return value; }

    public void increase(int amount) {
        value = Math.min(MAX_VALUE, value + amount);
    }

    public void reset() {
        value = 0;
    }

    public boolean isMax() {
        return value >= MAX_VALUE;
    }
}