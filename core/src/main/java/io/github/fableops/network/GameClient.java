package io.github.fableops.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class GameClient {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    private volatile WorldState  latestState   = new WorldState();
    private volatile PlayerInput pendingInput  = new PlayerInput();
    private volatile boolean     running       = false;

    public void connect(String hostIP) throws IOException {
        socket = new Socket(hostIP, 9090);
        socket.setTcpNoDelay(true);
        System.out.println("Connected to " + hostIP);

        out = new PrintWriter(socket.getOutputStream(), true);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        running = true;

        Thread networkThread = new Thread(() -> {
            while (running) {
                try {
                    // read state from host
                    if (in.ready()) {
                        String line = in.readLine();
                        if (line != null) {
                            latestState = WorldState.deserialize(line);
                        }
                    }

                    // send our input
                    out.println(pendingInput.serialize());

                    Thread.sleep(8);

                } catch (Exception e) {
                    System.out.println("Disconnected from host.");
                    running = false;
                }
            }
        });
        networkThread.setDaemon(true);
        networkThread.start();
    }

    public void pushInput(PlayerInput input) {
        pendingInput = input;
    }

    public WorldState pollState() {
        return latestState;
    }

    public boolean isConnected() { return running; }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Closing an already-closing socket during shutdown — benign.
        }
    }
}