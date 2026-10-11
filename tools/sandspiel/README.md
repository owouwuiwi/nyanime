# Offline Sandspiel build

The upstream Git submodule is immutable. Run commands from the repository root:

```sh
git submodule update --init vendor/sandspiel
npm --prefix tools/sandspiel ci --ignore-scripts
node tools/sandspiel/prepare.mjs
bash tools/sandspiel/build-wasm.sh tools/sandspiel/.work /opt/nyanime-sandspiel-work
node tools/sandspiel/bundle.mjs
npm --prefix tools/sandspiel test
```

On Windows, run the Bash command through WSL, passing the prepared directory as
its `/mnt/c/...` absolute path. Only source copying crosses that mount: Cargo
builds in the Linux work directory. Do not put the Cargo work directory on NTFS.

Build toolchain: Node **24.19.0**, Rust **1.85.1** with
`wasm32-unknown-unknown`, wasm-bindgen CLI **0.2.100**. Cargo and npm dependencies
are exact and locked. The CLI archive checksum is checked before extraction.
`SANDSPIEL_TOOLS` may select a different isolated toolchain root; its `cargo/bin`
must contain rustup and the pinned Rust toolchain must be installed in `rustup`.
The build script never installs or changes the global Rust toolchain.

Bundled outputs and full dependency notices live in
`feature/secret-game/src/main/assets/secret-game/sandspiel`. They are versioned so
an ordinary Gradle build needs neither Node nor Rust nor an Internet connection
for the game. `verifySecretGameAssets` checks their SHA-256 values, adapter inputs
and canonical upstream sources. It does not update the submodule or rebuild
unreviewed sources automatically.

## Adaptations

`prepare.mjs` exports the pinned sources with `git show`, then applies checked
replacements to a generated copy in `.work`. The original Rust cellular rules
are unchanged. The host replaces the original React UI and network bootstrap;
it includes neither accounts/community nor Firebase, Sentry, fonts or service
workers. Rendering imports use the current WASM memory, debug GUI code is
removed, touch conversion uses the actual visible canvas, and explicit renderer
release hooks are added. The fluid input receives the native material, radius
and pause state through an explicit callback; it never reads the removed React
UI singleton or a global pause flag. The upstream HSV helper has no declared package
license; our Apache-2.0 six-sector HSV conversion replaces that helper instead.
The fluid solver and original element shaders otherwise retain their algorithms.

The local document's CSP permits `unsafe-eval` because regl compiles its WebGL
command functions at runtime. `wasm-unsafe-eval` alone permits WASM, not those
functions. Inline scripts and remote resources remain forbidden; Android also
serves an exact local asset allowlist and blocks network/file/content access.
Test the packaged HTML policy as well as the engine: a custom browser harness
without that policy cannot validate production startup. The isolated Android
probe in `docs/secret-game.md` covers the actual policy, assets and native bridge.

`rust-notices.py` reads the locked WASM target graph and collects actual package
license files, plus Rust runtime notices. Build-time macro dependencies are
included conservatively. No inferred license replaces a missing text.

## Updating or replacing

The reviewed revision is **9936e06b07a583aedfba9589c0cead2917f405fb**. An incompatible
revision deliberately fails. Review changes and licenses, update the expected
revision in preparation and Gradle verification, adapt the checked replacements,
rebuild, run tests and perform the Android lifecycle/performance checks in
`docs/secret-game.md`. Commit the gitlink, inputs, assets and notices together.

To replace Sandspiel, implement the internal `SecretGameEngine` contract and
replace its construction in `SecretGameContent`. The host application's
five-tap gesture and navigation remain independent of the simulator.
