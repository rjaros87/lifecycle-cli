package io.github.rjaros87.lifecycle;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Subcommand that calls the /shutdown endpoint to gracefully stop
 * a Micronaut application (or a compatible Actuator endpoint).
 */
@Command(
        name = "shutdown",
        description = "Call the /shutdown endpoint to gracefully stop the application."
)
public class ShutdownCommand implements Callable<Integer> {

    @Mixin
    CommonOptions options;

    @Option(
            names = "--path",
            defaultValue = "/shutdown",
            description = "Shutdown endpoint path (default: ${DEFAULT-VALUE}). " +
                    "For Spring Boot Actuator, use e.g. /actuator/shutdown."
    )
    String path;

    @Option(
            names = "--ignore-connection-errors",
            description = "Return exit code 0 even if the process disappears before responding " +
                    "(expected behavior on shutdown - the connection is often dropped mid-flight)."
    )
    boolean ignoreConnectionErrors;

    @Option(
            names = "--method",
            defaultValue = "POST",
            description = "HTTP method to use (GET or POST). Default: ${DEFAULT-VALUE}, " +
                    "matching Micronaut's built-in /shutdown endpoint (it's annotated @Write, " +
                    "not @Read, since it has a side effect - killing the process)."
    )
    String method;

    ManagementEndpointClient client = new ManagementEndpointClient();

    @Override
    public Integer call() {
        var result = client.call(options.scheme, options.host, options.port, path, method, options.timeout());

        if (result.error() != null) {
            if (ignoreConnectionErrors) {
                System.err.println("[lifecycle shutdown] Connection dropped (expected during shutdown): "
                        + result.error().getMessage());
                return 0;
            }
            System.err.println("[lifecycle shutdown] Failed to call " + path + ": " + result.error().getMessage());
            return 1;
        }

        if (options.verbose) {
            System.err.println("[lifecycle shutdown] HTTP " + result.statusCode() + " -> " + result.body());
        }

        if (!result.isSuccess()) {
            System.err.println("[lifecycle shutdown] Unexpected HTTP status " + result.statusCode());
            return 1;
        }

        System.out.println("Shutdown initiated");
        return 0;
    }
}
