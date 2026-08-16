#!/usr/bin/env bash
set -euo pipefail

# Builds the "builder" stage of the Dockerfile (GraalVM native-image compile)
# and copies the resulting binary out to ./lifecycle on the host.
#
# Usage: ./scripts/extract-binary.sh

cd "$(dirname "${BASH_SOURCE[0]}")/.."

IMAGE_TAG="lifecycle-builder"
BINARY_IN_IMAGE="/workspace/build/native/nativeCompile/lifecycle"
OUT_FILE="./lifecycle"

echo "==> Building builder stage (this runs the native-image compile, can take a while)..."
docker build --target builder -t "$IMAGE_TAG" .

echo "==> Creating temporary container..."
container_id=$(docker create "$IMAGE_TAG")

cleanup() {
  docker rm "$container_id" > /dev/null
}
trap cleanup EXIT

echo "==> Copying binary to $OUT_FILE..."
docker cp "$container_id:$BINARY_IN_IMAGE" "$OUT_FILE"
chmod +x "$OUT_FILE"

echo "==> Done: $OUT_FILE"
"$OUT_FILE" --help
