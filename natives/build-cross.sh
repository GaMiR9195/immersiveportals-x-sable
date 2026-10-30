#!/usr/bin/env bash
# IPSable natives cross-build for release jars (same toolchain as Sable's own natives).
#
# Builds the patched sable_rapier library for ONE target inside the Docker images from
# natives/container/ (cargo-zigbuild for Linux/macOS, cargo-xwin for Windows MSVC) and
# stages it at the project root as sable_rapier_<arch>_<os>.<ext>, where build.gradle
# packs it. The release workflow (.github/workflows/release.yml) runs this once per target.
#
# Prereqs: Docker. Everything else (pinned nightly, targets, linkers, Windows SDK) lives
# in the images, so any host OS works.
#
# Usage:  ./natives/build-cross.sh <windows|linux|macos> <x86_64|aarch64>
set -euo pipefail

usage="usage: $0 <windows|linux|macos> <x86_64|aarch64>"
os="${1:?$usage}"
arch="${2:?$usage}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
rust_version="$(sed -n 's/^channel *= *"\(.*\)".*/\1/p' "$here/rust-toolchain.toml")"

case "$arch" in
  x86_64|aarch64) ;;
  *) echo "unsupported arch: $arch ($usage)" >&2; exit 1 ;;
esac

# Target table mirrors Sable's sable_rapier/build.gradle. Linux links against glibc 2.17
# so the library loads on old distros.
case "$os" in
  windows) triple="$arch-pc-windows-msvc";   image=xwin;     cargo_args=(xwin build --target "$triple");  built="sable_rapier.dll" ;;
  linux)   triple="$arch-unknown-linux-gnu"; image=zigbuild; cargo_args=(zigbuild --target "$triple.2.17"); built="libsable_rapier.so" ;;
  macos)   triple="$arch-apple-darwin";      image=zigbuild; cargo_args=(zigbuild --target "$triple");    built="libsable_rapier.dylib" ;;
  *) echo "unsupported os: $os ($usage)" >&2; exit 1 ;;
esac
staged="sable_rapier_${arch}_${os}.${built##*.}"

# Git Bash on Windows: stop MSYS from rewriting the container-side paths, and hand Docker
# native host paths.
export MSYS_NO_PATHCONV=1
host_path() { if (cd "$1" && pwd -W) >/dev/null 2>&1; then (cd "$1" && pwd -W); else echo "$1"; fi; }

tag="ipl-sable-build-$image:$rust_version"
docker build -t "$tag" --build-arg "RUST_VERSION=$rust_version" "$(host_path "$here/container/$image")"

# Crate downloads persist between runs (and between CI jobs via actions/cache).
cargo_cache="${CARGO_CACHE_DIR:-$here/target/docker-cargo}"
mkdir -p "$cargo_cache/registry" "$cargo_cache/git"

docker run --rm \
  -v "$(host_path "$here"):/io" \
  -v "$(host_path "$cargo_cache/registry"):/usr/local/cargo/registry" \
  -v "$(host_path "$cargo_cache/git"):/usr/local/cargo/git" \
  -w /io/rapier \
  "$tag" cargo "${cargo_args[@]}" --release

lib="$here/target/$triple/release/$built"
[ -f "$lib" ] || { echo "expected $lib after build" >&2; exit 1; }
cp -f "$lib" "$root/$staged"
echo "staged: $root/$staged ($(wc -c < "$root/$staged") bytes)"
