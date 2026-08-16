package io.github.rjaros87.lifecycle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShutdownCommandTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ShutdownCommand newCommand(String path) {
        ShutdownCommand command = new ShutdownCommand();
        command.client = new ManagementEndpointClient();
        command.path = path;
        command.ignoreConnectionErrors = false;
        command.method = "POST";
        command.options = new CommonOptions();
        command.options.host = "127.0.0.1";
        command.options.port = port;
        command.options.scheme = "http";
        command.options.timeoutSeconds = 2;
        return command;
    }

    @Test
    void returnsZeroWhenShutdownAccepted() {
        server.createContext("/shutdown", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertEquals(0, newCommand("/shutdown").call());
    }

    @Test
    void returnsOneOnUnexpectedStatus() {
        server.createContext("/shutdown", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        assertEquals(1, newCommand("/shutdown").call());
    }

    @Test
    void returnsOneOnConnectionErrorByDefault() throws IOException {
        ShutdownCommand command = newCommand("/shutdown");
        try (ServerSocket socket = new ServerSocket(0)) {
            command.options.port = socket.getLocalPort();
        }

        assertEquals(1, command.call());
    }

    @Test
    void returnsZeroOnConnectionErrorWhenIgnored() throws IOException {
        ShutdownCommand command = newCommand("/shutdown");
        command.ignoreConnectionErrors = true;
        try (ServerSocket socket = new ServerSocket(0)) {
            command.options.port = socket.getLocalPort();
        }

        assertEquals(0, command.call());
    }

    @Test
    void methodCanBeOverriddenToGet() {
        // Only handles GET - if --method still defaulted to POST, this
        // would 405 and the command would report failure.
        server.createContext("/shutdown", exchange -> {
            int status = "GET".equals(exchange.getRequestMethod()) ? 200 : 405;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        ShutdownCommand command = newCommand("/shutdown");
        command.method = "GET";

        assertEquals(0, command.call());
    }
}
