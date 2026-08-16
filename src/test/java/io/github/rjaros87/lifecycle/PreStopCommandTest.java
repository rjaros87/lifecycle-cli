package io.github.rjaros87.lifecycle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PreStopCommandTest {

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

    private PreStopCommand newCommand() {
        PreStopCommand command = new PreStopCommand();
        command.client = new ManagementEndpointClient();
        command.shutdownPath = "/shutdown";
        command.waitSeconds = 0; // keep the test suite fast
        command.triggerShutdown = true;
        command.method = "POST";
        command.options = new CommonOptions();
        command.options.host = "127.0.0.1";
        command.options.port = port;
        command.options.scheme = "http";
        command.options.timeoutSeconds = 2;
        return command;
    }

    @Test
    void callsShutdownAfterWaitAndReturnsZero() throws InterruptedException {
        server.createContext("/shutdown", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertEquals(0, newCommand().call());
    }

    @Test
    void skipsShutdownCallWhenDisabled() throws InterruptedException {
        AtomicBoolean called = new AtomicBoolean(false);
        server.createContext("/shutdown", exchange -> {
            called.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        PreStopCommand command = newCommand();
        command.triggerShutdown = false;

        assertEquals(0, command.call());
        assertFalse(called.get());
    }

    @Test
    void treatsDroppedConnectionAsSuccessNotFailure() throws InterruptedException, IOException {
        PreStopCommand command = newCommand();
        try (ServerSocket socket = new ServerSocket(0)) {
            command.options.port = socket.getLocalPort();
        }

        // A closed connection during shutdown is expected (the process may
        // already be gone) - pre-stop must not fail the K8s hook because of it.
        assertEquals(0, command.call());
    }

    @Test
    void returnsOneOnUnexpectedStatus() throws InterruptedException {
        server.createContext("/shutdown", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        assertEquals(1, newCommand().call());
    }

    @Test
    void actuallyWaitsBeforeCallingShutdown() throws InterruptedException {
        server.createContext("/shutdown", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        PreStopCommand command = newCommand();
        command.waitSeconds = 1;

        long start = System.nanoTime();
        assertEquals(0, command.call());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        org.junit.jupiter.api.Assertions.assertTrue(elapsedMillis >= 900,
                "expected at least ~1s wait, was " + elapsedMillis + "ms");
    }

    @Test
    void methodCanBeOverriddenToGet() throws InterruptedException {
        // Only handles GET - if --method still defaulted to POST, this
        // would 405 and the command would report failure.
        server.createContext("/shutdown", exchange -> {
            int status = "GET".equals(exchange.getRequestMethod()) ? 200 : 405;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        PreStopCommand command = newCommand();
        command.method = "GET";

        assertEquals(0, command.call());
    }
}
