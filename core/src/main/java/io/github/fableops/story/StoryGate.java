package io.github.fableops.story;

import com.badlogic.gdx.Gdx;

import io.github.fableops.network.session.MessageChannel;
import io.github.fableops.network.session.MessageListener;

// Holds the game on a scenario window until both players confirm it, and puts up the mission failed
// window. Runs on the game thread. session is null in debug, where one confirm counts for both players
public class StoryGate {

    // Implemented by the launcher. Called on the game thread
    public interface View {
        // onConfirm runs once this machine's player confirms, on any thread
        void show(StoryBeat beat, Runnable onConfirm);

        void setReady(int players);

        // onRestart is null on the client, which waits for the host. It runs on any thread
        void showFailure(String heading, String narration, String[][] rows, Runnable onRestart);

        // Also brings the game window back
        void close();
    }

    private final View view;
    private final MessageChannel session;
    private StoryBeat current;
    private boolean localReady;
    // The last beat the other player confirmed, it can arrive before this machine reaches that beat
    private StoryBeat remoteReady;
    private boolean failureShown;

    public StoryGate(View view, MessageChannel session) {
        this.view = view;
        this.session = session;
    }

    // The level doesn't run while either window is up
    public boolean isActive() {
        return current != null || failureShown;
    }

    public void begin(StoryBeat beat) {
        current = beat;
        localReady = false;
        view.show(beat, () -> Gdx.app.postRunnable(() -> confirmLocal(beat)));
        refresh();
    }

    // Each level passes its listener through here, so STORY_READY is handled whichever level is running
    public MessageListener wrap(MessageListener levelListener) {
        return (type, body) -> {
            if (!StoryReadyMessage.TYPE.equals(type)) {
                levelListener.onMessage(type, body);
                return;
            }
            StoryBeat beat = StoryBeat.valueOf(body);
            Gdx.app.postRunnable(() -> {
                remoteReady = beat;
                refresh();
            });
        };
    }

    // onRestart runs on the game thread. The client passes null and waits for the host's restart
    public void showFailure(String heading, String narration, String[][] rows, Runnable onRestart) {
        failureShown = true;
        view.showFailure(heading, narration, rows,
            onRestart == null ? null : () -> Gdx.app.postRunnable(onRestart));
    }

    public void closeFailure() {
        if (!failureShown) return;
        failureShown = false;
        view.close();
    }

    // Who fell, for the mission failed window
    public static String fallen(boolean breakerDown, boolean listenerDown) {
        if (breakerDown && listenerDown) return "Both operators have fallen.";
        return breakerDown ? "The Breaker has fallen." : "The Listener has fallen.";
    }

    private void confirmLocal(StoryBeat beat) {
        if (beat != current || localReady) return;
        localReady = true;
        if (session != null) session.send(new StoryReadyMessage(beat));
        refresh();
    }

    private void refresh() {
        if (current == null) return;
        int ready = (session == null)
            ? (localReady ? 2 : 0)
            : (localReady ? 1 : 0) + (remoteReady == current ? 1 : 0);
        view.setReady(ready);
        if (ready == 2) {
            current = null;
            view.close();
        }
    }
}
