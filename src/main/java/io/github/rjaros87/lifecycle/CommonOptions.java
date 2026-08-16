package io.github.rjaros87.lifecycle;

import picocli.CommandLine.Option;

import java.time.Duration;

/**
 * Options shared by all subcommands (health, shutdown, pre-stop).
 * Included in each subcommand via the {@code @Mixin} annotation.
 */
public class CommonOptions {

    @Option(
            names = {"-H", "--host"},
            defaultValue = "127.0.0.1",
            description = "Management endpoint host (default: ${DEFAULT-VALUE})"
    )
    String host;

    @Option(
            names = {"-p", "--port"},
            defaultValue = "8082",
            description = "Management endpoint port (default: ${DEFAULT-VALUE})"
    )
    int port;

    @Option(
            names = {"-s", "--scheme"},
            defaultValue = "http",
            description = "Connection scheme. Only 'http' is supported - this build has no TLS " +
                    "support (see README) - the option exists for forward compatibility. " +
                    "(default: ${DEFAULT-VALUE})"
    )
    String scheme;

    @Option(
            names = {"-t", "--timeout"},
            defaultValue = "5",
            description = "Timeout for the whole HTTP request/response exchange, in seconds " +
                    "(default: ${DEFAULT-VALUE}). Safe to set very high for a slow graceful " +
                    "shutdown that holds the connection open (e.g. '--timeout 900' for up to " +
                    "15 minutes) - see PreStopCommand / README for the Kubernetes " +
                    "terminationGracePeriodSeconds caveat that goes with that."
    )
    long timeoutSeconds;

    @Option(
            names = {"-v", "--verbose"},
            description = "Print response details to stderr"
    )
    boolean verbose;

    Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }
}
