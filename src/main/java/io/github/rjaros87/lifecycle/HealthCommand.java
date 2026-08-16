package io.github.rjaros87.lifecycle;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Subcommand meant to be used as a liveness/readiness probe
 * (exec: ["/usr/local/bin/lifecycle", "health"]).
 * Returns exit code 0 when the status is UP, 1 otherwise.
 * <p>
 * Only the HTTP status code is checked - Micronaut and Spring Boot
 * Actuator both already map application health to the response status
 * (200 for UP, 503 for DOWN by default), so parsing the JSON body would
 * just duplicate that logic and make this tool depend on a response
 * format it doesn't actually need to care about.
 */
@Command(
        name = "health",
        description = "Query the /health endpoint and return exit code 0 (UP) or 1 (DOWN/error), " +
                "based purely on the HTTP status code."
)
public class HealthCommand implements Callable<Integer> {

    @Mixin
    CommonOptions options;

    @Option(
            names = "--path",
            defaultValue = "/health",
            description = "Health endpoint path (default: ${DEFAULT-VALUE}). " +
                    "For Spring Boot Actuator, use e.g. /actuator/health."
    )
    String path;

    @Option(
            names = "--method",
            defaultValue = "GET",
            description = "HTTP method to use (GET or POST). Default: ${DEFAULT-VALUE}, " +
                    "matching Micronaut's built-in /health endpoint."
    )
    String method;

    // No DI framework - just construct it. picocli itself instantiates this
    // command class (see @Command(subcommands = ...) on LifecycleCommand),
    // so a field initializer here is sufficient; no constructor needed.
    ManagementEndpointClient client = new ManagementEndpointClient();

    @Override
    public Integer call() {
        var result = client.call(options.scheme, options.host, options.port, path, method, options.timeout());

        if (result.error() != null) {
            System.err.println("[lifecycle health] Failed to connect to " + path + ": " + result.error().getMessage());
            return 1;
        }

        if (options.verbose) {
            System.err.println("[lifecycle health] HTTP " + result.statusCode() + " -> " + result.body());
        }

        if (!result.isSuccess()) {
            System.err.println("[lifecycle health] Service is unhealthy (HTTP " + result.statusCode() + ")");
            return 1;
        }

        System.out.println("UP");
        return 0;
    }
}
