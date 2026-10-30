#!/usr/bin/env bash
# Кросс-сборка патченных нативов Rapier под ВСЕ платформы на одной машине с Docker
# (Linux x86_64/arm64, macOS с Docker Desktop, WSL2). Тот же способ, что у самого Sable
# (sable_rapier/build.gradle + .github/workflows/build-rust.yml): cargo-zigbuild для Linux и
# macOS, cargo-xwin (MSVC) для Windows. Образы — natives/container/{zigbuild,xwin}, Rust —
# закреплённый nightly из natives/rust-toolchain.toml.
#
# Почему так, а не «по раннеру на ОС»: один хост, одни и те же образы и тулчейн для всех целей —
# сборка воспроизводима, не зависит от того, что предустановлено на раннерах Windows/macOS, а
# Windows-DLL собирается MSVC-линкером (как у стокового Sable) — без GNU-ld и без
# fix_import_descriptors.py.
#
# Использование (из корня проекта или из natives/):
#   bash natives/build-cross.sh                      # linux x86_64+aarch64, macos x86_64+aarch64, windows x86_64
#   bash natives/build-cross.sh all                  # то же + windows aarch64
#   bash natives/build-cross.sh linux-x86_64 windows-x86_64
#   NO_STAGE=1 bash natives/build-cross.sh            # только natives/out/, без раскладки в проект
#   DOCKER=podman bash natives/build-cross.sh         # другой движок контейнеров
#
# Результат: natives/out/sable_rapier_<arch>_<os>.<ext>. Затем (если не NO_STAGE=1):
#   Windows x86_64 -> src/main/resources/natives_ipl/ (заменяет лежащую там DLL; копия в корне
#                     проекта проиграла бы ей из-за DuplicatesStrategy.EXCLUDE в processResources),
#   остальные      -> корень проекта (processResources кладёт их в /natives_ipl/ jar'а).
# Код выхода != 0, если хотя бы одна цель не собралась (остальные всё равно собираются).
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
docker="${DOCKER:-docker}"
toolchain="$(sed -n 's/^channel *= *"\([^"]*\)".*/\1/p' "$here/rust-toolchain.toml")"
if [ -z "$toolchain" ]; then
  echo "cannot read toolchain channel from $here/rust-toolchain.toml" >&2
  exit 2
fi
cache="${IPL_CARGO_CACHE:-$here/target/docker-cargo}"
out="$here/out"
zig_image="ipl-sable-zigbuild:${toolchain}"
xwin_image="ipl-sable-xwin:${toolchain}"

default_targets="linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64 windows-x86_64"
if [ "$#" -eq 0 ]; then
  set -- $default_targets
elif [ "$*" = "all" ]; then
  set -- $default_targets windows-aarch64
fi

# цель -> "образ triple собранный-файл имя-в-jar"
spec() {
  case "$1" in
    linux-x86_64)    echo "zig x86_64-unknown-linux-gnu.2.17 libsable_rapier.so sable_rapier_x86_64_linux.so" ;;
    linux-aarch64)   echo "zig aarch64-unknown-linux-gnu.2.17 libsable_rapier.so sable_rapier_aarch64_linux.so" ;;
    macos-x86_64)    echo "zig x86_64-apple-darwin libsable_rapier.dylib sable_rapier_x86_64_macos.dylib" ;;
    macos-aarch64)   echo "zig aarch64-apple-darwin libsable_rapier.dylib sable_rapier_aarch64_macos.dylib" ;;
    windows-x86_64)  echo "xwin x86_64-pc-windows-msvc sable_rapier.dll sable_rapier_x86_64_windows.dll" ;;
    windows-aarch64) echo "xwin aarch64-pc-windows-msvc sable_rapier.dll sable_rapier_aarch64_windows.dll" ;;
    *) return 1 ;;
  esac
}

for target in "$@"; do
  spec "$target" > /dev/null || { echo "unknown target: $target" >&2; exit 2; }
done

mkdir -p "$cache/registry" "$cache/git" "$out"

need_zig=0; need_xwin=0
for target in "$@"; do
  case "$(spec "$target" | cut -d' ' -f1)" in
    zig) need_zig=1 ;;
    xwin) need_xwin=1 ;;
  esac
done

build_image() { # тег, каталог
  if "$docker" image inspect "$1" > /dev/null 2>&1; then return 0; fi
  echo "== building image $1"
  "$docker" build -t "$1" "$here/container/$2" --build-arg "RUST_VERSION=$toolchain"
}
[ "$need_zig" = 1 ] && { build_image "$zig_image" zigbuild || exit 3; }
[ "$need_xwin" = 1 ] && { build_image "$xwin_image" xwin || exit 3; }

run_in() { # образ, команда...
  local image="$1"; shift
  "$docker" run --rm \
    -v "$here:/io" \
    -v "$cache/registry:/usr/local/cargo/registry" \
    -v "$cache/git:/usr/local/cargo/git" \
    -w /io/rapier \
    "$image" "$@"
}

failed=()
for target in "$@"; do
  read -r kind triple built staged <<< "$(spec "$target")"
  echo "== $target ($triple)"
  if [ "$kind" = zig ]; then
    image="$zig_image"; cmd=(cargo zigbuild --release --target "$triple")
  else
    image="$xwin_image"; cmd=(cargo xwin build --release --target "$triple")
  fi
  # zigbuild кладёт результат в каталог цели БЕЗ суффикса glibc (.2.17).
  dir_triple="${triple%.2.17}"
  rm -f "$out/$staged"
  if run_in "$image" "${cmd[@]}" && [ -f "$here/target/$dir_triple/release/$built" ]; then
    cp -f "$here/target/$dir_triple/release/$built" "$out/$staged"
    echo "   ok: natives/out/$staged ($(wc -c < "$out/$staged") bytes)"
    command -v file > /dev/null 2>&1 && file -b "$out/$staged" | sed 's/^/   /'
  else
    echo "   FAILED: $target" >&2
    failed+=("$target")
  fi
done

# Контейнер пишет от root: вернуть файлы владельцу, иначе локально не сделать cargo clean,
# а на CI кэш/артефакты читаются пользователем раннера.
if [ "$(id -u)" != 0 ]; then
  img="$zig_image"; [ "$need_zig" = 1 ] || img="$xwin_image"
  "$docker" run --rm -v "$here:/io" "$img" \
    chown -R "$(id -u):$(id -g)" /io/target /io/out > /dev/null 2>&1 || true
fi

if [ "${NO_STAGE:-0}" != 1 ]; then
  for f in "$out"/sable_rapier_*; do
    [ -e "$f" ] || continue
    name="$(basename "$f")"
    if [ "$name" = sable_rapier_x86_64_windows.dll ]; then
      mkdir -p "$root/src/main/resources/natives_ipl"
      cp -f "$f" "$root/src/main/resources/natives_ipl/$name"
      echo "staged: src/main/resources/natives_ipl/$name"
    else
      cp -f "$f" "$root/$name"
      echo "staged: $name"
    fi
  done
fi

if [ "${#failed[@]}" -gt 0 ]; then
  echo "failed targets: ${failed[*]}" >&2
  exit 1
fi
echo "all requested targets built"
