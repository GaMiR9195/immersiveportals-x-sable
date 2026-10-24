#!/usr/bin/env bash
# Build the vendored Atlas fork, never upstream stock Rapier.
set -euo pipefail
cd "$(dirname "$0")"
cargo build --release -p sable_rapier
cp target/release/libsable_rapier.so ../sable_rapier_x86_64_linux.so
echo 'Staged ABI 2 Linux library at project root (packaged by processResources).'
