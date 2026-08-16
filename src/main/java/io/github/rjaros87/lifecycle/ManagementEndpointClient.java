package io.github.rjaros87.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client for querying Micronaut Management Endpoints (or compatible
 * endpoints, e.g. Spring Boot Actuator), built on the JDK's old
 * {@link java.net.HttpURLConnection} rather than the modern
 * {@link java.net.http.HttpClient}.
 * <p>
 * This is a middle ground between a hand-rolled {@code Socket} client and
 * the modern {@code HttpClient}: {@code HttpURLConnection} is blocking and
 * HTTP/1.1-only, with none of {@code HttpClient}'s HTTP/2, async/selector,
 * or connection-pooling machinery - all of which GraalVM native-image
 * bakes into the binary regardless of whether it's ever exercised at
 * runtime. Being part of the JDK since Java 1.1, it's also long since
 * battle-tested and needs no extra dependency or native-image reflection
 * config beyond {@code --enable-https} for TLS (same as {@code HttpClient}).
 */
public class ManagementEndpointClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    public EndpointResult call(String scheme, String host, int port, String path, String method, Duration requestTimeout) {
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "Only the 'http' scheme is supported (no TLS in this build): " + scheme);
        }

        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(scheme + "://" + host + ":" + port + normalize(path));
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod(normalizeMethod(method));
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout((int) Math.min(Integer.MAX_VALUE, requestTimeout.toMillis()));

            if ("POST".equals(connection.getRequestMethod())) {
                // Explicit empty body with Content-Length: 0, rather than no
                // body at all - some servers are picky about a missing
                // Content-Length on POST.
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                connection.getOutputStream().close();
            }

            int statusCode = connection.getResponseCode();
            String body = readBody(connection, statusCode);
            return new EndpointResult(statusCode, body, null);
        } catch (IOException e) {
            return new EndpointResult(-1, null, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String normalizeMethod(String method) {
        String normalized = method.toUpperCase();
        if (!normalized.equals("GET") && !normalized.equals("POST")) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
        return normalized;
    }

    private static String readBody(HttpURLConnection connection, int statusCode) throws IOException {
        // For 4xx/5xx responses, the body (if any) is on the error stream -
        // getInputStream() throws for those instead of returning it.
        InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return null;
        }
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String normalize(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Result of calling an endpoint. {@code error != null} means the
     * request never actually completed (e.g. connection refused, timeout,
     * or the process already shut down mid-request).
     */
    public record EndpointResult(int statusCode, String body, Exception error) {
        public boolean isSuccess() {
            return error == null && statusCode >= 200 && statusCode < 300;
        }
    }
}
