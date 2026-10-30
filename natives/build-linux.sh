#!/usr/bin/env bash
# IPSable natives build for Linux (also works on macOS).
#
# Builds the patched sable_rapier library for the HOST platform and stages it at the project
# root as sable_rapier_<arch>_linux.so (macOS: sable_rapier_<arch>_macos.dylib), where
# build.gradle bundles it into /natives_ipl/ for IplNativesOverrideMixin.
#
# Prereqs (one-time):
#   rustup (https://rustup.rs). The pinned nightly in rust-toolchain.toml is installed
#   automatically on first build. Linux also needs a C toolchain (gcc/clang + binutils).
#
# Usage:  ./natives/build-linux.sh            (from the project root or from natives/)
#         CARGO=/path/to/cargo ./natives/build-linux.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
cargo="${CARGO:-cargo}"

case "$(uname -m)" in
  x86_64|amd64) arch=x86_64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

case "$(uname -s)" in
  Linux)  built="libsable_rapier.so";    staged="sable_rapier_${arch}_linux.so" ;;
  Darwin) built="libsable_rapier.dylib"; staged="sable_rapier_${arch}_macos.dylib" ;;
  *) echo "unsupported OS: $(uname -s) (use build-windows.ps1 on Windows)" >&2; exit 1 ;;
esac

(cd "$here/rapier" && "$cargo" build --release)

lib="$here/target/release/$built"
[ -f "$lib" ] || { echo "expected $lib after build" >&2; exit 1; }
cp -f "$lib" "$root/$staged"
echo "staged: $root/$staged ($(wc -c < "$root/$staged") bytes)"
