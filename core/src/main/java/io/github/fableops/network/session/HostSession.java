package io.github.fableops.network.session;

import io.github.fableops.network.messages.NetworkMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic host-side line-based message channel, separate from GameServer/GameClient
 * (which only ever carry WorldState/PlayerInput on port 9090). Runs on its own port
 * so movement stays untouched. Reusable as-is by Level 2 and Level 3.
 */
public class HostSession {
    private static final int PORT = 9091;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;
    private MessageListener listener;
    private volatile boolean running = true;

    // Same race as ClientSession: the listen thread starts as soon as start() accepts
    // a connection, but the caller only attaches a listener afterwards. Buffer anything
    // received before a listener is attached, then flush it in order once one is.
    private final List<String[]> pending = new ArrayList<>();

    public synchronized void setListener(MessageListener listener) {
        this.listener = listener;
        for (String[] message : pending) {
            listener.onMessage(message[0], message[1]);
        }
        pending.clear();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(PORT));
        System.out.println("[HostSession] Waiting for client on port " + PORT + "...");

        clientSocket = serverSocket.accept();
        clientSocket.setTcpNoDelay(true);
        System.out.println("[HostSession] Client connected: " + clientSocket.getInetAddress());

        out = new PrintWriter(clientSocket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

        Thread listenThread = new Thread(this::listenLoop, "HostSession-Listener");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    private void listenLoop() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            if (running) System.out.println("[HostSession] Client disconnected.");
        }
    }

    private synchronized void dispatch(String line) {
        int separator = line.indexOf('|');
        String type = (separator == -1) ? line : line.substring(0, separator);
        String body = (separator == -1) ? "" : line.substring(separator + 1);
        if (listener != null) {
            listener.onMessage(type, body);
        } else {
            pending.add(new String[]{type, body});
        }
    }

    public synchronized void send(NetworkMessage message) {
        if (out != null) out.println(message.toLine());
    }

    public void stop() {
        running = false;
        try {
            if (clientSocket != null) clientSocket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}