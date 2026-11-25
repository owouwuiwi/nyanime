# Independent source layers

A layer enhances an already installed source. Source packages and layers keep
their original APKs, signatures, update origins, and consent. The application
provides the same Store, trust and selection rules for manga, anime/video, news
and TV. A layer's author supplies site code, declared Home sections and assets.

## Package contract

Publish a signed, component-free APK with feature `nyanime.layer` and integer
application metadata `nyanime.layer.api=1`. Its asset
`assets/nyanime/package-v1.json` uses package schema 2 and medium `layer`. The
`mangaLayer` field is the v1 layer declaration, including the media it targets.
`nyanime.mangalayer` APKs with medium `manga-layer` remain valid for manga.

Each target declares `medium`, base `packageName`, `sourceId` (zero for a news
package), `signer` (lowercase certificate SHA-256), `minVersionCode`, optional
`maxVersionCode`, and `capabilities`. The manifest keeps `sources` empty. Use
`requires` to declare which selected capabilities a capability needs. For
example, a Home that uses a custom filter can require `catalog`.

| Target | Capabilities | Optional factory |
| --- | --- | --- |
| manga | home, catalog, update, identity, reader | `nyanime.extension.api.layer.MangaLayerFactoryV1` |
| anime | home, catalog, update, identity, player | `nyanime.extension.api.layer.AnimeLayerFactoryV1` |
| news | feed, article, search | `nyanime.news.api.layer.NewsLayerFactoryV1` |
| tv | catalog, guide, player | `nyanime.tv.api.layer.TvLayerFactoryV1` |

For executable layers, set `nyanime.layer.factory` to the factory's class name
and declare this same name in the single manifest `entryPoints` entry. A
declarative Home uses the usual `assets/aniyomi/home-v1.json`, with an empty
`entryPoints` array and no factory metadata. Compile against the appropriate
host ABI without packaging copies of the shared classes. A single factory can
implement multiple family interfaces for a layer with multiple targets.

The factory gets the original source. Returning null for an operation delegates
to it. A layer that replaces reader pages supplies the corresponding image
operations for those pages. Keep persisted source IDs and URL formats stable.
News and TV keep the host's normal result validation and cancellation.

Use a minimum supported base version and leave the upper bound absent when the
layer relies only on the public ABI. Delegate stock operations to the original
source so its fixes arrive independently. A manga layer can implement
`VersionedMangaLayerFactoryV1` to apply a specific workaround to known base
versions while keeping the rest of the layer active on newer releases.

## Store description

The layer author can add `presentation` inside the signed `mangaLayer`
declaration. The Store shows the summary on the layer's card under the base
source and the feature descriptions before installation and approval.

```json
"presentation": {
  "en": {
    "summary": "Adds a Home with recent releases and rankings.",
    "features": [
      {
        "title": "Recent releases",
        "description": "Shows the latest chapters from the source catalogue."
      },
      {
        "title": "Rankings",
        "description": "Lists popular titles for the day and week."
      }
    ]
  }
}
```

Use up to eight locale keys such as `it`, `en` and `pt-BR`. The app checks
the full locale, the language, English and then the first provided entry.
Each entry has a plain-text summary of up to 350 characters and one to eight
features. Each feature has a title of up to 70 characters and a description
of up to 420 characters. Explain what the layer adds, the applicable versions
if a feature is version-specific, and any limitations relevant to the user.

Existing packages may omit this field. The signed target declarations and
capabilities continue to determine compatibility and approval.

## Selection and updates

Import and approve a layer in the Store. A single compatible layer attaches
automatically to its installed base. The original source continues to work on
its own whenever no matching capability is selected. Choose a layer per
capability in the Store when several packages offer the same one.

The Store retains your choice when another layer later claims the same
capability. Missing or cyclic dependencies leave the affected capabilities
inactive. Selecting **Use original** is persistent.

The base APK can update while an incompatible capability is suspended. Limits
on tested base versions may change in a layer update from its existing signer
and origin. A layer update can also drop capabilities. Additional capabilities,
changes to target identities, and the introduction of executable code require
explicit approval. Reader and video operations use a consistent snapshot
for the session, so a source update cannot mix transports.

## Moving an existing library

The Store migration is a separate approval from running a layer. It verifies
the candidate's publisher and source IDs against the active layer, retains the
previous signed APK for recovery, and leaves the app's library rows in place.
Keep a recent Nyanime backup when switching publishers. An APK that cannot be
restored safely, or a candidate that does not match the expected identity, must
remain unswitched.
