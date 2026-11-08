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
CARGO_TARGET_DIR=/rust/target cargo build -q --release --locked --offline -p awgcore
install -m755 /rust/target/release/awgcore "$out"
"$out" --version | head -1

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
