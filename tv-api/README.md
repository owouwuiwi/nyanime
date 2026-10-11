# Live TV contract, version 1

The host implements navigation, local cache, guide rendering, playback, favourites, backup, sharing, Cast and room coordination. Trusted extensions supply catalogues, programme mappings and resolved streams. Existing anime/manga factories are unchanged.

`TvSourceFactory.create` receives a host HTTP client and a private, bounded cache. Factory construction must not perform network IO. Catalogue, programme and playback resolution are suspend calls; cancellation must propagate. Sources must have the same nonzero IDs as their signed RIN manifest and declare `medium: tv`, `extensionLib: 1.0` and a compatible minimum host version.

Channel references are opaque. They must remain stable across catalogue filters and quality changes. The host cannot parse a reference, identify a broadcaster from its name, match a programme by a source-specific heuristic, or guess a stream from a channel logo. Different regional or delayed feeds require different references. Quality alternatives of the same feed belong in one playback result.

`channelLookup` enables an optional batched metadata lookup for favourites/recent channels outside the currently selected country. `channels(references)` must return exact references, distinguishing removed channels from failed requests, without resolving or testing any playable stream. Its local metadata cache is excluded from backup; only the personal references/order are backed up. Unknown capabilities never trigger an inferred lookup.

Times are epoch milliseconds in UTC. `startUtc <= now < endUtc` defines the current programme. No guide, unavailable data, network error and saved data are distinct states. Programmes crossing midnight remain valid; local timezone and daylight-saving formatting belong to the UI.

The host limits HTTP calls to four simultaneous requests per extension, validates and bounds responses, cancels superseded views, caches catalogues for 24 hours and guide windows for one hour. It requests guide data for visible channels, favourites and opened days, never scans every stream to test availability. Failed refreshes preserve saved data without moving their verification timestamps forward.

Live playback has no episode or chapter identity. Progress tracking, intro skipping, completion and media downloads cannot be inferred from a channel. Pause is local; resume returns to the live edge. Rewind, adjustable speed and verified broadcast-time mappings are explicit capabilities, not inferred from names or apparent durations.

Room/share payloads contain only channel identities and public labels. Stream URLs, headers, cookies and credentials are local resolution results. A live room uses the existing room transport, negotiates live support before selecting a channel, allows only the host to select it, and never waits for every participant's buffer. Verified broadcast clocks permit gradual alignment with three-second tolerance. Without a verified mapping or measured clock offset, the UI reports that precise delay cannot be checked. Older clients must not receive a fabricated video episode as a fallback.

Personal TV preferences use the ordinary settings backup. Cache contents and resolved playback URLs are excluded. Incognito viewing does not create recent-channel entries. Removing a source cancels its operations but retains personal references so reinstalling can restore favourites.

The host tests use synthetic source IDs, channel IDs, programme data and reserved example addresses. Provider adapters and live endpoint checks belong in the extension repository.

Implementation status: this module and the staged host integration are not compiled or wired into the Android app yet. Contract, repository and adapter tests are written but their execution and real-device proof remain pending the shared-build coordination release.
