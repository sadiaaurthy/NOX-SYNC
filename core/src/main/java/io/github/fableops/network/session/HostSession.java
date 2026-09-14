package io.github.fableops.network.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

// Message channel, host side
public class HostSession extends MessageChannel {

    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;

    // Blocks until the client connects
    public void start() throws IOException {
        ServerSocket server = new ServerSocket();
        serverSocket = server;
        if (stopped) server.close(); // stop() was already called
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(PORT));
        clientSocket = server.accept();
        open(clientSocket);
    }

    @Override
    protected void closeSockets() {
        close(clientSocket);
        close(serverSocket);
    }
}
