# Home HomeContent

HomeContent is the Android Home presentation shared by video and manga. It changes
presentation only: extension manifests, routes, filters, sections, date selection,
ranking, chapter shortcuts, source alternatives and pagination remain authoritative.
The app contains no provider-specific routing or catalogue assumptions.

## Continuous header

The logo and category layout stay fixed. Home lists reserve their initial top space
as content padding rather than as a clipped outer viewport, so covers can scroll
behind the header. The header measures its controls before the scrolling content:
category wrapping and font size do not rely on a fixed header-height estimate.

A single bounded Haze 1.6.10 backdrop area (Apache 2.0) affects the scrolling content;
labels, artwork branding and actions are drawn separately and remain sharp. The
10 dp maximum blur decreases progressively toward its lower edge, with a separate
opacity mask that stays opaque behind the category labels. A warm theme-aware tint
and a soft edge shifted 32 dp upward blend it into the content. The band ends only
12 dp below the measured controls, before the initial cover position. This spatial
gradient is active from the first frame, independent of scroll thresholds and
animation preferences. Android 13+ uses progressive radius; Android 12 uses uniform
GPU blur with the same soft edge. Versions without supported GPU blur retain a readable tint behind the
controls, without opting into experimental CPU/RenderScript blur.

Only the header band is filtered, using 0.8 input scale; the original covers keep
their full resolution. Shared poster navigation lifts the glass separately from
the category labels. The glass is already present on Home's first returning frame;
only the sharp controls fade in. On departure it fades out with the page. The
backdrop and tint receive that opacity explicitly because the blur draws its own
layer; hidden Home content must not filter the destination's toolbar or artwork.
This avoids an unfiltered gap during the return flight. Refresh indicators appear below the controls;
featured rotation and new-content acknowledgement exclude the obscured area.
News and TV retain their secondary fixed controls and existing navigation.

During a poster flight, the target artwork participates in the header backdrop
as one temporary area, using its animated placement. Home binds its current
backdrop when recreated on return; an old navigation route never substitutes
for the current Home capture. The poster's placeholder surface and detail shade
are part of its own layer. Shade opacity follows the same 360 ms bounds timing
in both directions, rather than disappearing while the image is still moving.
The shade and backdrop are transient navigation data and are released with the
route; they do not enter screen serialization or backups.

The navigator owns one movable primary-artwork slot per destination. Loading and
resolved details reuse that slot across their separate composition branches, so
the shared element, caption and enter transition are not recreated when local
data arrives during the flight. The subtitle reserves the same text metrics while
loading. This also applies when entering details from an extension catalogue;
there is no artificial delay or additional data request to hide the state change.

## Featured carousel

- Covers start directly below the Home categories, without a duplicate featured
  heading or browse button. Loading placeholders reserve the same compact layout.
- A lazily composed portrait pager presents one large cover and smaller side previews.
- Swiping works in both directions across the collection boundary. Pager positions
  are virtual; content keys and selected-title recovery use extension identities.
- Refreshes retaining the same identities keep the pager and existing artwork. If
  identities change, the selected work is located in the updated collection.
- Rotation follows six idle seconds. Pointer interaction, parent scrolling, hidden
  sections, source dialogs, navigation and background lifecycle suspend rotation.
- Reduced motion and touch exploration disable automatic rotation. Manual navigation
  remains available. Accessibility reports the real collection size.
- Scale, opacity and perspective are draw-layer changes. The caption reserves two
  title lines and a metadata line; it fades with the gesture without resizing the list.
- Position is announced to accessibility without visible dots below the title.
  Source actions fade out before the centered cover changes.
- Navigation and category activity use tracked composition locals: only consumers
  of the changing state are invalidated, not every descendant of the screen.
- Images retain their previous painter during refresh/failure, with existing retry
  and source-resolution paths. Portrait transitions into detail screens are retained.
- One retained image follows the shared element bounds into the detail hero and back,
  rather than crossfading two differently cropped copies. The overlay clip uses those
  animated bounds, with corner radii driven by the same navigation transition. This
  avoids losing a parent clip or scaling rounded corners outside the visible crop.

## Personal sections

Continue watching presents compact horizontal bookmarks, normally 116 dp tall. A small
portrait cover carries the play affordance; title, episode and elapsed/total time sit
beside it, with a thin progress line. The whole surface resumes playback. The separate
48 dp menu target retains details, hiding with Undo and the optional unfinished-ending
action. Space is reserved beside the metadata for that target, including with large text.
The next bookmark peeks in on phones; tablet widths are bounded. All entries remain
reachable, with stable identity keys. Card height follows measured text, including
Android's non-linear font scaling; skeletons reserve exactly the same geometry.
Unknown duration never fabricates an advancement.

Continue reading retains its compact columns of up to three cards. Manga cards resume
the stored chapter; progress not present in the history contract is not invented.

Updates use compact cards with medium-specific borders. Their existing dismissal,
reading/playback and navigation handlers are unchanged. New-content acknowledgement
still targets the actual local section, including when the user reaches it by scrolling.

## Validation

Unit tests cover both circular boundaries, single-title collections, restored selection,
shared placeholder geometry and the lifetime of return artwork, shading and backdrop.
Native Compose preview definitions include narrow and large-text layouts, light/dark
themes and loading; those definitions are not physical-device proof.

The refinement was checked on an Android 16 phone at 360 dp width: repeated catalogue
openings with long titles, loading-to-resolved details, Home opening/return, header
overlap while scrolling and both themes. The 12 targeted poster/navigation/carousel
tests and scoped formatting check passed. Runtime checks found no AndroidRuntime
errors in that session. Older GPU/fallback paths still require their own devices.

On a physical device, verify swiping through both boundaries, idle rotation, refresh,
return from details, source selection, resume actions and the updates shortcut. Frame
measurements describe the tested device and session, not a guarantee for all hardware.

## Identità delle opere in evidenza

Il carosello mostra una sola opera per riferimento concreto fonte/URL, anche se
la lista contiene schede relative a episodi distinti. La lista di origine resta
intatta. Le copie della stessa variante si uniscono con ID di catalogo concordanti
oppure con lo stesso indirizzo assoluto dell'opera, ricavato dal normale contratto
URL della fonte installata. Restano tutte le alternative apribili, con gli indirizzi
concreti anche dopo un primo raggruppamento. Il contratto generico
del tracking può fornire gli stessi ID senza doverli duplicare nei metadata Home.
ID contraddittori impediscono l'associazione; non si interrogano cataloghi o
dettagli remoti per ordinare o deduplicare il carosello. Gli indirizzi relativi,
le pagine iniziali e gli URL con credenziali non diventano identità condivise;
domini e parametri differenti restano distinti. Il controllo si applica localmente
anche alle pagine in cache e i conflitti persistono dopo il riavvio.

I titoli completi differenti restano distinti anche se condividono un ID di catalogo:
il nome completo viene usato come vincolo di compatibilità, mai come prova di
identità. La normalizzazione gestisce solo Unicode, maiuscole e spazi: non elimina
qualifiche di lingua, edizione, stagione o numeri. Questa politica riguarda la Home;
i raggruppamenti per opera usati da altre funzioni conservano il comportamento precedente.
