# Cast in Nyanime

The player Cast button discovers Nyanime Companion apps, Google Cast and UPnP/DLNA renderers on the same
Wi-Fi/Ethernet LAN. Fire TV is outside this implementation. This is original
Nyanime code using the Google sender SDK and UPnP AV services.

## Using the phone as a remote

Choose a receiver from the player. The phone pauses local playback and becomes a
remote with pause/resume, seeking, previous/next episode and receiver volume.
Return to the app to browse the library or either discovery Home; the persistent
mini controller and session notification remain available. Opening another video
from the library or discovery pages replaces the remote episode. System media
controls and remote volume remain available while the phone screen is off.
“Continua sul telefono” stops the remote session and
reopens the selected episode at its last observed position.

Companion receivers advertise their controls; the webOS app offers video volume,
WebVTT subtitles and dimming, with explicit TV pairing. See the
[portable protocol](companion-protocol.md).

For DLNA, brightness is shown only when a renderer advertises GetBrightness and
SetBrightness with a valid range, and responds to its initial value query. Google
Cast's default receiver does not offer brightness control. The slider affects the
receiver's image, not the phone screen. Hardware volume keys in the remote and
main app screens address receiver volume when supported.

## Ownership and recovery

`CastController` owns the transport, relay, extension HTTP server, episode queue
and progress writer independently of activity lifetimes. Commands are serialized
and generation-checked; replacement/cancellation waits for previous load cleanup.
Failed preflight preserves the old session. Failure after touching a receiver
ends that attempted session before disposing its relay. Source loading, receiver
loading and individual commands have deadlines. Poll failures back off and stop
automatic retries after a bounded window, exposing explicit reconnection and
return-to-phone controls.

Volume key bursts accumulate in a conflated mailbox: one receiver command can be
in flight and only the latest target waits behind it. Holding a key cannot grow
an unbounded queue of stale volume commands. A buffering watchdog also surfaces
receivers that keep acknowledging requests without starting the video.

`CastHandoffPolicy` keeps buffering observations from resetting the position and
prevents a newly selected episode inheriting the previous MPV clock. The local
view model relinquishes progress writes until a fresh local file loads. Remote
progress is saved periodically and before handoff/stop, respecting incognito and
tracking preferences. Tracker network work runs separately from receiver commands.
Pending download deletion is deferred while a relay may still be streaming files.

`CastRelay` binds a LAN IPv4 address and serves random, session-scoped resource
identifiers. It retains the extension's client, headers and cookies on the phone;
the receiver gets relay URLs only. HLS variants, segments, keys, initialization
maps and subtitle playlists are resolved against the final upstream response URL.
Video bodies stream with a bounded number of simultaneous responses. Playlist and
UPnP XML sizes are bounded. HTTP byte ranges support seeking in downloaded files.
Stopping revokes URLs, cancels upstream requests and closes streams and servers.

## Compatibility boundaries

- The receiver decodes the video. Supported codecs, containers and HLS features
  depend on the TV. There is no transcoding or phone-screen mirroring.
- Anime4K and MPV effects run locally and are not applied to the cast video.
- DASH manifests and HLS variable substitution are rejected explicitly; select an
  HLS/MP4 quality instead. Live playlists with over 30,000 distinct registered
  resources require a new session.
- Google Cast and compatible companion apps expose external WebVTT text tracks. DLNA subtitle selection stays
  with the receiver; external audio tracks require receiver-specific support.
- Device discovery requires multicast access; guest networks/client isolation
  can prevent discovery or access to the phone relay. IPv4 LAN is required.
- The foreground service keeps the relay alive while browsing or screen-off.
  Force-stopping the app ends the relay. Session restoration after process death
  is deliberately disabled because old relay URLs would no longer be valid.
- No emulator or physical TV compatibility result is implied by JVM tests.

## Validation

`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest spotlessCheck`

The Cast tests exercise handoff position rules, XML and HLS validation, byte ranges,
actual HTTP relay responses, source-header handling, bounded streaming and cleanup.
Physical receiver testing is still needed for discovery, decoder compatibility,
long playback, network loss, screen-off and manufacturer-specific controls.

References: [Google sender integration](https://developers.google.com/cast/docs/android_sender/integrate),
[supported media](https://developers.google.com/cast/docs/media),
[media tracks](https://developers.google.com/cast/docs/android_sender/media_tracks),
[UPnP AV specifications](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/).
