# IPSable local natives build (portal-physics spec phase 4).
#
# Builds the patched sable_rapier natives for Windows x86_64 and stages the DLL into this
# mod's resources (/natives_ipl/), where IplNativesOverrideMixin loads it in place of the
# sable jar's bundled build.
#
# Prereqs (one-time):
#   rustup with toolchain nightly-2026-01-29 (GNU host: x86_64-pc-windows-gnu),
#   components rust-mingw + llvm-tools.
#
# Toolchain quirks encoded here:
#   - raw-dylib crates need dlltool; the self-contained MinGW one can't spawn its
#     assembler, so we use llvm-ar dispatched by name (llvm-dlltool has no separate exe).
#   - upstream's -Wl,-Brepro rustflag is stripped in .cargo/config.toml (rustup's ld
#     doesn't support it; dev builds don't need reproducibility).

# NOTE: no $ErrorActionPreference=Stop — cargo writes progress to stderr, which PS 5.1
# wraps into NativeCommandErrors and would abort a SUCCESSFUL build. Gate on exit codes.
$toolchainBin = "$env:USERPROFILE\.rustup\toolchains\nightly-2026-01-29-x86_64-pc-windows-gnu\lib\rustlib\x86_64-pc-windows-gnu\bin"
$lltools = Join-Path $env:TEMP "ipl-lltools"
New-Item -ItemType Directory -Force $lltools | Out-Null
if (!(Test-Path "$lltools\dlltool.exe")) {
    Copy-Item "$toolchainBin\llvm-ar.exe" "$lltools\dlltool.exe"
}
$env:PATH = "$lltools;$env:PATH"

Push-Location "$PSScriptRoot\rapier"
try {
    & "$env:USERPROFILE\.cargo\bin\cargo.exe" build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$dll = "$PSScriptRoot\target\release\sable_rapier.dll"
if (!(Test-Path $dll)) { throw "expected $dll after build" }
$dest = "$PSScriptRoot\..\src\main\resources\natives_ipl"
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item $dll "$dest\sable_rapier_x86_64_windows.dll" -Force
Write-Output "staged: $dest\sable_rapier_x86_64_windows.dll ($((Get-Item $dll).Length) bytes)"
