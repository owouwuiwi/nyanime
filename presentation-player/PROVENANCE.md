# Host player UI provenance

This module contains original Nyanime host contracts/adapters and a selected,
modified FLOW player UI subset. It does not contain the FLOW playback
application, extraction, accounts, network services, databases or MELD.

The flow/ Kotlin subtree and values*/flow_player.xml resources derive from
FLOW revision 14afdf9e9426fb8b67e5fcbc76bf4e81af492d16, GPL-3.0-only. The license,
modification notice, original source hashes and retained original files are in
third-party/flow-ui/. Upstream notices remain applicable. The GPL code has not
been relicensed Apache or claimed as original Nyanime work.

Adaptations remove singleton players and stored preferences; the UI takes
caller-owned state, labels, chapters and callbacks. Host MPV/session code owns
playback, tracking, cancellation, native pause, thumbnails, audio, PiP, rooms,
Cast, attention and Anime4K. Extraction and service storyboard/heatmap metadata
are absent. Only scoped UI resources are included. The host supplies the sole
persistent scrub preview.

Original Nyanime contracts, adapters and helpers retain Apache-2.0 following
the owner's express grant of 19 October 2026. The root LICENSE applies to those
original files and does not override the GPL subtree. AndroidX and Material
icons retain Apache-2.0. Combined distribution must also satisfy GPL-3.0 and
all other applicable component terms.

The complete modified module and required build inputs accompany application
corresponding source. There is no FLOW/MELD gitlink or feature dependency.
Source/dependency checks, AAR inspection and APK inspection verify different
parts of this separation. Compilation is not device or distribution proof.
