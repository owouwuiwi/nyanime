# Manual releases

When private CI cannot build, compile and sign locally with the existing production
identity, run the relevant tests and device checks, and retain all five APKs.
Never create a public release directly with a maintainer token: its author and
uploaded assets would expose that account. The public publisher can run without
rebuilding the Android app.

1. Prepare the reviewed source/docs bundle from the exact build revision using
   `scripts/public_distribution.py`. Notes are UTF-8; checksum files use LF on
   every platform. Verify the version, code, certificate, package, all APK hashes,
   native materials and versioned changelog marker.
2. Publish the reviewed source snapshot, pinned release metadata and immutable
   documentation tag. Preserve historical OTA migration bridges.
3. Dispatch `publish-reviewed-nyanime` to the public publisher with the version,
   `phase: prepare` and validated notes. The bot creates the unpublished draft.
4. Stage the exact reviewed files in that draft. Maintainer uploads are temporary
   and remain unpublished.
5. Dispatch `phase: publish`. The bot checks all hashes, source and native
   materials, replaces temporary attachments with identical bot-owned uploads,
   and publishes in the selected channel.
6. Check the public API after publication: author and every attachment uploader
   must be `github-actions[bot]`; notes must retain accents and the versioned
   format; all architectures must have the expected names and byte hashes.

An explicitly authorized `phase: repair` can correct a release authored by a
maintainer. It validates every complete file before recreating the release with
the bot, preserving the tag, APK bytes and download URL paths. This may briefly
hide the release while replacement attachments are published. Never change an
already distributed signed APK under an existing version.
