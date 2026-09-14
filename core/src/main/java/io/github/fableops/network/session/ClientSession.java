package io.github.fableops.network.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import io.github.fableops.network.GameClient;

// Message channel, client side
public class ClientSession extends MessageChannel {

    private volatile Socket socket;

    public void connect(String hostIP) throws IOException {
        Socket s = new Socket();
        socket = s;
        if (stopped) s.close(); // stop() was already called
        s.connect(new InetSocketAddress(hostIP, PORT), GameClient.CONNECT_TIMEOUT_MS);
        open(s);
    }

    @Override
    protected void closeSockets() {
        close(socket);
    }
}
