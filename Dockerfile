# syntax=docker/dockerfile:1

########################################################################
# Stage 1: build a fully static GraalVM Native Image binary
# (the muslib image ships the musl toolchain needed for --static --libc=musl)
#
# Tag scheme changed with GraalVM 25.2+: it's now
# "<jdk-feature>i<graalvm-increment>[-muslib]", e.g. 25i2-muslib selects
# JDK 25 feature version, GraalVM's 2nd feature release on top of it.
########################################################################
FROM ghcr.io/graalvm/native-image-community:25i2-muslib AS builder

# Tools needed to download and unpack Gradle (base image is Oracle Linux 9)
RUN microdnf install -y curl unzip tar gzip findutils \
    && microdnf clean all

# Gradle 8.14.5+ is required to run the Gradle daemon itself on JDK 25
# (Gradle 9.0.0 raised the daemon floor to Java 17; JDK 25 support for the
# daemon specifically landed in 8.14.5). Since this image's only JDK is the
# GraalVM 25 install above, Gradle has no older JDK to fall back to here.
ARG GRADLE_VERSION=8.14.5
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip
ENV PATH="/opt/gradle-${GRADLE_VERSION}/bin:${PATH}"

WORKDIR /workspace

# Build files first, to make the most of Docker layer caching
COPY build.gradle settings.gradle gradle.properties ./
COPY src ./src

# --no-daemon: avoid leaving a daemon process behind in the CI container/layer
RUN gradle nativeCompile --no-daemon

########################################################################
# Stage 2: final distroless image with the compiled binary
# distroless/static doesn't even ship glibc - that's why the binary
# must be fully static (see --static --libc=musl in build.gradle)
########################################################################
FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=builder /workspace/build/native/nativeCompile/lifecycle /usr/local/bin/lifecycle

ENTRYPOINT ["/usr/local/bin/lifecycle"]
CMD ["--help"]
