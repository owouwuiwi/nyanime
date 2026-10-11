# OTA checkpoints and patch chains

Nyanime installs a normal, signed Android package after every application update.
A patch changes the bytes downloaded, not Android's installation model. No code
is loaded into a running app and no intermediate APK is installed.

## Compatibility comes first

`Nyanime-Release-v2.json` is a bounded closure of exact APK identities, retained
complete checkpoint URLs and patch edges. It accompanies the **complete client
transition** before any patch-only publication is enabled. The old v1 single-hop
manifest and all five signed APKs remain in that transition release.

The public publisher keeps GitHub's `/releases/latest` on the newest recommended
**complete checkpoint**. A patch-only release uses `make_latest=false`. This is
essential for installed clients that only understand full APKs. The capable
client additionally reads the paginated release feed and compares numeric
versions. An old client can install the checkpoint, restart, then discover the
newer patch release; an installation change must bypass the previous version's
update-check cooldown.

Release API requests use the JSON transport. If part of the feed fails, a known
compatible newer candidate can still be offered. Without one, the failure stays
an error and must not start the successful-check cooldown. Complete-only browser
handoffs shortcut a valid recommended latest; other lookups still scan the feed.

Asset acquisition has separate deadlines: 15 seconds per declaration request and
30 minutes per APK/patch request, with a 30-second read inactivity timeout. Large
complete bases must not fail merely because a healthy slow transfer exceeds the
short metadata deadline. Byte limits, cancellation and bounded redirects remain
in force.

An installed hash need not occur in the graph. The planner can download a
retained complete base and apply a bounded route to the target. This covers
unknown/repacked installed bytes, split installations and skipped releases.
Every downloaded or reconstructed reference is checked for exact size, SHA-256,
Android identity and production signer. Only the final package must be newer
than the installation. Reference packages may be older and are never executed.

Old full packages, source archives and patches referenced by any published
closure must **not be deleted**. A fresh complete checkpoint resets the closure,
not the archive. Keep legacy compatibility bridges where their shipped clients
need them; never infer compatibility from the current client's tests alone.

## Declaration and planner

Schema 2 contains `applicationId`, `versionName`, `versionCode`, target hashes,
package nodes (`sha256`, `size`, version, ABI, optional complete `url`) and patch
edges (`from`, `to`, `sha256`, `size`, `url`). URLs must exactly match immutable
assets in the configured application publisher repository. No arbitrary origins,
mutable latest URLs, queries, fragments, user information or cross-ABI edges.

Limits: 512 KiB declaration, depth 5, 160 packages, 320 patches, 8 patch steps,
1 GiB per APK/patch. Edges strictly increase both Android code and numeric
version. Repeated/conflicting identities, cycles and targets unreachable from a
complete base fail validation. Exceeding a bound requires a new complete
checkpoint; increasing an unbounded history download is not a substitute.

The bounded shortest-path search includes the cost of downloading a checkpoint,
or starts at zero cost from verified installed/cached bytes. It prefers a cheap
direct edge over a more expensive chain. Failed bases/edges are excluded during
at most three route attempts. A nonexistent full APK for the latest release is
never invented as a fallback.

Each WorkManager update owns `cache/app-updates/route-<work-id>`. A completed
checkpoint is named by its exact hash and revalidated after a process restart;
partial files are not resume evidence. Explicit cancellation cleans only the
owning job. Reserve space for the patch and reconstructed target plus metadata.
The installed APK is never modified or deleted.

## Publication

Prepare the full transition using `scripts.public_distribution --release-graph`.
All architecture APKs are still built, signed and verified locally. Subsequent
patch-only preparation also needs `--patch-only`, a verified previous closure
via `--previous-release-graph`, and explicit signed base APK directories for
delta generation. Carry retained nodes and edges forward; no invented metadata
for unknown old base hashes. Keep immutable local signed APKs for retries.

`distribution/ota-transition.json` blocks patch-only publication until the full
transition has actually been published and its update path verified. Do not flip
`verifiedUpdate` on the strength of unit tests. Complete checkpoint APKs must
contain the `assets/ota-capabilities.json` marker. This marker is a compatibility
guard, not a substitute for physical update validation.

Reviewed publication metadata still pins all five **target** APK hashes even
when those APKs are not uploaded. The upload set consists of the current patches,
v1/v2 descriptors, checksums, corresponding source ZIP and complete native source
closure. The bot replays each cold-client path from public immutable bases,
reconstructs the exact targets, checks Android signatures/package/version/ABI
with Android build-tools, then performs the same native licensing/source checks
as a complete publication. Missing build-tools, unreachable materials or changed
bytes block publication. No fallback that silently skips source obligations.

Publish patch-only releases only after the complete transition's real update and
anonymous-download proof. Preserve the existing recommended/preview distinction,
production signing identity and bot attribution. An already published asset or
numeric source tag is immutable; resume matching staged assets instead of
rebuilding or overwriting them.
