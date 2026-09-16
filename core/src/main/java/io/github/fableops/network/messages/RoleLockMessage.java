package io.github.fableops.network.messages;

import io.github.fableops.Role;

// Sent when a player locks their operator in. The other machine marks that role taken and takes the other
public class RoleLockMessage extends NetworkMessage {

    public static final String TYPE = "ROLE_LOCK";

    private final int playerId;
    private final Role role;

    public RoleLockMessage(int playerId, Role role) {
        this.playerId = playerId;
        this.role = role;
    }

    public int getPlayerId() { return playerId; }

    public Role getRole() { return role; }

    @Override
    public String getType() { return TYPE; }

    @Override
    public String serializeBody() {
        return playerId + "|" + role.name();
    }

    public static RoleLockMessage deserialize(String body) {
        String[] parts = body.split("\\|");
        return new RoleLockMessage(Integer.parseInt(parts[0]), Role.valueOf(parts[1]));
    }
}
