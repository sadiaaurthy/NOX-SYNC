package io.github.fableops.network.session;

import io.github.fableops.network.messages.NetworkMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic client-side line-based message channel, separate from GameServer/GameClient.
 * Connects to the same host IP already used for the movement connection, just on a
 * different port. Reusable as-is by Level 2 and Level 3.
 */
public class ClientSession {
    private static final int PORT = 9091;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private MessageListener listener;
    private volatile boolean running = true;

    // The listen thread starts as soon as connect() returns, but the caller (Level1Screen's
    // constructor) only attaches a listener afterwards — a message arriving in that gap used
    // to be silently dropped. Buffer anything received before a listener is attached, then
    // flush it in order once one is.
    private final List<String[]> pending = new ArrayList<>();

    public synchronized void setListener(MessageListener listener) {
        this.listener = listener;
        for (String[] message : pending) {
            listener.onMessage(message[0], message[1]);
        }
        pending.clear();
    }

    public void connect(String hostIP) throws IOException {
        socket = new Socket(hostIP, PORT);
        socket.setTcpNoDelay(true);
        System.out.println("[ClientSession] Connected to " + hostIP);

        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        Thread listenThread = new Thread(this::listenLoop, "ClientSession-Listener");
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
            if (running) System.out.println("[ClientSession] Disconnected from host.");
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
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}