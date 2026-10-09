# Integrated playback

The Android phone and tablet client presents one playback session as a watch page,
a floating mini player or a fullscreen player. Changing presentation does not send
a load-file command, create another MPV instance or transfer progress ownership.

## Ownership

`PlaybackSessionService` is the only creator of `PlayerSession`. It immediately
enters the media-playback foreground state, owns the notification and observes
the process lifecycle. It is not sticky: restarting an Android process does not
silently restart media or claim room readiness.

`PlayerSession` owns the native lifecycle lease, audio focus, MediaSession,
ViewModel, timer, progress and room adapter. The application-wide registry exposes
the current session. Activities observe it and own only the rendering host.

The compatibility entry activities retain existing episode and channel intents.
They route playback to the service and open the selected starting presentation in
MainActivity: watch page by default, or fullscreen. Starting a selection never
inherits Mini from an earlier video. Opening a notification or mini player returns
to the watch page. Only a resumed reader rendering host may request Mini;
background reader compositions cannot change the presentation of a new session.
The mini player has no visible playback controls. A tap opens the watch page;
a deliberate single-finger drag beyond the lower safe area dismisses it. Short
drags and pinches only reposition or resize it. An accessibility dismiss action
remains available without adding chrome. Room observer compatibility is checked
before closing; a refused close restores the visible mini and explains the limit.
ReaderActivity observes the same registry and displays the mini player alongside
the existing reader. Internal navigation does not request external PiP.

`PlaybackRequest`, `PlaybackSnapshot`, `PlaybackCommands` and
`PlaybackPresentation` separate selection, playback state, actions and layout.
Live playback reuses the same engine with the existing channel recovery contract;
episode-only tools do not create fictitious tracking or skip markers for channels.

## Rendering and motion

`MpvPlaybackEngine` owns MPV independently of SurfaceView. A host owns an identity
token in `PlaybackSurfaceLease`. Late resize or destruction from an older host
cannot detach the current surface. An incoming host retains the previous valid
surface until its own surface is ready. Destroying the engine closes the lease
and makes subsequent callbacks inert.

Within a window, the same AndroidView is retained through all three presentations.
Bounds, corner rounding and page visibility share a transition. A presentation
drag interrupts the current transition and continues from its current geometry.
The reduced-motion preference skips timed interpolation without disabling gestures.
The geometry derives from the current window, keyboard and reserved reader controls.
The native SurfaceView child keeps its layout size while the enclosing video changes
size. RenderNode transforms move its surface with the container; page/mini/PiP changes
do not resize its buffer. The maximum display window and source aspect define the
buffer independently of the PiP viewport, including its transition callbacks.
Rotating the same window retains the source-shaped buffer dimensions. The host,
not an additional MPV aspect override, applies fit, crop and stretch exactly once.
Bounds and visibility use one animation clock with
the free Transitions.dev continuous-resize easing adapted to ModernMotion.
Pointer samples use app-window coordinates. Page dismissal preserves the grabbed
vertical point under the finger; fullscreen dismissal translates without resizing
until release. A rotated window starts from its new geometry, never stale pixel
bounds from the previous orientation. Phone watch pages always return to portrait.
The page has a thin, accessible timeline with a separate 48dp hit area. Buffering
is drawn within the same track and the thumb enlarges while held. Fullscreen keeps
the original Seeker timeline, chapter segments, theme colors and timers. Horizontal
video dragging seeks only in fullscreen; page gestures change presentation.
Committed seek feedback is shared by page and fullscreen and held until native
position or playback restart acknowledges it. Stale position samples cannot move
the released thumb backwards; native progress alone remains the input to history,
tracking, room timing and MediaSession. A failed request expires to actual progress,
and media changes cancel the pending feedback. Timer and fullscreen action sit
directly above the rail; action hit areas take precedence over the rail's touch area.
The rail uses an orange-to-red progress gradient and a distinct muted red buffer
lane within the same line. Only small native playback increments are interpolated;
drags, seeks and paused updates remain direct. Shared reversible vector glyphs and
interruptible spring press feedback keep control footprints fixed. The integrated
host enables this motion centrally and disables it for reduced-motion preferences.

## Selection and lifecycle

Database and extension resolution run outside the UI dispatcher. The current
selection keeps playing during preparation. Selection generations reject stale
results; a preparation failure leaves the current video available. At commit,
progress is saved and the video and metadata change together. A bounded PixelCopy
preview can conceal the renderer's file transition; failure to capture a preview
does not prevent playback.

Fullscreen dismissal keeps its released geometry until the portrait window arrives.
A bounded video frame is committed in the app window before requesting rotation,
so the native surface and Android's window animation do not expose different
orientations for one frame. The cover clears after the portrait layout is committed;
the existing engine and audio continue throughout. Cancelling presentation work or
closing the session also cancels the frame callback and releases the cover.
The cover bitmap is rendered synchronously in that commit, not populated by a
later composition effect. The attached SurfaceView uses binary alpha while the
window owns the cover, so its separate compositor layer cannot expose an earlier
rotation transform. Its layout, holder and decoder remain alive. Binary alpha
avoids relying on fractional SurfaceView blending on older Android versions.
See [SurfaceView](https://developer.android.com/reference/kotlin/android/view/SurfaceView).

`PlaybackWindowMode` owns the window rotation policy separately from the engine.
Android's rotated-screenshot animation is disabled for presentation changes. Its
crossfade still reorients the outgoing screenshot into a visible vertical strip.
The incoming portrait host starts from fullscreen geometry and owns its resize
and page reveal on one clock. Reduced motion starts directly at the page geometry.
The fullscreen window flag stays set until the portrait layout commits its cover.
The original flags and rotation animation are restored when the host detaches or
playback closes. Reader windows retain their own policy.
PiP configuration callbacks never request orientation or mutate the fullscreen
window policy; Android owns that temporary window and its resize.
See [window rotation animation](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#rotationAnimation).

Native events capture the playback generation before dispatching to Main. Dispatch
also checks closing and destroyed state. Closing cancels preparation, timers,
observers, handler callbacks and ViewModel work before releasing the native lease.
Surface callbacks operate only through the already-closed lease after teardown.
Updates to installed RIN packages and incompatible processing remain blocked for
the entire playback session, including mini player and background playback.

## Rooms

Nostr remains the room transport. Observer support is negotiated independently of
the existing protocol version. An observer remains a member with a virtual room
timeline, never claims native video readiness and is excluded from buffering gates.
Attaching a new player retains observer status until it has loaded the video.
Ordinary room updates do not open media while observing; opening it is explicit.

Older participants do not advertise observer support. Closing local playback in
such a room offers keeping the video minimized or leaving before stopping it.
Reading and zoom remain personal; opening a reader while watching does not change
the shared content. Room and Cast navigation do not introduce a second decoder.

## Content boundary

The page uses existing episode sorting, filler selection, resume rules,
extension-declared relationships, verified identity merging and source preferences.
Recommendations use available relationship metadata and library affinity.
No catalogue, provider name, domain, selector or provider-specific matching rule is
embedded in these components or their tests.

## Verification

Tests cover narrow windows, aspect ratios, keyboard reservations, presentation
settling, stale surface callbacks, observer gates, reentry and protocol defaults.
Device verification must separately establish session continuity, native progress,
PiP, background, reader coexistence and safe teardown. Compilation and unit tests
alone do not establish physical-device behavior.
