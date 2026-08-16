package io.github.rjaros87.lifecycle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses a real embedded {@link HttpServer} instead of mocks, so these tests
 * exercise the actual wire behavior of {@link ManagementEndpointClient}
 * (built on {@link java.net.HttpURLConnection}) - status codes, headers,
 * connection failures - rather than a stubbed abstraction.
 */
class ManagementEndpointClientTest {

    private final ManagementEndpointClient client = new ManagementEndpointClient();

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200));
        server.createContext("/unhealthy", exchange -> respond(exchange, 503));
        server.createContext("/shutdown", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 200);
            } else {
                respond(exchange, 405);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getReturnsSuccessForHealthyEndpoint() {
        var result = client.call("http", "127.0.0.1", port, "/health", "GET", Duration.ofSeconds(2));

        assertNull(result.error());
        assertEquals(200, result.statusCode());
        assertTrue(result.isSuccess());
    }

    @Test
    void getReturnsFailureForUnhealthyEndpoint() {
        var result = client.call("http", "127.0.0.1", port, "/unhealthy", "GET", Duration.ofSeconds(2));

        assertNull(result.error());
        assertEquals(503, result.statusCode());
        assertFalse(result.isSuccess());
    }

    @Test
    void postSucceedsAgainstShutdownEndpoint() {
        var result = client.call("http", "127.0.0.1", port, "/shutdown", "POST", Duration.ofSeconds(2));

        assertNull(result.error());
        assertEquals(200, result.statusCode());
        assertTrue(result.isSuccess());
    }

    @Test
    void getAgainstShutdownEndpointFailsWithMethodNotAllowed() {
        var result = client.call("http", "127.0.0.1", port, "/shutdown", "GET", Duration.ofSeconds(2));

        assertEquals(405, result.statusCode());
        assertFalse(result.isSuccess());
    }

    @Test
    void pathIsNormalizedWhenMissingLeadingSlash() {
        var result = client.call("http", "127.0.0.1", port, "health", "GET", Duration.ofSeconds(2));

        assertTrue(result.isSuccess());
    }

    @Test
    void connectionErrorIsReportedInsteadOfThrowing() throws IOException {
        var result = client.call("http", "127.0.0.1", closedPort(), "/health", "GET", Duration.ofSeconds(2));

        assertNotNull(result.error());
        assertFalse(result.isSuccess());
        assertEquals(-1, result.statusCode());
    }

    @Test
    void unsupportedHttpMethodThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                client.call("http", "127.0.0.1", port, "/health", "PUT", Duration.ofSeconds(2)));
    }

    @Test
    void httpsSchemeThrowsBecauseTlsIsNotBuiltIn() {
        // This native image is built without --enable-https on purpose (see
        // build.gradle) to keep the binary small - it only ever talks to
        // localhost management endpoints, so TLS support would be dead weight.
        assertThrows(IllegalArgumentException.class, () ->
                client.call("https", "127.0.0.1", port, "/health", "GET", Duration.ofSeconds(2)));
    }

    @Test
    void slowResponseWithinTimeoutStillSucceeds() {
        // Simulates a graceful-shutdown style endpoint that holds the
        // connection open while it does work, then responds. --timeout is
        // an upper bound, not a mandatory wait - a fast response well
        // within a generous timeout must not be affected by it.
        server.createContext("/slow-shutdown", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200);
        });

        var result = client.call("http", "127.0.0.1", port, "/slow-shutdown", "POST", Duration.ofSeconds(5));

        assertNull(result.error());
        assertTrue(result.isSuccess());
    }

    @Test
    void responseBodyIsReadCorrectlyWhenPresent() {
        server.createContext("/echo", exchange -> {
            byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var result = client.call("http", "127.0.0.1", port, "/echo", "GET", Duration.ofSeconds(2));

        assertNull(result.error());
        assertEquals(200, result.statusCode());
        assertEquals("{\"hello\":\"world\"}", result.body());
    }

    /** Opens then immediately closes a socket, guaranteeing "connection refused" on that port. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
