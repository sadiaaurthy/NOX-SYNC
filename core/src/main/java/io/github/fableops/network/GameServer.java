package io.github.fableops.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class GameServer {

    private static final int PORT = 9090;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;

    private volatile PlayerInput latestClientInput = new PlayerInput();
    private volatile WorldState  latestState       = new WorldState();
    private volatile boolean     running           = false;
    private volatile boolean     clientConnected   = false;

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress(PORT));
        System.out.println("Waiting for client on port " + PORT);

        // release port automatically on any exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
            System.out.println("Server shutdown — port released.");
        }));

        clientSocket = serverSocket.accept();
        clientSocket.setTcpNoDelay(true);
        clientConnected = true;
        System.out.println("Client connected.");

        out = new PrintWriter(clientSocket.getOutputStream(), true);
        in  = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

        running = true;

        Thread networkThread = new Thread(() -> {
            while (running) {
                try {
                    out.println(latestState.serialize());

                    if (in.ready()) {
                        String line = in.readLine();
                        if (line != null) {
                            latestClientInput = PlayerInput.deserialize(line);
                        }
                    }

                    Thread.sleep(8);

                } catch (Exception e) {
                    System.out.println("Client disconnected.");
                    clientConnected = false;
                    running = false;
                }
            }
        });
        networkThread.setDaemon(true);
        networkThread.start();
    }

    public void pushState(WorldState state) {
        latestState = state;
    }

    public PlayerInput pollClientInput() {
        return latestClientInput;
    }

    public boolean isConnected() {
        return running;
    }

    public boolean isClientConnected() {
        return clientConnected;
    }

    public void stop() {
        running = false;
        clientConnected = false;
        try {
            if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}