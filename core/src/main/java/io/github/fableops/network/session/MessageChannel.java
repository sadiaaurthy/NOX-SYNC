package io.github.fableops.network.session;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import io.github.fableops.network.messages.NetworkMessage;

// Line based messages (TYPE|body) for the puzzle, enemies and the core, on port 9091
public abstract class MessageChannel {

    static final int PORT = 9091;

    protected volatile boolean stopped = false;
    private PrintWriter out;
    private MessageListener listener;
    // Messages that arrive before a listener is set wait here
    private final List<String[]> pending = new ArrayList<>();

    protected void open(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        synchronized (this) {
            out = new PrintWriter(socket.getOutputStream(), true);
        }
        Thread reader = new Thread(() -> read(in), getClass().getSimpleName());
        reader.setDaemon(true);
        reader.start();
    }

    private void read(BufferedReader in) {
        try {
            String line;
            while (!stopped && (line = in.readLine()) != null) dispatch(line);
        } catch (IOException ignored) {
            // The other side left, or stop() closed the socket.
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

    // null parks incoming messages until the next listener is set
    public synchronized void setListener(MessageListener listener) {
        this.listener = listener;
        if (listener == null) return;
        for (String[] message : pending) listener.onMessage(message[0], message[1]);
        pending.clear();
    }

    public synchronized void send(NetworkMessage message) {
        if (out != null) out.println(message.toLine());
    }

    // Can be called while another thread is still connecting
    public void stop() {
        stopped = true;
        closeSockets();
    }

    protected abstract void closeSockets();

    static void close(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Already closing.
        }
    }
}
