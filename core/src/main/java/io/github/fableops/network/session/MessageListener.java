package io.github.fableops.network.session;

public interface MessageListener {
    void onMessage(String type, String body);
}