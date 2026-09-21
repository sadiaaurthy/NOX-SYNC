package io.github.fableops.level2;

import com.badlogic.gdx.Gdx;

import io.github.fableops.Player;
import io.github.fableops.inventory.PlayerInventories;
import io.github.fableops.inventory.network.InventoryTransferMessage;
import io.github.fableops.level2.loot.LootDrop;
import io.github.fableops.level2.loot.LootField;
import io.github.fableops.level2.network.CoreStateMessage;
import io.github.fableops.level2.network.LootInteractRequestMessage;
import io.github.fableops.level2.network.LootPickedUpMessage;
import io.github.fableops.network.session.HostSession;
import io.github.fableops.network.session.MessageListener;

// Core pickup/placement and loot pickup, run on the host. hostSession is null in debug mode
public class Level2Controller {

    private final HostSession hostSession;
    private final CoreObject core;
    private final LootField loot;
    private final Sidearms sidearms;
    private final PlayerInventories inventories;
    private final Level2Map world;
    private final Player player1;
    private final Player player2;

    public Level2Controller(HostSession hostSession, CoreObject core, LootField loot, Sidearms sidearms,
                            PlayerInventories inventories, Level2Map world, Player player1, Player player2) {
        this.hostSession = hostSession;
        this.core = core;
        this.loot = loot;
        this.sidearms = sidearms;
        this.inventories = inventories;
        this.world = world;
        this.player1 = player1;
        this.player2 = player2;
    }

    // Client E/G presses are handled on the render thread
    public MessageListener asMessageListener() {
        return (type, body) -> {
            if ("CORE_INTERACT".equals(type)) {
                Gdx.app.postRunnable(() -> interactCore(2));
            } else if ("LOOT_INTERACT".equals(type)) {
                LootInteractRequestMessage msg = LootInteractRequestMessage.deserialize(body);
                Gdx.app.postRunnable(() -> interactLoot(2, msg.getLootId()));
            } else if ("INVENTORY_TRANSFER".equals(type)) {
                InventoryTransferMessage msg = InventoryTransferMessage.deserialize(body);
                if (msg.getPlayerSide() == 2) {
                    Gdx.app.postRunnable(() -> transferInventory(msg));
                }
            }
        };
    }

    public void transferInventory(InventoryTransferMessage message) {
        if (!inventories.applyTransfer(message.getPlayerSide(), message.isFromShared(),
            message.getPersonalSlot())) return;
        // A Sidearm can change hands through the shared slot, so re-read who is armed on both sides
        sidearms.syncOwnership(inventories);
        if (hostSession != null) {
            hostSession.send(message);
            hostSession.send(sidearms.forSide(1).toMessage());
            hostSession.send(sidearms.forSide(2).toMessage());
        }
    }

    public void interactCore(int playerId) {
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

    // Debug only (K). Takes the core, then seats it on the next press, without walking
    // either operator anywhere. Player 1 carries it, the same as a normal pickup would
    public void debugToggleCore() {
        if (core.getState() == CoreObject.State.IN_SOCKET) return;
        if (core.getState() == CoreObject.State.ON_PEDESTAL) {
            core.set(CoreObject.State.CARRIED, 1);
        } else {
            core.set(CoreObject.State.IN_SOCKET, 0);
            world.openExit();
        }
        if (hostSession != null) hostSession.send(new CoreStateMessage(core.getState(), core.getCarrierId()));
    }

    public void interactLoot(int playerId, int lootId) {
        Player player = (playerId == 1) ? player1 : player2;
        LootDrop drop = loot.findReachablePickup(player, core.getState());
        if (drop == null || drop.getId() != lootId) return;

        loot.applyPickup(drop.getId(), playerId, inventories);
        // Each operator arms their own weapon, and a cache loads the gun of whoever grabbed it -
        // including before they have found a Sidearm, so the rounds are waiting when they do
        if (loot.isGun(drop)) sidearms.forSide(playerId).giveTo(playerId);
        sidearms.forSide(playerId).addSpare(drop.getAmmo());
        if (hostSession != null) {
            hostSession.send(new LootPickedUpMessage(drop.getId(), playerId));
            hostSession.send(sidearms.forSide(playerId).toMessage());
        }
    }
}
