# Opening and ending videos in private NyanimeB

The native anime detail page contains an **Openings and endings** section after
the description. A song can have two independent choices: **Anime sequence** and
**Full song**. Missing versions offer an explicit YouTube search; ambiguous links
are shown as alternatives rather than silently played or labelled official.

## Identity and providers

The feature consumes the existing verified anime/season association from Work
Information. It does not need an authenticated tracker account, create tracking
entries, interpret extension names or guess a season from a song title. Season
containers and non-anime works do not borrow the songs of an arbitrary season.
Use the existing **Choose anime** association flow when identity is unresolved.

AnimeThemes provides songs, artists, versions, episode ranges and song resources.
Jikan supplements theme strings and associated music videos. Public read APIs
need no new API key. Each provider is fetched and cached independently; one
unavailable endpoint must not discard the other provider or a complete cache.
Jikan requests are serialised with at least 1100 ms between starts, and the shared
metadata transport honours quota/server backoff and bounds response size/time.

Only validated YouTube video IDs are playable. Artist channels, playlists,
foreign hosts and arbitrary URLs are rejected. A generic song link is not
automatically a television sequence; unclassified links remain labelled as such.
Episode ranges are displayed as provided. A current-episode badge is deliberately
not inferred when extension and catalogue episode numbering cannot be verified.

## Playback and search

The existing typed destination enters the integrated FLOW player inside the
same app and returns to the originating detail page. No browser, extra Activity,
fake episode, WebView player or second playback engine is introduced. The
existing ownership coordinator pauses/closes the previous native video only
when playback is explicitly requested/committed.

Search reuses FLOW's InnerTube search engine on explicit user action, supports
bounded pagination, keeps failures distinct from successful empty results and
does not write to search history. It does not initialise the playback runtime or
perform background per-song searches. Candidate selection is always deliberate.

## Persistence and privacy

- Bounded public metadata cache: 24 hours, atomic snapshots, stale/offline reads,
  shared requests and cancellation when the final reader leaves.
- Partial refreshes never replace a complete saved provider snapshot.
- User video choices use ordinary portable preferences and the existing settings
  backup/restore policy. Metadata cache and temporary stream URLs are excluded.
- Incognito choices stay in screen memory. Incognito metadata requests never
  share a flight that writes a persistent snapshot.
- Downloaded-only/offline policies prevent network lookup and online playback;
  thumbnails can use the image cache but cannot open a network connection.
- Explicit spoiler flags hide thumbnails until the user reveals them.

All new code is under `it.lorelore.nyanime`; existing host adapters remain
compatible. This integration belongs to the private NyanimeB repository only.

## Contracts and verification

`ThemeProvider`, `ThemeIdentity`, `AnimeTheme`, `ThemeVersion` and `ThemeVideo`
separate catalogue parsing from presentation. Only the internal online-video
bridge exposes a playback destination and small search result DTOs to the host.
There are no new mandatory extension methods or extension-specific test fixtures.

Focused JVM tests cover identity conflicts, versions, video URL validation,
ambiguous links, deduplication, partial failures, cache freshness, offline reads,
incognito separation, request coalescing/cancellation, timeouts and portable
choices. Device verification must additionally exercise native playback/return,
rotation, PiP, appearance, large fonts and network errors in the signed APK.

Provider documentation:
- <https://github.com/AnimeThemes/animethemes-api-docs>
- <https://github.com/jikan-me/jikan-rest>
