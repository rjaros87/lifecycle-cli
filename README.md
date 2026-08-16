# lifecycle-cli

A small, static GraalVM native binary called **`lifecycle`**, built to run
inside **distroless** Kubernetes images. It handles the three most common
lifecycle needs - `preStop`, `health`, `shutdown` - by calling a Micronaut
(or Spring Boot Actuator) management endpoint over plain HTTP.

## Purpose

Distroless images have no shell, no `curl`, no `wget` - so an
`httpGet` probe on a separate management port (commonly `8082`) can't be
replicated with `exec: ["curl", ...]` the way it could on a normal image.

That alone would be a minor inconvenience. The real problem shows up with
an Istio (or similar) sidecar:

- Istio **does** automatically rewrite `livenessProbe`/`readinessProbe`
  `httpGet` calls (via `sidecar.istio.io/rewriteAppHTTPProbers`, routed
  through the Istio agent on port 15020) so they work despite mTLS.
- Istio does **not** rewrite the `lifecycle.preStop` hook the same way.
  If your app enforces strict mTLS, an `httpGet` preStop hook gets
  rejected by Envoy, Kubernetes gives up on it immediately, and sends
  SIGTERM straight away - defeating the entire point of a preStop hook.
  ([istio/istio#26099](https://github.com/istio/istio/issues/26099),
  still open.)

`exec` probes/hooks sidestep this completely: the kubelet runs them
*inside* the container via the container runtime, never touching the
network - so Envoy's mTLS enforcement never comes into play. The catch is
that `exec` needs something to execute, and a distroless image has
nothing capable of speaking HTTP. `lifecycle` is that something: a single
static binary with no dependencies, callable from `exec` in `preStop`,
`livenessProbe`, and `readinessProbe` alike.

## Usage

| Command               | Method | Default path | Use case                                  |
|------------------------|--------|---------------|---------------------------------------------|
| `lifecycle health`     | GET    | `/health`     | `livenessProbe` / `readinessProbe`           |
| `lifecycle shutdown`   | POST   | `/shutdown`   | manually trigger graceful shutdown           |
| `lifecycle pre-stop`   | POST   | `/shutdown`   | `lifecycle.preStop` hook (wait + shutdown)   |

```bash
lifecycle health   --host 127.0.0.1 --port 8082 --path /health
lifecycle shutdown --host 127.0.0.1 --port 8082 --path /shutdown
lifecycle pre-stop --port 8082 --wait 5 --shutdown-path /shutdown
```

Common options (all three subcommands): `--host` (default `127.0.0.1`),
`--port` (default `8082`), `--timeout` (seconds, default `5`), `--verbose`.
Run `lifecycle <command> --help` for the full list.

Exit codes: `0` = success, `1` = error/unhealthy - ready to use directly
as an `exec` command in K8s probes and hooks.

Only plain `http` is supported (no TLS built into this binary - see
`ManagementEndpointClient.java`). Since it only ever talks to a
management endpoint in the same pod, that's not a real limitation.

> Works against Spring Boot Actuator too - just pass
> `--path /actuator/health` / `--path /actuator/shutdown`.

## Long graceful shutdowns

If your app's `/shutdown` holds the connection open while it drains
in-flight work (this can legitimately take minutes), two separate
timeouts need to agree:

1. **`--timeout`** on `lifecycle` itself - an upper bound on the HTTP
   wait, safe to set generously, e.g. `--timeout 900` for 15 minutes.
2. **`terminationGracePeriodSeconds`** on the pod spec - defaults to just
   **30 seconds**. If the preStop hook is still running when this
   elapses, Kubernetes SIGKILLs the whole pod regardless of `--timeout`.

See `k8s/example-deployment.yaml` (`example-app-slow-shutdown`) for both
set consistently.

## Requirements on the target application

Enable the relevant endpoints on the app you want to manage. For
Micronaut (e.g. in `application.yml`):

```yaml
endpoints:
  health:
    enabled: true
  shutdown:
    enabled: true
    sensitive: false
```

## Build

Requires JDK 25.

```bash
gradle run --args="health --host localhost --port 8082"   # JVM, fast dev loop
gradle nativeCompile                                        # native binary (needs GraalVM)
./build/native/nativeCompile/lifecycle health --port 8082
gradle test
```

> No Gradle Wrapper is checked in (`gradle-wrapper.jar` is a binary file).
> Generate it yourself with `gradle wrapper --gradle-version 8.14.5`, or use
> a system-installed `gradle`. CI uses `gradle/actions/setup-gradle`, so
> it doesn't need the wrapper either.

## Docker

```bash
docker build -t lifecycle-cli:local .
docker run --rm lifecycle-cli:local health --host host.docker.internal --port 8082
```

Multi-stage build (GraalVM -> `gcr.io/distroless/static-debian12`), fully
static binary (`--static --libc=musl`) - works in any distroless image,
not just this one.

`host.docker.internal` only resolves on Docker Desktop. On plain Linux
Docker Engine, use `--add-host=host.docker.internal:host-gateway` or
`--network host` instead - or just run `./scripts/extract-binary.sh` to
pull the binary out and run it directly on the host.

## Using it in your own application image

This repo doesn't publish a Docker image - only raw binaries, attached to
GitHub Releases. To use it in your own distroless app image:

```bash
gh release download <tag> --repo <owner>/lifecycle-cli --pattern '*.tar.gz'
tar -xzf lifecycle-linux-amd64.tar.gz
```

```dockerfile
FROM gcr.io/distroless/java25-debian12
COPY lifecycle /usr/local/bin/lifecycle
COPY target/your-app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

See `k8s/example-deployment.yaml` for the matching pod spec.

## Testing

```bash
gradle test
```

Unit tests use a real embedded `com.sun.net.httpserver.HttpServer` (no
mocks) and construct command objects directly - no DI framework in play.
Both CI workflows run `gradle test` as a gate before building anything.

`scripts/health_stub.py` is a dependency-free Python stub server for
manual end-to-end testing (`GET /health` / `POST /shutdown`, plain status
codes, no body needed):

```bash
python scripts/health_stub.py
./build/native/nativeCompile/lifecycle health --port 8082
```

## CI/CD

- **`.github/workflows/ci.yml`** - on push/PR to `main`: `gradle test`,
  then a Dockerfile build-check (`push: false`, nothing published).
- **`.github/workflows/release.yml`** - on a published GitHub Release or
  a `v*.*.*` tag: `gradle test`, then native binaries for `linux/amd64`
  and `linux/arm64` (built on real arm64 runners - `native-image` doesn't
  cross-compile), uploaded as Release assets.

## Project structure

```
build.gradle                  application + GraalVM Native Image plugins
src/main/java/.../
  LifecycleCommand.java        root "lifecycle" command (plain picocli)
  CommonOptions.java            shared options (--host, --port, --timeout, ...)
  ManagementEndpointClient.java  HTTP client (java.net.HttpURLConnection)
  HealthCommand.java            "health" subcommand
  ShutdownCommand.java          "shutdown" subcommand
  PreStopCommand.java           "pre-stop" subcommand
src/test/java/.../             unit tests
scripts/health_stub.py         stub server for manual testing
scripts/extract-binary.sh      pulls the compiled binary out of the Docker build
Dockerfile                     multi-stage: GraalVM -> distroless/static
.github/workflows/ci.yml       test -> Dockerfile build-check (no push)
.github/workflows/release.yml  test -> amd64+arm64 binaries as Release assets
k8s/example-deployment.yaml    example Deployment usage
```
