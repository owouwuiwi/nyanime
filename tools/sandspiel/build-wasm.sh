#!/usr/bin/env bash
set -euo pipefail
# Invoke explicitly: bash build-wasm.sh PREPARED_DIRECTORY [LINUX_WORK_DIRECTORY]
SOURCE=$(realpath "$1")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WORK=${2:-/opt/nyanime-sandspiel-work}
TASK_TOOLS=${SANDSPIEL_TOOLS:-/opt/nyanime-sandspiel-tools}
export CARGO_HOME="$TASK_TOOLS/cargo"
export RUSTUP_HOME="$TASK_TOOLS/rustup"
export PATH="$CARGO_HOME/bin:$PATH"
mkdir -p "$WORK/src" "$SOURCE/wasm" "$TASK_TOOLS/bindgen"
cp "$SOURCE/crate/Cargo.toml" "$WORK/Cargo.toml"
cp "$SOURCE/crate/src/"*.rs "$WORK/src/"
if [ -f "$SOURCE/crate/Cargo.lock" ]; then cp "$SOURCE/crate/Cargo.lock" "$WORK/Cargo.lock"; fi
cd "$WORK"
if [ ! -f Cargo.lock ]; then cargo +1.85.1 generate-lockfile; fi
cargo +1.85.1 build --locked --release --target wasm32-unknown-unknown -j 2
cp Cargo.lock "$SOURCE/crate/Cargo.lock"
CLI="$TASK_TOOLS/bindgen/wasm-bindgen-0.2.100-x86_64-unknown-linux-musl/wasm-bindgen"
if [ ! -x "$CLI" ]; then
  curl --fail --location --retry 2 -o "$TASK_TOOLS/bindgen/archive.tar.gz" https://github.com/wasm-bindgen/wasm-bindgen/releases/download/0.2.100/wasm-bindgen-0.2.100-x86_64-unknown-linux-musl.tar.gz
  echo "63d6a38deb65bd7023c02bdf382ab66b0d2c0241c8582fd3413b5a808b8aeb5b  $TASK_TOOLS/bindgen/archive.tar.gz" | sha256sum --check
  tar -xzf "$TASK_TOOLS/bindgen/archive.tar.gz" -C "$TASK_TOOLS/bindgen"
fi
[ "$("$CLI" --version)" = 'wasm-bindgen 0.2.100' ]
"$CLI" --target web --out-dir "$SOURCE/wasm" target/wasm32-unknown-unknown/release/sandtable.wasm
printf '%s\n' 'Rust 1.85.1; wasm-bindgen 0.2.100; original cellular rules; fluid state is transient.' > "$SOURCE/wasm/toolchain.txt"
sha256sum "$CLI" >> "$SOURCE/wasm/toolchain.txt"
python3 "$SCRIPT_DIR/rust-notices.py" "$SOURCE/wasm/RUST-NOTICES.txt"
