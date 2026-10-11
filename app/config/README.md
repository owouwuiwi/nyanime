# Offline dependency attribution

AboutLibraries 11.6.3 reads `libraries/` and `licenses/` when collecting the
application dependency graph. These files supplement the actual Maven metadata;
they do not change a component's license or the dependency selected by Gradle.

Some POMs describe a license by name/URL instead of its SPDX identifier. The
collector then assigns a hash as its license ID. An SPDX-only definition does
not automatically supply the text for that different ID. The checked aliases
`native-gpl.json` and `webrtc-bsd.json` retain those exact IDs and supply full
texts from the component's already-bundled original licenses:

| Definition | Canonical text and provenance |
| --- | --- |
| `native-gpl.json` | `app/src/main/assets/licenses/GPL-3.0.txt`; applies to the GPL native build declared by the two player artifacts. Original Android bridge MIT/LGPL notices remain separate. |
| `webrtc-bsd.json` | `core/room-voice/src/main/assets/licenses/WebRTC-LICENSE.txt`; pinned WebRTC revision `30d5e63ae91da483e06577b5c35ee91cc5e5c3db`. The Android binding's MIT license, patents and third-party notices are also retained. |

When updating a dependency, inspect the selected version and its original
license, then collect the inventory again. Do not guess a license from a similar
name, replace unknown licenses with Apache/MIT, delete inherited notices, or
edit generated `aboutlibraries.json` as the permanent fix. An obsolete alias
should be removed only after no selected artifact references it.

The UI and packaging verifier share the two bundled registries documented in
[licensing](../../docs/licensing.md#inventario-e-testi-consultabili-offline).
Add copied code, models, fonts, shaders and scripts to the applicable registry
with their original notices, including components not represented by Maven.
The registry supplies offline attribution; source/configuration obligations are
documented separately in the native manifests and release source archives.

Gradle declares the project build files, version catalogs and this configuration
as collector inputs, so restoration of a branch or notice changes invalidate
stale collected metadata. CI checks the resulting inventory and texts in every
actual APK before publication. No token or runtime download is required to
display the bundled license texts.

## Selective feature migration

Studio uses RE2/J; its full original BSD-3-Clause (Go) text is bundled separately.
The optional viewing-attention dependencies retain their published Apache-2.0
and annotation notices. Source exchange retains its pinned Rust/TLS metadata.
No metadata override or runtime from the optional video/music engines is included.
