package io.github.rjaros87.lifecycle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthCommandTest {

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

    private HealthCommand newCommand(String path) {
        HealthCommand command = new HealthCommand();
        command.client = new ManagementEndpointClient();
        command.path = path;
        command.method = "GET";
        command.options = new CommonOptions();
        command.options.host = "127.0.0.1";
        command.options.port = port;
        command.options.scheme = "http";
        command.options.timeoutSeconds = 2;
        return command;
    }

    @Test
    void returnsZeroWhenEndpointRespondsWith2xx() {
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertEquals(0, newCommand("/health").call());
    }

    @Test
    void returnsOneWhenEndpointRespondsWith503() {
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        assertEquals(1, newCommand("/health").call());
    }

    @Test
    void returnsOneWhenPathDoesNotExist() {
        // No context registered for /missing -> the JDK HttpServer answers 404.
        assertEquals(1, newCommand("/missing").call());
    }

    @Test
    void returnsOneWhenNothingIsListening() throws IOException {
        HealthCommand command = newCommand("/health");
        try (ServerSocket socket = new ServerSocket(0)) {
            command.options.port = socket.getLocalPort();
        }

        assertEquals(1, command.call());
    }

    @Test
    void ignoresResponseBodyEntirely() {
        // Body says DOWN, but the status code is 2xx - health should only ever
        // look at the HTTP status, so this must still count as UP.
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"DOWN\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertEquals(0, newCommand("/health").call());
    }

    @Test
    void methodCanBeOverriddenToPost() {
        // Only handles POST - if --method still defaulted to GET, this
        // would 405/404 and the command would report unhealthy.
        server.createContext("/health", exchange -> {
            int status = "POST".equals(exchange.getRequestMethod()) ? 200 : 405;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        HealthCommand command = newCommand("/health");
        command.method = "POST";

        assertEquals(0, command.call());
    }
}
