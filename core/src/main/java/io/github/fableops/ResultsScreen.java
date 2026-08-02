package io.github.fableops;

/**
 * Bridge that lets the game ask for a JavaFX results screen without depending on JavaFX.
 *
 * The core module can't reference JavaFX (only the launcher module has it on the
 * classpath), so the launcher registers a presenter at startup and the game calls
 * through this hook. When the game is started directly — no launcher, so no JavaFX
 * toolkit running — no presenter is registered, {@link #show} reports that nothing
 * handled it, and the caller falls back to drawing its own in-game panel.
 *
 * The presenter is deliberately a single static slot: it is written once during startup
 * on the launcher thread and read from the render thread, hence volatile.
 */
public final class ResultsScreen {

    public interface Presenter {
        /** Called off the render thread's control — implementations must marshal to their own UI thread. */
        void show(boolean victory, String detail);
    }

    private static volatile Presenter presenter;

    public static void setPresenter(Presenter newPresenter) {
        presenter = newPresenter;
    }

    /** @return true if a presenter took ownership of showing the result. */
    public static boolean show(boolean victory, String detail) {
        Presenter current = presenter;
        if (current == null) return false;
        current.show(victory, detail);
        return true;
    }

    private ResultsScreen() {}
}
