package io.github.fableops.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

// Movement channel, client side
public class GameClient {

    // Fail quickly on a wrong IP
    public static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int PORT = 9090;

    private volatile Socket socket;
    private volatile boolean stopped = false;
    private volatile boolean running = false;

    private volatile WorldState latestState = new WorldState();
    private volatile PlayerInput pendingInput = new PlayerInput();

    public void connect(String hostIP) throws IOException {
        Socket s = new Socket();
        socket = s;
        if (stopped) s.close(); // stop() was already called
        s.connect(new InetSocketAddress(hostIP, PORT), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        PrintWriter out = new PrintWriter(s.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        running = true;

        Thread networkThread = new Thread(() -> {
            while (running) {
                try {
                    if (in.ready()) {
                        String line = in.readLine();
                        if (line != null) latestState = WorldState.deserialize(line);
                    }
                    out.println(pendingInput.serialize());
                    Thread.sleep(GameServer.SEND_INTERVAL_MS);
                } catch (Exception e) {
                    running = false;
                }
            }
        }, "GameClient");
        networkThread.setDaemon(true);
        networkThread.start();
    }

    public void pushInput(PlayerInput input) {
        pendingInput = input;
    }

    public WorldState pollState() {
        return latestState;
    }

    // Can be called while connect() is still running
    public void stop() {
        stopped = true;
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
