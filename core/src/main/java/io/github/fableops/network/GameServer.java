package io.github.fableops.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

// Movement channel, host side
public class GameServer {

    private static final int PORT = 9090;
    // About twice per frame at 30 FPS
    static final long SEND_INTERVAL_MS = 16;

    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile boolean stopped = false;

    private volatile PlayerInput latestClientInput = new PlayerInput();
    private volatile WorldState latestState = new WorldState();
    private volatile boolean clientConnected = false;

    // Blocks until the client connects
    public void start() throws IOException {
        ServerSocket server = new ServerSocket();
        serverSocket = server;
        if (stopped) server.close(); // stop() was already called
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(PORT));

        Socket client = server.accept();
        clientSocket = client;
        client.setTcpNoDelay(true);
        PrintWriter out = new PrintWriter(client.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
        clientConnected = true;

        Thread networkThread = new Thread(() -> {
            while (clientConnected) {
                try {
                    out.println(latestState.serialize());
                    if (in.ready()) {
                        String line = in.readLine();
                        if (line != null) latestClientInput = PlayerInput.deserialize(line);
                    }
                    Thread.sleep(SEND_INTERVAL_MS);
                } catch (Exception e) {
                    clientConnected = false;
                }
            }
        }, "GameServer");
        networkThread.setDaemon(true);
        networkThread.start();
    }

    public void pushState(WorldState state) {
        latestState = state;
    }

    public PlayerInput pollClientInput() {
        return latestClientInput;
    }

    public boolean isClientConnected() {
        return clientConnected;
    }

    // Can be called while start() is still waiting
    public void stop() {
        stopped = true;
        clientConnected = false;
        try {
            if (clientSocket != null) clientSocket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
