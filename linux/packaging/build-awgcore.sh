#!/bin/bash
# Builds awgcore inside a container (see linux/Makefile), not by hand:
#   build-awgcore.sh <output binary>
# Expects the awgcore checkout at /awgcore, a cache for the Rust toolchain at /rust, and
# AWGCORE_REV (the short revision) in the environment. The license files of the crates it links
# go to <output binary>-licenses/.
set -euo pipefail
out=${1:?output binary}

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends ca-certificates curl gcc libc6-dev >/dev/null

# rustup once into the cache; it then installs the toolchain awgcore's rust-toolchain.toml pins.
export RUSTUP_HOME=/rust/rustup CARGO_HOME=/rust/cargo PATH=/rust/cargo/bin:$PATH
if [ ! -x /rust/cargo/bin/rustup ]; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs |
        sh -s -- -q -y --profile minimal --default-toolchain none --no-modify-path
fi
cd /awgcore
rustup show active-toolchain >/dev/null 2>&1 || rustup -q toolchain install

# On x86_64 awgcore is built for x86-64-v3: RustCrypto's AVX2 code calls its intrinsics instead of
# inlining them unless AVX2 is on in every function, and is twice as slow per packet that way
# (awgcore's stage 5). The helper runs amneziawg-go on a CPU without these (engine.go). RUSTFLAGS
# replaces the rustflags of awgcore's .cargo/config.toml, so they are repeated here, checked first.
v3="avx avx2 bmi1 bmi2 f16c fma abm movbe xsave"
if [ "$(uname -m)" = x86_64 ]; then
    want='rustflags = ["--cfg", '"'"'curve25519_dalek_backend="fiat"'"'"']'
    grep -qxF "$want" .cargo/config.toml ||
        { echo "awgcore's .cargo/config.toml changed its rustflags: repeat them here" >&2; exit 1; }
    export RUSTFLAGS='--cfg curve25519_dalek_backend="fiat" -C target-cpu=x86-64-v3'
fi
# An explicit --target keeps RUSTFLAGS off build scripts, which run here.
target=$(uname -m)-unknown-linux-gnu
CARGO_TARGET_DIR=/rust/target cargo build -q --release --locked --offline -p awgcore --target "$target"
install -m755 "/rust/target/$target/release/awgcore" "$out"
# The build machine may not run it (an x86_64 container under Rosetta has no AVX2).
flags=" $(grep -m1 '^flags' /proc/cpuinfo 2>/dev/null) "
runs=1
if [ "$(uname -m)" = x86_64 ]; then
    for f in $v3; do [[ $flags == *" $f "* ]] || runs=0; done
fi
if [ $runs = 1 ]; then
    "$out" --version | head -1
else
    echo "awgcore built for x86-64-v3; this machine cannot run it, so it was not started"
fi

# Every crate in the binary, from the lock file, with the license files it vendors.
licenses="$out-licenses"
mkdir -p "$licenses"
find "$licenses" -mindepth 1 -maxdepth 1 -type f -delete
cargo tree -q --locked --offline -p awgcore -e normal --prefix none |
    sed -n 's/^\([A-Za-z0-9_-]*\) v\([0-9][^ ]*\).*/\1 \2/p' | sort -u |
    while read -r name version; do
        dir=vendor/$name-$version
        [ -d "$dir" ] || continue
        for f in "$dir"/LICENSE* "$dir"/COPYING*; do
            if [ -f "$f" ]; then
                install -m644 "$f" "$licenses/$name-$version-$(basename "$f")"
            fi
        done
    done
echo "license files: $(find "$licenses" -type f | wc -l)"
