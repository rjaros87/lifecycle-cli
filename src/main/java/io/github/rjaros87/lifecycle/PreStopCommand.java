package io.github.rjaros87.lifecycle;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Subcommand meant to be used as a Kubernetes {@code preStop} hook.
 * <p>
 * Common problem: Kubernetes sends SIGTERM to the container at roughly
 * the same time it removes the pod from the Service's Endpoints list -
 * these two operations are asynchronous, so traffic can briefly keep
 * arriving at a pod that is already shutting down. {@code pre-stop}
 * first waits (giving kube-proxy time to update routing) and only then
 * (optionally) calls {@code /shutdown}, so the process terminates
 * cleanly before {@code terminationGracePeriodSeconds} elapses and
 * Kubernetes sends SIGKILL.
 * <p>
 * <b>Long-running graceful shutdowns:</b> if {@code /shutdown} holds the
 * connection open while the server drains in-flight work (this can
 * legitimately take minutes), pass a matching {@code --timeout} (see
 * {@link CommonOptions}), e.g. {@code --timeout 900} for up to 15
 * minutes. That alone is not enough, though: Kubernetes' default
 * {@code terminationGracePeriodSeconds} is only 30 seconds. If the
 * preStop hook is still running when that grace period elapses,
 * Kubernetes sends SIGKILL to the whole pod - killing this process
 * (and the still-draining server) regardless of {@code --timeout}. Set
 * {@code terminationGracePeriodSeconds} on the pod spec to comfortably
 * more than the longest shutdown you expect (see
 * {@code k8s/example-deployment.yaml}).
 */
@Command(
        name = "pre-stop",
        description = "Kubernetes preStop hook: wait for the pod to be deregistered from the " +
                "Service, then optionally call /shutdown."
)
public class PreStopCommand implements Callable<Integer> {

    @Mixin
    CommonOptions options;

    @Option(
            names = "--wait",
            defaultValue = "5",
            description = "Seconds to wait before the next steps - gives kube-proxy time to " +
                    "remove the pod from traffic rotation (default: ${DEFAULT-VALUE})"
    )
    long waitSeconds;

    @Option(
            names = "--trigger-shutdown",
            defaultValue = "true",
            negatable = true,
            description = "Whether to call the /shutdown endpoint after waiting (default: ${DEFAULT-VALUE})"
    )
    boolean triggerShutdown;

    @Option(
            names = "--shutdown-path",
            defaultValue = "/shutdown",
            description = "Shutdown endpoint path (default: ${DEFAULT-VALUE})"
    )
    String shutdownPath;

    @Option(
            names = "--method",
            defaultValue = "POST",
            description = "HTTP method to use for the /shutdown call (GET or POST). " +
                    "Default: ${DEFAULT-VALUE}, matching Micronaut's built-in /shutdown endpoint."
    )
    String method;

    ManagementEndpointClient client = new ManagementEndpointClient();

    @Override
    public Integer call() throws InterruptedException {
        if (waitSeconds > 0) {
            if (options.verbose) {
                System.err.println("[lifecycle pre-stop] Waiting " + waitSeconds
                        + "s for deregistration from the Service...");
            }
            Thread.sleep(waitSeconds * 1000L);
        }

        if (!triggerShutdown) {
            System.out.println("preStop wait finished (shutdown not triggered)");
            return 0;
        }

        var result = client.call(options.scheme, options.host, options.port, shutdownPath, method, options.timeout());

        if (result.error() != null) {
            // A dropped connection during shutdown is expected - the process may
            // have already terminated before it had a chance to respond.
            System.err.println("[lifecycle pre-stop] Connection to " + shutdownPath
                    + " dropped (may be expected if the process is already shutting down): "
                    + result.error().getMessage());
            return 0;
        }

        if (options.verbose) {
            System.err.println("[lifecycle pre-stop] HTTP " + result.statusCode() + " -> " + result.body());
        }

        if (!result.isSuccess()) {
            System.err.println("[lifecycle pre-stop] Unexpected HTTP status " + result.statusCode());
            return 1;
        }

        System.out.println("preStop finished - shutdown initiated");
        return 0;
    }
}
