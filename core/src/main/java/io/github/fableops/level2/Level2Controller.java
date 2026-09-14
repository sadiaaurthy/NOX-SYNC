package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;

import io.github.fableops.Player;
import io.github.fableops.level2.network.CoreStateMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

// Core pickup and placement, run on the host. hostSession is null in debug mode
public class Level2Controller {

    private final HostSession hostSession;
    private final CoreObject core;
    private final Level2Map world;
    private final Player player1;
    private final Player player2;

    public Level2Controller(HostSession hostSession, CoreObject core, Level2Map world,
                            Player player1, Player player2) {
        this.hostSession = hostSession;
        this.core = core;
        this.world = world;
        this.player1 = player1;
        this.player2 = player2;
    }

    // Client E presses are handled on the render thread
    public MessageListener asMessageListener() {
        return (type, body) -> {
            if ("CORE_INTERACT".equals(type)) Gdx.app.postRunnable(() -> interact(2));
        };
    }

    public void interact(int playerId) {
        Player player = (playerId == 1) ? player1 : player2;
        if (core.prompt(world, player, playerId) == null) return;

        if (core.getState() == CoreObject.State.ON_PEDESTAL) {
            core.set(CoreObject.State.CARRIED, playerId);
        } else {
            core.set(CoreObject.State.IN_SOCKET, 0);
            world.openExit();
        }
        if (hostSession != null) hostSession.send(new CoreStateMessage(core.getState(), core.getCarrierId()));
    }
}
