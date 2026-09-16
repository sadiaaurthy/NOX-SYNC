package io.github.fableops;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.ScreenUtils;

import io.github.fableops.level1.Level1Screen;
import io.github.fableops.network.GameClient;
import io.github.fableops.network.GameServer;
import io.github.fableops.network.session.ClientSession;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.story.StoryBeat;
import io.github.fableops.story.StoryGate;

// Starts while the player is still in the menu so the window and the GL context are already up
// (that part costs about 440ms). It sits on a black frame until begin() hands it a match
public class Main extends Game {

    // Caps the frame time so one long frame can't move a body through a wall
    private static final float MAX_DELTA = 1f / 20f;

    // Written on the JavaFX thread, read on the game thread
    private volatile Match match;
    private StoryGate story;

    // Everything the menu has to settle before a level can be built
    private static final class Match {
        final GameServer server;
        final GameClient client;
        final HostSession hostSession;
        final ClientSession clientSession;
        final StoryGate.View storyView;
        final Role sideOneRole;

        Match(GameServer server, GameClient client, HostSession hostSession, ClientSession clientSession,
              StoryGate.View storyView, Role sideOneRole) {
            this.server = server;
            this.client = client;
            this.hostSession = hostSession;
            this.clientSession = clientSession;
            this.storyView = storyView;
            this.sideOneRole = sideOneRole;
        }
    }

    // JavaFX thread, once the mode, the connection and both operators are settled. The next frame
    // builds Level 1 (about 280ms) behind the opening scenario window
    public void begin(GameServer server, GameClient client, HostSession hostSession,
                      ClientSession clientSession, StoryGate.View storyView, Role sideOneRole) {
        match = new Match(server, client, hostSession, clientSession, storyView, sideOneRole);
    }

    // Nothing heavy here, the point is to reach this line early
    @Override
    public void create() {}

    @Override
    public void render() {
        Match pending = match;
        if (pending != null && screen == null) startMatch(pending);
        // Waiting in the menu, or the level is waiting on a scenario window
        if (screen == null || story.isActive()) {
            ScreenUtils.clear(0f, 0f, 0f, 1f);
            return;
        }
        screen.render(Math.min(Gdx.graphics.getDeltaTime(), MAX_DELTA));
    }

    // Game thread
    private void startMatch(Match pending) {
        story = new StoryGate(pending.storyView,
            pending.hostSession != null ? pending.hostSession : pending.clientSession);
        story.begin(StoryBeat.START);
        setScreen(new Level1Screen(this, pending.server, pending.client, pending.hostSession,
            pending.clientSession, story, pending.sideOneRole));
    }

    // Game.dispose() only calls hide(), so dispose the screen here
    @Override
    public void dispose() {
        if (screen != null) screen.dispose();
    }
}
