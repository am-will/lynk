#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
LLAMA_ROOT="$REPOSITORY_ROOT/android/third_party/llama.cpp"
EXPECTED_REVISION="62061f91088281e65071cc38c5f69ee95c39f14e"

if [ ! -x "$LLAMA_ROOT/build-xcframework.sh" ]; then
    echo "Initialize the llama.cpp submodule before building the local framework." >&2
    exit 1
fi

ACTUAL_REVISION=$(git -C "$LLAMA_ROOT" rev-parse HEAD)
if [ "$ACTUAL_REVISION" != "$EXPECTED_REVISION" ]; then
    echo "Expected llama.cpp $EXPECTED_REVISION but found $ACTUAL_REVISION." >&2
    exit 1
fi

exec "$LLAMA_ROOT/build-xcframework.sh"
