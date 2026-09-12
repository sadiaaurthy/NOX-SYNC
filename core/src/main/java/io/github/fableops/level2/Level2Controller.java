package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;

import io.github.fableops.level2.network.CoreInteractRequestMessage;
import io.github.fableops.level2.network.CoreStateMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

/**
 * Host-authoritative Level 2 state — currently just the Core's pickup/drop lifecycle.
 * Same shape as Level1Controller: hostSession is null in Debug mode (fully local, no
 * network involved at all), non-null for a real host/client match.
 */
public class Level2Controller {

    private final HostSession hostSession; // nullable — null in Debug mode
    private final CoreObject core;

    public Level2Controller(HostSession hostSession, CoreObject core) {
        this.hostSession = hostSession;
        this.core = core;
        core.setInteractionHandler(this::tryInteract);
    }

    /** Wire into hostSession.setListener(...) to route a joined client's interact requests here. */
    public MessageListener asMessageListener() {
        return (type, body) -> {
            if ("CORE_INTERACT_REQUEST".equals(type)) {
                CoreInteractRequestMessage request = CoreInteractRequestMessage.deserialize(body);
                // Marshal onto the render thread — mutating CoreObject/broadcasting is only
                // safe there, same reasoning as Level1Controller.asMessageListener().
                Gdx.app.postRunnable(() -> tryInteract(request.getPlayerId()));
            }
        };
    }

    /**
     * Pick up if ON_GROUND, set back down if CARRIED by this same player. Placing on an
     * altar is intentionally not implemented yet (hooks only, per spec) — a future step
     * will add that transition once the altar/pressure-plate mechanics are reviewed.
     */
    public void tryInteract(int playerId) {
        switch (core.getState()) {
            case ON_GROUND:
                core.setLocalState(CoreObject.State.CARRIED, core.getX(), core.getY(), playerId);
                broadcastState();
                break;
            case CARRIED:
                if (core.getCarrierPlayerId() == playerId) {
                    core.setLocalState(CoreObject.State.ON_GROUND, core.getX(), core.getY(), -1);
                    broadcastState();
                }
                break;
            case PLACED_ON_ALTAR:
                break;
        }
    }

    /** Host/debug only — called once per frame while the Core is carried. */
    public void followCarrier(float carrierX, float carrierY) {
        if (core.getState() != CoreObject.State.CARRIED) return;
        core.followCarrier(carrierX, carrierY);
        broadcastState();
    }

    public void broadcastState() {
        if (hostSession != null) {
            hostSession.send(new CoreStateMessage(core.getState(), core.getX(), core.getY(), core.getCarrierPlayerId()));
        }
    }
}
