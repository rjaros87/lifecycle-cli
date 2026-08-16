package io.github.rjaros87.lifecycle;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point of the {@code lifecycle} binary.
 * <p>
 * A CLI tool for handling Kubernetes lifecycle hooks (preStop, health,
 * shutdown) by querying a Micronaut application's Management Endpoints
 * (port 8082 by default). Meant to be shipped as a static GraalVM Native
 * Image binary inside distroless images, which have no shell and no
 * {@code curl}/{@code wget}.
 * <p>
 * Plain picocli, no DI framework: every command wires its own
 * {@link ManagementEndpointClient} directly (see e.g. {@link HealthCommand}).
 * For a tool this small, that's simpler and produces a meaningfully smaller
 * native-image binary than pulling in a full IoC container just to satisfy
 * one {@code @Inject} field.
 */
@Command(
        name = "lifecycle",
        description = "CLI tool for Kubernetes lifecycle hooks (preStop, health, shutdown) " +
                "via a Micronaut application's Management Endpoints.",
        mixinStandardHelpOptions = true,
        version = "lifecycle 1.0.0",
        subcommands = {
                PreStopCommand.class,
                HealthCommand.class,
                ShutdownCommand.class
        }
)
public class LifecycleCommand implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LifecycleCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // No subcommand given - print usage and exit with code 0.
        new CommandLine(this).usage(System.out);
    }
}
